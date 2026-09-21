package fuse;

import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;

/**
 * Ничего не хранит и никуда не отправляет - только смотрит, пишет ли программа файл
 * по порядку, от начала к концу, кусками >= PART_MIN, и сообщает об этом в лог.
 * Прообраз частей multipart-загрузки в S3: INFO на каждую набравшуюся часть,
 * WARN на всё, что в эту модель не укладывается.
 *
 * <p>КАК ЧИТАТЬ ЛОГ. Значимых строк две:
 * <pre>
 * PART   path=&lt;путь&gt;                 файл, к которому относится часть
 *        n=&lt;номер&gt;                   порядковый номер части, с 1, в пределах одного открытия
 *        off=&lt;смещение&gt;              где в файле эта часть началась
 *        size=&lt;байт&gt;                 размер части (всегда &gt;= PART_MIN)
 *        writes=&lt;сколько&gt;            сколько вызовов write() её собрали
 *        md5=&lt;хэш&gt;                   MD5 содержимого части, аналог ETag части в S3
 *
 * NONSEQ path=&lt;путь&gt;                 запись не продолжила текущую часть
 *        off=&lt;смещение&gt;              куда пришла запись
 *        size=&lt;байт&gt;                 сколько байт в ней было
 *        expected=&lt;смещение&gt;         куда она должна была лечь, чтобы продолжить часть
 *        partStart=&lt;смещение&gt;        начало части уже ПОСЛЕ обработки этой записи
 *        dropped=&lt;байт&gt;              сколько накопленного пришлось выбросить (0 - ничего)
 * </pre>
 * Хороший прогон: подряд идущие PART с n=1, 2, 3, ... и ни одного NONSEQ - программа пишет
 * строго последовательно, её вывод можно лить в S3 частями прямо по ходу записи.
 * Плохой прогон: NONSEQ с dropped &gt; 0 - программа возвращается назад или прыгает вперёд, так
 * что собрать непрерывные части на лету нельзя; под multipart в таком виде она не годится.
 */
@Slf4j
public class PartLogAction implements Action {

    private static final int PART_MIN = 5 * 1024 * 1024;

    private final Map<Long, Part> parts = new HashMap<>();

    @Override
    public void write(String path, long fh, long offset, byte[] data) {
        // Состояние заводим лениво, на первой записи: об открытии файла нам не сообщают,
        // да и незачем - у файла, в который не писали, и частей никаких нет.
        Part part = parts.computeIfAbsent(fh, key -> new Part());
        if (part.start == -1) {
            // Первая запись для этого дескриптора: с какого бы смещения она ни пришла,
            // оно и становится отправной точкой - сравнивать пока не с чем.
            part.start = offset;
        }
        // "Куда должен лечь следующий байт этой части", если записи идут по порядку.
        long expected = part.start + part.buffer.size();
        if (offset == expected) {
            part.buffer.writeBytes(data);
            part.writes++;
            if (part.buffer.size() >= PART_MIN) {
                byte[] bytes = part.buffer.toByteArray();
                // md5 играет роль ETag части, который вернул бы S3: по нему потом видно,
                // не переписал ли писатель уже "отправленный" диапазон. Выбран не из
                // соображений криптостойкости (для неё MD5 давно не годится), а потому что
                // S3 считает ETag части именно как MD5: нужно то же число, чтобы сверять.
                log.info("PART path={} n={} off={} size={} writes={} md5={}",
                        path, part.number, part.start, bytes.length, part.writes, md5Hex(bytes));
                part.start += bytes.length;
                part.buffer.reset();
                part.number++;
                part.writes = 0;
            }
        } else {
            // Запись не продолжает часть. Что делать дальше, решает её КОНЕЦ, а не начало:
            //  * end <= expected - запись целиком попала в диапазон, который мы уже учли
            //    (программа переписывает свои же байты). Содержимое части от этого могло
            //    поменяться, но её границы - нет, терять нечего, продолжаем копить.
            //  * end > expected - либо скачок вперёд с дырой (offset > expected), либо
            //    частичное перекрытие (offset < expected, но запись вылезла за expected).
            //    В обоих случаях непрерывную часть из накопленного уже не собрать.
            long dropped = 0;
            long end = offset + data.length;
            if (end > expected) {
                dropped = part.buffer.size();
                part.buffer.reset();
                part.writes = 0;
                // Байты ЭТОЙ записи в часть НЕ добавляем: именно она порвала
                // последовательность, поэтому выбрасываем и накопленное, и её саму. Следующую
                // часть начинаем с `end` - с первого байта, про который мы снова сможем
                // сказать, что он идёт следующим по порядку.
                part.start = end;
            }
            log.warn("NONSEQ path={} off={} size={} expected={} partStart={} dropped={}",
                    path, offset, data.length, expected, part.start, dropped);
        }
    }

    @Override
    public void release(String path, long fh) {
        Part part = parts.remove(fh);
        // Остаток меньше PART_MIN до части не дотянул и никуда не поехал - виден только на DEBUG.
        log.debug("PART tail path={} size={}", path, part == null ? 0 : part.buffer.size());
    }

    private static String md5Hex(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Незавершённая часть одного открытого файла. */
    private static final class Part {
        /** Байты, накопленные для текущей части, но ещё не попавшие в лог. */
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        /** Смещение в файле, с которого часть началась; -1 - записей ещё не было. */
        private long start = -1;
        /**
         * Номер текущей части (с 1), нужен только для строки лога. Сквозной в пределах ОДНОГО
         * открытия файла: повторный open начнёт нумерацию заново.
         */
        private int number = 1;
        /** Сколько вызовов write() внесли вклад в текущую часть. */
        private int writes = 0;
    }
}
