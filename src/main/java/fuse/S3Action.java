package fuse;

import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CompletedPart;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Грузит содержимое файла в S3-совместимое хранилище multipart-загрузкой, по одной загрузке на
 * открытый файл: createMultipartUpload на первой записи, uploadPart каждый раз, когда набралось
 * PART_MIN байт, completeMultipartUpload после закрытия файла.
 * <p>
 * Раскладка ключей отличается от файловой: ФС получает /&lt;uuid&gt;/screen, а в S3 удобнее
 * группировать по типу записи, поэтому ключ выходит recordings/screen/&lt;uuid&gt;.
 *
 * <p>ЧТО ГДЕ ВЫПОЛНЯЕТСЯ. Обращения к сети вынесены на виртуальные потоки, потому что синхронная
 * загрузка занимала до 84% времени потока FUSE, а он один на всю ФС (монтируемся с "-s") - пока он
 * ждал ответа S3, стояли все программы и все файлы. На потоке FUSE осталась только работа с
 * памятью: сложить байты в буфер, заметить, что часть набралась, выдать ей номер и отдать массив
 * фоновой задаче. Поэтому карту `uploads` трогает исключительно поток FUSE, а фоновым задачам
 * достаётся ссылка на конкретный Upload; поля, которые после этого читают обе стороны, помечены
 * volatile.
 *
 * <p>ПОЧЕМУ ЭТО НЕ СЪЕДАЕТ ПАМЯТЬ. Виртуальный поток создаётся на каждую задачу, и очередь у
 * такого исполнителя неограниченная - если бы мы просто отправляли части по мере готовности,
 * медленный S3 означал бы неограниченный рост числа буферов по 5 МиБ, то есть OOM. Поэтому есть
 * семафор, считающий байты "в полёте": разрешение берётся на потоке FUSE ДО отправки задачи и
 * возвращается, когда цепочка завершилась - неважно, успехом или ошибкой. Не хватило разрешений -
 * запись честно отваливается с EIO, а не копит память и не вешает ФС навсегда.
 *
 * <p>КАК ЧИТАТЬ ЛОГ: S3 PART - часть принята хранилищем, S3 DONE - загрузка завершена целиком,
 * S3 ABORT - отменена (данных не было или что-то не доехало), S3 LOST - запись попала в уже
 * загруженный диапазон и в объект не войдёт, S3 GAP - писатель прыгнул так далеко вперёд, что
 * копить одну часть стало нельзя.
 */
@Slf4j
public class S3Action implements Action, AutoCloseable {

    /** Минимальный размер части multipart-загрузки в S3; меньше может быть только последняя. */
    private static final int PART_MIN = 5 * 1024 * 1024;

    /**
     * Потолок для буфера части. Буфер умеет расти, когда писатель патчит байты внутри него, но
     * рост надо ограничить: прыжок записи на гигабайт вперёд иначе аллоцировал бы гигабайтный
     * массив, а разница смещений больше 2 ГиБ ещё и переполнила бы int.
     */
    private static final int PART_MAX = 2 * PART_MIN;

    /** Сколько байт частей разрешено держать в полёте одновременно; переопределяется окружением. */
    private static final int IN_FLIGHT_BYTES = mib("S3_INFLIGHT_MB", 64);

    /**
     * Сколько ждать разрешения, прежде чем сдаться и вернуть EIO. Бесконечное ожидание здесь
     * означало бы намертво вставшую файловую систему и "hung task" в ядре.
     */
    private static final Duration ACQUIRE_TIMEOUT = Duration.ofSeconds(seconds("S3_ACQUIRE_TIMEOUT_SEC", 10));

    /** Сколько ждать догрузки при остановке процесса. */
    private static final Duration DRAIN_TIMEOUT = Duration.ofSeconds(seconds("S3_DRAIN_TIMEOUT_SEC", 60));

    private final S3Client s3;
    private final String bucket;
    private final String prefix;

    /** По виртуальному потоку на задачу: они дешёвые и на JDK 25 не пиннят носителя (JEP 491). */
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    /** Разрешения = байты частей в полёте. Именно это не даёт памяти расти быстрее, чем её разгребают. */
    private final Semaphore inFlight = new Semaphore(IN_FLIGHT_BYTES);

    /** Только поток FUSE меняет эту карту. */
    private final Map<Long, Upload> uploads = new HashMap<>();

    /** Завершения закрытых файлов, которые ещё не доехали - их дожидается close(). */
    private final java.util.Set<CompletableFuture<Void>> finishing = ConcurrentHashMap.newKeySet();

    public S3Action(String endpoint, String bucket, String prefix) {
        if (endpoint == null || bucket == null) {
            throw new IllegalStateException(
                    "S3Action: задайте переменные окружения S3_ENDPOINT и S3_BUCKET "
                            + "(ключи доступа берутся из AWS_ACCESS_KEY_ID/AWS_SECRET_ACCESS_KEY)");
        }
        this.bucket = bucket;
        this.prefix = prefix;
        this.s3 = S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                // Регион MinIO не использует, но SDK его требует. Ключи доступа SDK сам
                // возьмёт из стандартной цепочки: AWS_ACCESS_KEY_ID/AWS_SECRET_ACCESS_KEY,
                // ~/.aws/credentials и так далее.
                .region(Region.US_EAST_1)
                // MinIO адресует бакет путём (host/bucket/key), а не поддоменом.
                .forcePathStyle(true)
                .httpClient(UrlConnectionHttpClient.create())
                // Дефолты SDK (2 с на соединение, 30 с на сокет) вместе с ретраями дают минуты,
                // в течение которых задача держала бы разрешения семафора. Ограничиваем явно.
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .apiCallAttemptTimeout(Duration.ofSeconds(30))
                        .apiCallTimeout(Duration.ofSeconds(60))
                        .build())
                .build();
        log.info("S3 INIT endpoint={} bucket={} inFlight={}MiB", endpoint, bucket, IN_FLIGHT_BYTES / 1024 / 1024);
    }

    /** /&lt;uuid&gt;/screen -&gt; recordings/screen/&lt;uuid&gt;: имя файла становится папкой, папка - именем. */
    private String key(String path) {
        String relative = path.substring(1);
        int slash = relative.lastIndexOf('/');
        if (slash < 0) {
            return prefix + "/" + relative;
        }
        return prefix + "/" + relative.substring(slash + 1) + "/" + relative.substring(0, slash);
    }

    @Override
    public void write(String path, long fh, long offset, byte[] data) {
        // Загрузку начинаем лениво, на первой записи: об открытии нам не сообщают, а заводить
        // multipart под файл, в который так ничего и не напишут, значило бы потом её отменять.
        Upload upload = uploads.computeIfAbsent(fh, key -> new Upload(key(path), executor, s3, bucket));
        if (upload.failed) {
            // Асинхронная ошибка случилась уже после того, как виноватая запись вернула успех.
            // Сказать о ней можно только следующей записи - и это единственный шанс,
            // потому что release ничего вызывающему не возвращает.
            throw new IllegalStateException("загрузка " + upload.key + " уже сломана");
        }
        long at = offset - upload.start;
        if (at < 0) {
            // Запись попала в диапазон, который S3 уже принял. Часть неизменяема - починить
            // нельзя, объект уедет без этой правки. Ровно этот случай ловит NONSEQ в логе.
            log.warn("S3 LOST key={} off={} size={} partStart={} (диапазон уже загружен)",
                    upload.key, offset, data.length, upload.start);
            return;
        }
        if (at + data.length > PART_MAX) {
            // Прыжок слишком далеко вперёд: держать одну часть от старого начала до новой записи
            // уже нельзя. Отправляем накопленное и начинаем часть с этого смещения.
            log.warn("S3 GAP key={} off={} size={} partStart={} (часть начата заново)",
                    upload.key, offset, data.length, upload.start);
            flush(upload);
            upload.start = offset;
            at = 0;
        }
        // Запись внутрь ещё не отправленного буфера - нормальная ситуация (так патчат размеры
        // фрагментов mp4). Дыры остаются нулями, как и в файле на диске.
        upload.put((int) at, data);
        if (upload.length >= PART_MIN) {
            flush(upload);
        }
    }

    /**
     * Забирает накопленную часть у потока FUSE и отправляет её загружаться в фоне. Массив
     * передаётся как есть, без копирования: буфер тут же заменяется новым.
     */
    private void flush(Upload upload) {
        if (upload.length == 0) {
            return;
        }
        byte[] bytes = upload.buffer;
        int length = upload.length;
        long partStart = upload.start;
        int number = ++upload.lastPart;
        upload.buffer = new byte[PART_MIN];
        upload.length = 0;
        upload.start += length;

        // Разрешение берём ДО отправки задачи и на потоке FUSE: именно здесь возникает
        // противодавление - если S3 не успевает, притормаживает сам писатель.
        try {
            if (!inFlight.tryAcquire(length, ACQUIRE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                upload.failed = true;
                throw new IllegalStateException("S3 не успевает: " + length + " байт не влезли в лимит "
                        + IN_FLIGHT_BYTES + " за " + ACQUIRE_TIMEOUT.toSeconds() + " с");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            upload.failed = true;
            throw new IllegalStateException("прервано в ожидании места под часть", e);
        }

        CompletableFuture<Void> task = upload.uploadId
                .thenApplyAsync(id -> {
                    String etag = s3.uploadPart(
                            b -> b.bucket(bucket).key(upload.key).uploadId(id).partNumber(number),
                            RequestBody.fromInputStream(new ByteArrayInputStream(bytes, 0, length), length)).eTag();
                    log.info("S3 PART key={} n={} off={} size={} etag={}",
                            upload.key, number, partStart, length, etag);
                    return CompletedPart.builder().partNumber(number).eTag(etag).build();
                }, executor)
                .thenAccept(part -> upload.parts.put(number, part))
                // whenComplete вешается на ВСЮ цепочку и отрабатывает в любом случае. Освобождать
                // разрешение в теле задачи было бы ошибкой: при упавшем createMultipartUpload тело
                // не выполнится вовсе, разрешение утечёт, и через несколько таких случаев ФС встанет.
                .whenComplete((ignored, error) -> {
                    inFlight.release(length);
                    if (error != null) {
                        upload.failed = true;
                        log.warn("S3 PART FAILED key={} n={} size={} error={}",
                                upload.key, number, length, error.toString());
                    }
                });
        upload.tasks.add(task);
    }

    @Override
    public void release(String path, long fh) {
        Upload upload = uploads.remove(fh);
        if (upload == null) {
            return;
        }
        // Последняя часть может быть меньше PART_MIN - S3 это разрешает.
        flush(upload);
        // Завершаем не здесь: сначала должны доехать все части. allOf - это продолжение, а не
        // ожидание в потоке, поэтому release возвращается сразу и ФС не стоит.
        CompletableFuture<Void> finish = CompletableFuture
                .allOf(upload.tasks.toArray(CompletableFuture[]::new))
                .handleAsync((ignored, error) -> {
                    upload.finish(log);
                    return null;
                }, executor);
        finishing.add(finish);
        finish.whenComplete((ignored, error) -> finishing.remove(finish));
    }

    /**
     * Остановка процесса. Виртуальные потоки - демонские, без этого JVM ушла бы молча, потеряв
     * недогруженные хвосты и оставив в бакете висящие multipart-загрузки (за них платят).
     * Вызывается из SpyFs.close() уже ПОСЛЕ размонтирования, то есть новых записей не будет.
     */
    @Override
    public void close() {
        List<CompletableFuture<?>> pending = new ArrayList<>(finishing);
        // Файлы, которые писатель так и не закрыл: их части могут быть ещё в полёте.
        uploads.values().forEach(upload -> pending.addAll(upload.tasks));
        if (!pending.isEmpty()) {
            log.info("S3 DRAIN ожидаем {} незавершённых задач, не дольше {} с",
                    pending.size(), DRAIN_TIMEOUT.toSeconds());
            try {
                CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new))
                        .get(DRAIN_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            } catch (Exception e) {
                log.warn("S3 DRAIN не дождались: {}", e.toString());
            }
        }
        // Незакрытые файлы завершать нечем - их объект был бы обрезан на полуслове.
        uploads.values().forEach(upload -> upload.abort(log, "файл не был закрыт"));
        uploads.clear();
        executor.shutdown();
        try {
            if (!executor.awaitTermination(DRAIN_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
        s3.close();
    }

    private static int mib(String name, int fallback) {
        return Math.toIntExact((long) number(name, fallback) * 1024 * 1024);
    }

    private static int seconds(String name, int fallback) {
        return number(name, fallback);
    }

    private static int number(String name, int fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : Integer.parseInt(value.trim());
    }

    /**
     * Одна multipart-загрузка. Структуру карты uploads меняет только поток FUSE, но сюда смотрят
     * обе стороны: поток FUSE копит буфер и раздаёт номера, фоновые задачи складывают результаты.
     */
    private static final class Upload {
        private final String key;
        private final S3Client s3;
        private final String bucket;
        /** Идентификатор загрузки. Тоже заводится асинхронно, части ждут его через цепочку. */
        private final CompletableFuture<String> uploadId;
        /** Кладут фоновые задачи, читает завершение - отсюда concurrent-карта. */
        private final Map<Integer, CompletedPart> parts = new ConcurrentHashMap<>();
        /** Задачи загрузки частей: их дожидается release и close. */
        private final List<CompletableFuture<Void>> tasks = new ArrayList<>();

        /** Дальше - только поток FUSE. */
        private byte[] buffer = new byte[PART_MIN];
        private int length;
        private long start;
        private int lastPart;

        /** Ставится фоновой задачей, читается потоком FUSE - отсюда volatile. */
        private volatile boolean failed;

        private Upload(String key, ExecutorService executor, S3Client s3, String bucket) {
            this.key = key;
            this.s3 = s3;
            this.bucket = bucket;
            this.uploadId = CompletableFuture.supplyAsync(
                    () -> s3.createMultipartUpload(b -> b.bucket(bucket).key(key)).uploadId(), executor);
        }

        /** Кладёт data по смещению at внутри буфера, раздвигая его при необходимости. */
        private void put(int at, byte[] data) {
            int end = at + data.length;
            if (end > buffer.length) {
                buffer = java.util.Arrays.copyOf(buffer, Math.min(PART_MAX, Math.max(end, buffer.length * 2)));
            }
            System.arraycopy(data, 0, buffer, at, data.length);
            length = Math.max(length, end);
        }

        /** Завершает загрузку. Выполняется в фоне, когда все части уже доехали. */
        private void finish(org.slf4j.Logger log) {
            if (failed || parts.isEmpty()) {
                abort(log, failed ? "часть не доехала" : "записей не было");
                return;
            }
            try {
                // S3 требует части строго по возрастанию номера, иначе InvalidPartOrder.
                List<CompletedPart> ordered = parts.values().stream()
                        .sorted(Comparator.comparingInt(CompletedPart::partNumber))
                        .toList();
                s3.completeMultipartUpload(b -> b.bucket(bucket).key(key).uploadId(uploadId.join())
                        .multipartUpload(m -> m.parts(ordered)));
                log.info("S3 DONE key={} parts={} size={}", key, ordered.size(), start);
            } catch (Exception e) {
                log.warn("S3 FAILED key={} error={}", key, e.toString());
                abort(log, "ошибка завершения");
            }
        }

        private void abort(org.slf4j.Logger log, String reason) {
            try {
                s3.abortMultipartUpload(b -> b.bucket(bucket).key(key).uploadId(uploadId.join()));
                log.warn("S3 ABORT key={} ({})", key, reason);
            } catch (Exception e) {
                log.warn("S3 ABORT FAILED key={} error={}", key, e.toString());
            }
        }
    }
}
