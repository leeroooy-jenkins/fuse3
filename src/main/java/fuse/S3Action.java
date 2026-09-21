package fuse;

import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.UploadPartResponse;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Грузит содержимое файла в S3-совместимое хранилище multipart-загрузкой, по одной
 * загрузке на открытый файл: createMultipartUpload при открытии, uploadPart каждый раз,
 * когда набралось PART_MIN байт, completeMultipartUpload при закрытии.
 * <p>
 * Раскладка ключей отличается от файловой: ФС получает /&lt;uuid&gt;/screen, а в S3 удобнее
 * группировать по типу записи, поэтому ключ выходит recordings/screen/&lt;uuid&gt;.
 * <p>
 * Всё синхронно, внутри вызова ядра - никаких фоновых потоков, как и во всей остальной ФС.
 */
@Slf4j
public class S3Action implements Action {

    /**
     * Минимальный размер части multipart-загрузки в S3; меньше может быть только последняя.
     */
    private static final int PART_MIN = 5 * 1024 * 1024;

    private final S3Client s3;
    private final String bucket;
    private final Map<Long, Upload> uploads = new HashMap<>();

    public S3Action(String endpoint, String bucket, String accessKey, String secretKey) {
        if (endpoint == null || bucket == null || accessKey == null || secretKey == null) {
            throw new IllegalStateException(
                    "S3Action: задайте переменные окружения S3_ENDPOINT, S3_BUCKET, S3_ACCESS_KEY, S3_SECRET_KEY");
        }
        this.bucket = bucket;
        this.s3 = S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)))
                // Регион MinIO не использует, но SDK его требует. Ключи доступа SDK сам
                // возьмёт из стандартной цепочки: AWS_ACCESS_KEY_ID/AWS_SECRET_ACCESS_KEY,
                // ~/.aws/credentials и так далее.
                .region(Region.US_EAST_1)
                // MinIO адресует бакет путём (host/bucket/key), а не поддоменом.
                .forcePathStyle(true)
                .httpClient(UrlConnectionHttpClient.create())
                .build();

        log.info("S3 buckets: {}", this.s3.listBuckets().buckets());
    }

    /**
     * /<uuid>/screen -> recordings/screen/<uuid>: имя файла становится папкой, папка - именем.
     */
    private String key(String path) {
        String relative = path.substring(1);
        int slash = relative.lastIndexOf('/');
        if (slash < 0) {
            return relative;
        }
        return relative.substring(slash + 1) + "/" + relative.substring(0, slash);
    }

    /**
     * Загрузку начинаем лениво, на первой записи в файл: об открытии нам не сообщают, а
     * заводить multipart-загрузку под файл, в который так ничего и не напишут, ни к чему -
     * её потом пришлось бы отменять.
     */
    private Upload upload(String path, long fh) {
        return uploads.computeIfAbsent(fh, key -> {
            String objectKey = key(path);
            String uploadId = s3.createMultipartUpload(b -> b.bucket(bucket).key(objectKey)).uploadId();
            log.debug("S3 INIT key={} uploadId={}", objectKey, uploadId);
            return new Upload(objectKey, uploadId);
        });
    }

    @Override
    public void write(String path, long fh, long offset, byte[] data) {
        Upload upload = upload(path, fh);
        int at = (int) (offset - upload.start);
        if (at < 0) {
            // Запись попала в диапазон, который S3 уже принял. Часть неизменяема - починить
            // нельзя, объект уедет без этой правки. Ровно этот случай ловит NONSEQ в логе.
            log.warn("S3 LOST key={} off={} size={} partStart={} (диапазон уже загружен)",
                    upload.key, offset, data.length, upload.start);
            return;
        }
        // Запись внутрь ещё не отправленного буфера - нормальная ситуация (так патчат
        // размеры фрагментов mp4). Дыры остаются нулями, как и в файле на диске.
        upload.put(at, data);
        if (upload.length >= PART_MIN) {
            uploadPart(upload);
        }
    }

    private void uploadPart(Upload upload) {
        int number = upload.parts.size() + 1;
        int length = upload.length;
        UploadPartResponse response = s3.uploadPart(
                b -> b.bucket(bucket).key(upload.key).uploadId(upload.uploadId).partNumber(number),
                RequestBody.fromInputStream(new ByteArrayInputStream(upload.buffer, 0, length), length));
        upload.parts.add(CompletedPart.builder().partNumber(number).eTag(response.eTag()).build());
        log.info("S3 PART key={} n={} off={} size={} etag={}",
                upload.key, number, upload.start, length, response.eTag());
        upload.start += length;
        upload.length = 0;
    }

    @Override
    public void release(String path, long fh) {
        Upload upload = uploads.remove(fh);
        if (upload == null) {
            return;
        }
        // Последняя часть может быть меньше PART_MIN - S3 это разрешает.
        if (upload.length > 0) {
            uploadPart(upload);
        }
        if (upload.parts.isEmpty()) {
            // Завершить загрузку без единой части нельзя, да и незачем.
            s3.abortMultipartUpload(b -> b.bucket(bucket).key(upload.key).uploadId(upload.uploadId));
            log.debug("S3 ABORT key={} (записей не было)", upload.key);
            return;
        }
        s3.completeMultipartUpload(b -> b.bucket(bucket).key(upload.key).uploadId(upload.uploadId)
                .multipartUpload(m -> m.parts(upload.parts)));
        log.info("S3 DONE key={} parts={} size={}", upload.key, upload.parts.size(), upload.start);
    }

    /**
     * Одна незавершённая multipart-загрузка.
     */
    private static final class Upload {
        private final String key;
        private final String uploadId;
        private final List<CompletedPart> parts = new ArrayList<>();
        /**
         * Накопленные байты следующей части; buffer[0] соответствует смещению start в файле.
         */
        private byte[] buffer = new byte[PART_MIN];
        private int length;
        private long start;

        private Upload(String key, String uploadId) {
            this.key = key;
            this.uploadId = uploadId;
        }

        /**
         * Кладёт data по смещению at внутри буфера, раздвигая его при необходимости.
         */
        private void put(int at, byte[] data) {
            int end = at + data.length;
            if (end > buffer.length) {
                buffer = Arrays.copyOf(buffer, Math.max(end, buffer.length * 2));
            }
            System.arraycopy(data, 0, buffer, at, data.length);
            length = Math.max(length, end);
        }
    }
}
