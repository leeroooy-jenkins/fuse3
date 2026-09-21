package fuse;

import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayOutputStream;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;

/**
 * Ничего не хранит и никуда не отправляет - только смотрит, пишет ли программа файл
 * по порядку, от начала к концу, кусками >= PART_MIN, и сообщает об этом в лог.
 * Прообраз частей multipart-загрузки в S3: INFO на каждую набравшуюся часть,
 * WARN на всё, что в эту модель не укладывается.
 */
@Slf4j
public class PartLogAction implements Action {

    private static final int PART_MIN = 5 * 1024 * 1024;

    private final Map<Long, Part> parts = new HashMap<>();

    @Override
    public void open(String path, long fh, Set<StandardOpenOption> options) {
        parts.put(fh, new Part());
    }

    @Override
    public void write(String path, long fh, long offset, byte[] data) {
        Part part = parts.get(fh);
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
                // не переписал ли писатель уже "отправленный" диапазон.
                log.info("PART path={} n={} off={} size={} writes={} md5={}",
                        path, part.number, part.start, bytes.length, part.writes, md5Hex(bytes));
                part.start += bytes.length;
                part.buffer.reset();
                part.number++;
                part.writes = 0;
            }
        } else {
            // Не по порядку: либо переписывание уже залогированных байт (offset < expected,
            // терять нечего), либо скачок вперёд с дырой (offset+size > expected) - вот он
            // уже делает незавершённую часть недостижимой, её приходится бросить.
            long dropped = 0;
            long end = offset + data.length;
            if (end > expected) {
                dropped = part.buffer.size();
                part.buffer.reset();
                part.writes = 0;
                part.start = end;
            }
            log.warn("NONSEQ path={} off={} size={} expected={} partStart={} dropped={}",
                    path, offset, data.length, expected, part.start, dropped);
        }
    }

    @Override
    public void truncate(String path, long fh, long size) {
        Part part = fh == -1 ? null : parts.get(fh);
        if (part != null && part.buffer.size() > 0) {
            // Изменение размера делает недействительной часть, которую мы копили:
            // раскладка файла только что поехала под ней.
            log.warn("TRUNCATE path={} size={} dropped={}", path, size, part.buffer.size());
            part.buffer.reset();
            part.writes = 0;
            part.start = size;
        }
    }

    @Override
    public void release(String path, long fh) {
        Part part = parts.remove(fh);
        // Остаток меньше PART_MIN до части не дотянул и никуда не поехал - виден только на DEBUG.
        log.debug("RELEASE path={} tail={}", path, part == null ? 0 : part.buffer.size());
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
        /** Номер текущей части (с 1), нужен только для строки лога. */
        private int number = 1;
        /** Сколько вызовов write() внесли вклад в текущую часть. */
        private int writes = 0;
    }
}
