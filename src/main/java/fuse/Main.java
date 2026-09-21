package fuse;

import lombok.extern.slf4j.Slf4j;
import org.cryptomator.jfuse.api.Fuse;

import java.nio.file.Path;
import java.util.List;

@Slf4j
public class Main {
    /**
     * Точка входа: собирает и монтирует файловую систему, после чего блокируется навсегда.
     *
     * @param args [0] - точка монтирования (каталог, поверх которого встаём),
     *             [1] - каталог-хранилище (куда зеркалируем),
     *             [2...] - опции libfuse, пробрасываются как есть (например, "-o allow_other")
     */
    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: SpyFs <mountpoint> <storage> [libfuse options]");
            System.exit(1);
        }
        Path mountPoint = Path.of(args[0]);
        Path storage = Path.of(args[1]);

        String s3Endpoint = System.getenv("S3_ENDPOINT");
        String s3Bucket = System.getenv("S3_BUCKET");
        String s3AccessKey = System.getenv("S3_ACCESS_KEY");
        String s3SecretKey = System.getenv("S3_SECRET_KEY");
        var actions = List.of(
                new PartLogAction(),
                new S3Action(s3Endpoint, s3Bucket, s3AccessKey, s3SecretKey)
        );
        // builder.errno() даёт нам настоящие константы errno.h платформы (EIO, ENOENT, ...),
        // так как их числовые значения различаются между Linux/macOS/Windows.
        var builder = Fuse.builder();
        var fuse = builder.build(new SpyFs(builder.errno(), storage, actions));
        // Если JVM убивают (Ctrl+C, `docker stop`/SIGTERM), сначала аккуратно размонтируемся,
        // а не оставляем висячую точку монтирования.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                fuse.close();
            } catch (Exception e) {
                log.warn("close failed: {}", e.toString());
            }
        }));

        // "-s" велит libfuse работать в один поток: ядро присылает нам по одному запросу за
        // раз, поэтому read()/write()/и т.д. никогда не выполняются параллельно и не нужна
        // блокировка (см. поле `handles`). "-f" (остаться на переднем плане) и саму точку
        // монтирования jfuse добавляет сам.
        // Арифметика ниже: args[0] и args[1] мы уже разобрали (точка монтирования и
        // хранилище), всё остальное - опции libfuse, которые пробрасываем как есть. Отсюда
        // длина массива (args.length - 2) + 1 и копирование с args[2] в flags[1]: место
        // flags[0] занимает "-s".
        String[] flags = new String[args.length - 2 + 1];
        flags[0] = "-s";
        System.arraycopy(args, 2, flags, 1, args.length - 2);

        // Первый аргумент mount() - fsname: имя, под которым ФС покажется в колонке устройства
        // у `mount` и `df`. Больше ни на что не влияет.
        // mount() блокируется, пока файловая система реально не примонтируется, а затем
        // возвращает управление, пока цикл обработки событий FUSE продолжает работать в
        // фоновом потоке.
        fuse.mount("fuse", mountPoint, flags);
        // join() на ТЕКУЩЕМ потоке не завершается никогда: поток ждёт собственной смерти. Это
        // намеренная вечная блокировка main - без неё JVM тут же завершилась бы и
        // размонтировала ФС. Выходим отсюда только по сигналу, и тогда отработает shutdown
        // hook, зарегистрированный выше.
        Thread.currentThread().join();
    }
}
