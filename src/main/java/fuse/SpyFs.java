package fuse;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.cryptomator.jfuse.api.DirFiller;
import org.cryptomator.jfuse.api.Errno;
import org.cryptomator.jfuse.api.FileInfo;
import org.cryptomator.jfuse.api.Fuse;
import org.cryptomator.jfuse.api.FuseOperations;
import org.cryptomator.jfuse.api.Stat;
import org.cryptomator.jfuse.api.Statvfs;
import org.cryptomator.jfuse.api.TimeSpec;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;

/**
 * FUSE ("Filesystem in Userspace") позволяет обычному процессу реализовать файловую систему:
 * ядро Linux перенаправляет каждый системный вызов программы к смонтированному каталогу
 * (open, read, write, readdir, ...) этому процессу в виде вызова метода, а то, что вернёт
 * этот класс, становится результатом системного вызова. Поэтому SpyFs сам данные не хранит -
 * он лишь зеркалирует чтения/записи в настоящие файлы под `storage` и логирует, что видит.
 * jfuse - это Java-обвязка: она превращает вызовы ядра в вызовы методов FuseOperations
 * ниже, используя под капотом C-библиотеку libfuse.
 */
@Slf4j
@RequiredArgsConstructor
public class SpyFs implements FuseOperations {

    /**
     * Как только у дескриптора накопилось столько последовательных байт, мы логируем это как
     * "часть" (part) - прообраз части multipart-загрузки в S3, у которой тоже есть минимальный
     * размер.
     */
    private static final int PART_MIN = 5 * 1024 * 1024;

    private final Errno errno;
    private final Path storage;

    /**
     * FUSE опознаёт открытый файл по непрозрачному номеру "file handle", который МЫ САМИ
     * выбираем в open()/create(), а ядро затем возвращает нам же в каждом следующем вызове
     * (read, write, release, ...) через fi.getFh(). По этой карте мы находим своё состояние
     * для конкретного открытия файла.
     */
    private final Map<Long, Handle> handles = new HashMap<>();
    private long nextFh = 1;

    static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: SpyFs <mountpoint> <storage> [libfuse options]");
            System.exit(1);
        }
        Path mountPoint = Path.of(args[0]);
        Path storage = Path.of(args[1]);

        // builder.errno() даёт нам настоящие константы errno.h платформы (EIO, ENOENT, ...),
        // так как их числовые значения различаются между Linux/macOS/Windows.
        var builder = Fuse.builder();
        var fuse = builder.build(new SpyFs(builder.errno(), storage));
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
        // блокировка. "-f" (остаться на переднем плане) и саму точку монтирования jfuse
        // добавляет сам.
        String[] flags = new String[args.length - 2 + 1];
        flags[0] = "-s";
        System.arraycopy(args, 2, flags, 1, args.length - 2);

        // mount() блокируется, пока файловая система реально не примонтируется, а затем
        // возвращает управление, пока цикл обработки событий FUSE продолжает работать в
        // фоновом потоке. Без join() ниже JVM тут же завершилась бы и размонтировала ФС.
        fuse.mount("fuse", mountPoint, flags);
        Thread.currentThread().join();
    }

    @Override
    public Errno errno() {
        return errno;
    }

    /**
     * jfuse регистрирует в libfuse только перечисленные здесь операции; всё остальное -
     * подкаталоги, симлинки, xattr, проверки прав, ... - просто никогда не вызывается, и ядро
     * само получает общую ошибку "не реализовано" (ENOSYS).
     */
    @Override
    public Set<Operation> supportedOperations() {
        return EnumSet.of(
                Operation.GET_ATTR, Operation.READ_DIR,
                Operation.CREATE, Operation.OPEN, Operation.READ, Operation.WRITE,
                Operation.TRUNCATE, Operation.RELEASE, Operation.FSYNC, Operation.FLUSH,
                Operation.CHMOD, Operation.CHOWN, Operation.UTIMENS,
                Operation.UNLINK, Operation.RENAME, Operation.STATFS
        );
    }

    /**
     * В каждый вызов FUSE приходит абсолютный путь ВНУТРИ ТОЧКИ МОНТИРОВАНИЯ
     * (например, "/report.bin"), а не настоящий путь в файловой системе. У нас плоское
     * монтирование, поэтому отображение на каталог-хранилище - это просто отбрасывание
     * ведущего "/".
     */
    private Path resolve(String path) {
        return storage.resolve(path.substring(1));
    }

    /**
     * getattr - это аналог stat(2) в мире FUSE: ядро вызывает его постоянно (перед open,
     * перед read, для `ls -l`, ...), чтобы узнать, существует ли путь, и если да - его тип и
     * размер. Мы заполняем ровно то, что нужно плоской read/write-файловой системе: бит типа
     * записи (каталог или обычный файл), объединённый через "или" с фиксированными правами,
     * счётчик ссылок и - для файлов - реальный размер на диске.
     * Сам jfuse сразу после монтирования опрашивает "/jfuse_mount_probe", чтобы убедиться,
     * что монтирование действительно работает; поскольку такого файла в хранилище нет, этот
     * запрос закономерно (и правильно) получает здесь ENOENT, как и любое другое отсутствующее
     * имя.
     */
    @Override
    public int getattr(String path, Stat stat, FileInfo fi) {
        log.trace("GETATTR path={}", path);
        if ("/".equals(path)) {
            stat.setMode(Stat.S_IFDIR | 0755);
            stat.setNLink((short) 2);
            return 0;
        }
        Path file = resolve(path);
        if (!Files.isRegularFile(file)) {
            // Операция FUSE сообщает об ошибке, возвращая *отрицательное* значение errno
            // (например, -ENOENT), а не бросая исключение - именно так libfuse ждёт ошибки.
            return -errno.enoent();
        }
        try {
            stat.setMode(Stat.S_IFREG | 0644);
            stat.setNLink((short) 1);
            stat.setSize(Files.size(file));
            return 0;
        } catch (IOException e) {
            log.warn("GETATTR path={} error={}", path, e.toString());
            return -errno.eio();
        }
    }

    /**
     * readdir отвечает на `ls`/`readdir(3)` для каталога. Поскольку это *единственный*
     * каталог во всём монтировании (подкаталоги здесь принципиально не поддерживаются), ядро
     * всегда просит список только для "/". Каждая запись сообщается через filler.fill(name);
     * записи "." и ".." - это общепринятое соглашение, ожидаемое даже при том, что здесь ими
     * никто больше не пользуется.
     */
    @Override
    public int readdir(String path, DirFiller filler, long offset, FileInfo fi, int flags) {
        log.trace("READDIR path={}", path);
        try {
            filler.fill(".");
            filler.fill("..");
            try (var stream = Files.newDirectoryStream(storage)) {
                for (Path child : stream) {
                    if (Files.isRegularFile(child)) {
                        filler.fill(child.getFileName().toString());
                    }
                }
            }
            return 0;
        } catch (IOException e) {
            log.warn("READDIR path={} error={}", path, e.toString());
            return -errno.eio();
        }
    }

    // create() - это то, что ядро вызывает для open(O_CREAT) по имени, которого ещё нет
    // (файл создаётся И открывается за один шаг); open() - обычный open() уже существующего
    // файла. Обоим нужно вернуть file handle, поэтому они используют общий openInternal().
    @Override
    public int create(String path, int mode, FileInfo fi) {
        log.debug("CREATE path={} flags={} opts={}", path, Integer.toOctalString(fi.getFlags()), fi.getOpenFlags());
        return openInternal(path, fi, true);
    }

    @Override
    public int open(String path, FileInfo fi) {
        log.debug("OPEN path={} flags={} opts={}", path, Integer.toOctalString(fi.getFlags()), fi.getOpenFlags());
        return openInternal(path, fi, false);
    }

    private int openInternal(String path, FileInfo fi, boolean create) {
        Path file = resolve(path);
        // Всегда открываем READ+WRITE независимо от того, что запрашивал вызывающий: так
        // метод проще, и здесь ни на что не влияет строгое соблюдение O_RDONLY/O_WRONLY.
        Set<StandardOpenOption> opts = EnumSet.of(StandardOpenOption.READ, StandardOpenOption.WRITE);
        if (create) {
            opts.add(StandardOpenOption.CREATE);
        }
        // В libfuse 3 O_TRUNC приходит именно как один из этих флагов открытия, а не как
        // отдельный вызов truncate(), поэтому именно здесь мы его и проверяем.
        if (fi.getOpenFlags().contains(StandardOpenOption.TRUNCATE_EXISTING)) {
            opts.add(StandardOpenOption.TRUNCATE_EXISTING);
        }
        try {
            FileChannel channel = FileChannel.open(file, opts);
            // Отдаём ядру номер file handle по своему выбору; ядро вернёт нам ровно этот же
            // номер в каждом следующем вызове для этого открытого файла (см. `handles`).
            long fh = nextFh++;
            fi.setFh(fh);
            handles.put(fh, new Handle(channel));
            return 0;
        } catch (IOException e) {
            log.warn("OPEN path={} error={}", path, e.toString());
            return -errno.eio();
        }
    }

    /**
     * read() - это ПОЗИЦИОННОЕ чтение: в отличие от обычного InputStream, FUSE всегда
     * сообщает нам точное место в файле (`offset`), потому что позицию в файле отслеживает
     * ядро, а не мы. Заполняем `buf` вплоть до `count` байт, останавливаясь раньше только при
     * достижении конца файла, и сообщаем в ответ, сколько байт реально удалось отдать.
     */
    @Override
    public int read(String path, ByteBuffer buf, long count, long offset, FileInfo fi) {
        Handle handle = handles.get(fi.getFh());
        if (handle == null) {
            // Ядро прислало нам номер file handle, который мы никогда не выдавали (или уже
            // освободили) - "bad file descriptor" - стандартная errno для такого случая.
            return -errno.ebadf();
        }
        try {
            long total = 0;
            while (total < count) {
                int r = handle.channel.read(buf, offset + total);
                if (r < 0) {
                    break;
                }
                total += r;
            }
            return (int) total;
        } catch (IOException e) {
            log.warn("READ path={} error={}", path, e.toString());
            return -errno.eio();
        }
    }

    /**
     * write() тоже позиционный (см. read() выше). `buf` - это нативный буфер,
     * который живёт только на время этого вызова.
     * Поэтому первым делом копируем его в обычный byte[], который можно сохранить (в Handle.part)
     * и после возврата из метода.
     */
    @Override
    public int write(String path, ByteBuffer buf, long count, long offset, FileInfo fi) {
        Handle handle = handles.get(fi.getFh());
        if (handle == null) {
            return -errno.ebadf();
        }
        byte[] data = new byte[(int) count];
        buf.get(data);

        // Файл в хранилище всегда получает запись полностью, что бы дальше ни решила логика
        // отслеживания частей ниже - корректность зеркалируемого файла никогда не зависит от
        // логики логирования.
        try {
            ByteBuffer toWrite = ByteBuffer.wrap(data);
            long written = 0;
            while (toWrite.hasRemaining()) {
                written += handle.channel.write(toWrite, offset + written);
            }
        } catch (IOException e) {
            log.warn("WRITE path={} error={}", path, e.toString());
            return -errno.eio();
        }

        trackPart(path, handle, offset, data);
        return (int) count;
    }

    /**
     * Вот тут и происходит собственно "слежка": нам не важно, что означают байты, важно лишь,
     * пишет ли программа файл по порядку, от начала к концу, кусками >= PART_MIN - именно
     * это нужно multipart-загрузке в S3. Каждый открытый дескриптор ведёт свою незавершённую
     * часть в Handle.part; `partStart` - это то место *в файле*, где эта часть началась.
     */
    private void trackPart(String path, Handle handle, long offset, byte[] data) {
        if (handle.partStart == -1) {
            // Первая запись, увиденная для этого дескриптора: с какого бы смещения она ни
            // пришла, оно и становится отправной точкой - сравнивать пока не с чем.
            handle.partStart = offset;
        }
        // "Куда должен лечь следующий байт этой части", если записи и дальше идут по порядку.
        long expected = handle.partStart + handle.part.size();
        if (offset == expected) {
            // Ровно следующий байт по порядку: добавляем его в буфер части.
            handle.part.writeBytes(data);
            handle.writes++;
            if (handle.part.size() >= PART_MIN) {
                // Накопилось достаточно для одной части в стиле S3: логируем её и начинаем
                // следующую с того места, где закончилась эта.
                logPart(path, handle);
                handle.partStart += handle.part.size();
                handle.part.reset();
                handle.partNumber++;
                handle.writes = 0;
            }
        } else {
            // Не по порядку: либо переписывание уже залогированных байт (offset < expected,
            // безобидно - терять нечего), либо скачок вперёд, оставляющий дыру
            // (offset+size > expected). Только второй случай заставляет нас отказаться от
            // незавершённой части, поскольку последовательно завершить её уже нельзя.
            long dropped = 0;
            long end = offset + data.length;
            if (end > expected) {
                dropped = handle.part.size();
                handle.part.reset();
                handle.writes = 0;
                handle.partStart = end;
            }
            log.warn("NONSEQ path={} off={} size={} expected={} partStart={} dropped={}",
                    path, offset, data.length, expected, handle.partStart, dropped);
        }
    }

    private void logPart(String path, Handle handle) {
        byte[] bytes = handle.part.toByteArray();
        // MD5 здесь играет роль ETag части, который вернул бы S3, так что по этой строке
        // лога потом можно проверить, что "загруженный" диапазон никто тайком не переписал.
        log.info("PART path={} n={} off={} size={} writes={} md5={}",
                path, handle.partNumber, handle.partStart, bytes.length, handle.writes, md5Hex(bytes));
    }

    private static String md5Hex(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * truncate() отвечает и за truncate(2), и за ftruncate(2) - изменение размера файла,
     * которое может и УВЕЛИЧИВАТЬ его (новая область читается как нули). FileChannel умеет
     * только уменьшать, поэтому вместо него используем RandomAccessFile.setLength(), который
     * работает в обе стороны.
     */
    @Override
    public int truncate(String path, long size, FileInfo fi) {
        log.debug("TRUNCATE path={} size={}", path, size);
        try (RandomAccessFile raf = new RandomAccessFile(resolve(path).toFile(), "rw")) {
            raf.setLength(size);
        } catch (IOException e) {
            log.warn("TRUNCATE path={} error={}", path, e.toString());
            return -errno.eio();
        }
        // Изменение размера делает недействительной любую часть, которую мы копили для этого
        // дескриптора - раскладка файла только что изменилась под ней, - поэтому сбрасываем
        // буфер и начинаем отслеживание части заново с нового конца файла, как и при дыре
        // (NONSEQ).
        Handle handle = fi == null ? null : handles.get(fi.getFh());
        if (handle != null && handle.part.size() > 0) {
            log.warn("TRUNCATE path={} size={} dropped={}", path, size, handle.part.size());
            handle.part.reset();
            handle.writes = 0;
            handle.partStart = size;
        }
        return 0;
    }

    /**
     * release() вызывается один раз, когда закрывается ПОСЛЕДНЯЯ ссылка на открытый файл (close(2)).
     * Именно здесь мы освобождаем номер file handle и FileChannel.
     * Байты части, всё ещё оставшиеся в буфере (`tail`), попросту не дотянули до PART_MIN и отбрасываются
     * без лишних слов - это видно только на уровне DEBUG.
     */
    @Override
    public int release(String path, FileInfo fi) {
        Handle handle = handles.remove(fi.getFh());
        if (handle == null) {
            return -errno.ebadf();
        }
        log.debug("RELEASE path={} tail={}", path, handle.part.size());
        try {
            handle.channel.close();
            return 0;
        } catch (IOException e) {
            log.warn("RELEASE path={} error={}", path, e.toString());
            return -errno.eio();
        }
    }

    /**
     * fsync(2)/fdatasync(2): вызывающему нужно, чтобы записи надёжно попали на диск, прежде
     * чем продолжать. Ненулевой `datasync` означает семантику fdatasync
     * (только содержимое файла, без метаданных вроде времени изменения) - этому соответствует
     * FileChannel.force(false).
     */
    @Override
    public int fsync(String path, int datasync, FileInfo fi) {
        log.debug("FSYNC path={}", path);
        Handle handle = handles.get(fi.getFh());
        if (handle == null) {
            return -errno.ebadf();
        }
        try {
            handle.channel.force(datasync == 0);
            return 0;
        } catch (IOException e) {
            log.warn("FSYNC path={} error={}", path, e.toString());
            return -errno.eio();
        }
    }

    // flush() срабатывает на каждый close(2) (даже если файл ещё держат открытым другие
    // дескрипторы) отдельно от release(). chmod/chown/utimens меняют права, владельца и
    // время доступа. Для одноразовой ФС-шпиона ничто из этого не важно, поэтому просто
    // сообщаем об успехе, ничего не делая - ядру достаточно получить 0.
    @Override
    public int flush(String path, FileInfo fi) {
        return 0;
    }

    @Override
    public int chmod(String path, int mode, FileInfo fi) {
        return 0;
    }

    @Override
    public int chown(String path, int uid, int gid, FileInfo fi) {
        return 0;
    }

    @Override
    public int utimens(String path, TimeSpec atime, TimeSpec mtime, FileInfo fi) {
        return 0;
    }

    // unlink(2): удалить имя. rename(2): переместить/перезаписать имя. `flags` в rename могут
    // запрашивать более новую семантику Linux - атомарный обмен или запрет замены
    // (renameat2); мы её не поддерживаем, поэтому любое ненулевое значение flags сразу
    // отклоняется, а не молча игнорируется.
    @Override
    public int unlink(String path) {
        log.debug("UNLINK path={}", path);
        try {
            Files.delete(resolve(path));
            return 0;
        } catch (IOException e) {
            log.warn("UNLINK path={} error={}", path, e.toString());
            return -errno.eio();
        }
    }

    @Override
    public int rename(String oldpath, String newpath, int flags) {
        log.debug("RENAME path={} -> {}", oldpath, newpath);
        if (flags != 0) {
            return -errno.einval();
        }
        try {
            Files.move(resolve(oldpath), resolve(newpath), StandardCopyOption.REPLACE_EXISTING);
            return 0;
        } catch (IOException e) {
            log.warn("RENAME path={} error={}", oldpath, e.toString());
            return -errno.eio();
        }
    }

    /**
     * statfs(2) - это то, на чём работает `df`: общий/свободный объём файловой системы.
     * Мы просто передаём настоящие цифры того диска, на котором лежит `storage`, блоками по фиксированным 4096 байт.
     */
    @Override
    public int statfs(String path, Statvfs statvfs) {
        log.trace("STATFS path={}", path);
        try {
            FileStore store = Files.getFileStore(storage);
            long bsize = 4096;
            statvfs.setBsize(bsize);
            statvfs.setFrsize(bsize);
            statvfs.setBlocks(store.getTotalSpace() / bsize);
            statvfs.setBfree(store.getUnallocatedSpace() / bsize);
            statvfs.setBavail(store.getUsableSpace() / bsize);
            statvfs.setNameMax(255);
            return 0;
        } catch (IOException e) {
            log.warn("STATFS path={} error={}", path, e.toString());
            return -errno.eio();
        }
    }

    /**
     * Состояние на одно открытие файла, хранится в `handles` по номеру file handle. Живёт
     * ровно столько, сколько файл остаётся открытым под этим дескриптором - повторное
     * открытие того же пути создаёт новый Handle и, соответственно, отслеживание части
     * начинается заново, поскольку новый дескриптор - это новый, ещё не изученный паттерн
     * записи.
     */
    private static final class Handle {
        private final FileChannel channel;
        /**
         * Байты, накопленные для части, которая сейчас собирается, но ещё не попала в лог.
         */
        private final ByteArrayOutputStream part = new ByteArrayOutputStream();
        /**
         * Смещение в файле, с которого началась текущая незавершённая часть; -1 означает
         * "ни одной записи ещё не было".
         */
        private long partStart = -1;
        /**
         * Номер текущей незавершённой части (с 1), нужен только для строки лога (n=...).
         */
        private int partNumber = 1;
        /**
         * Сколько вызовов write() внесли вклад в текущую незавершённую часть.
         */
        private int writes = 0;

        private Handle(FileChannel channel) {
            this.channel = channel;
        }
    }
}
