package fuse;

import lombok.extern.slf4j.Slf4j;
import org.cryptomator.jfuse.api.DirFiller;
import org.cryptomator.jfuse.api.Errno;
import org.cryptomator.jfuse.api.FileInfo;
import org.cryptomator.jfuse.api.Fuse;
import org.cryptomator.jfuse.api.FuseOperations;
import org.cryptomator.jfuse.api.Stat;
import org.cryptomator.jfuse.api.Statvfs;
import org.cryptomator.jfuse.api.TimeSpec;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * FUSE ("Filesystem in Userspace") позволяет обычному процессу реализовать файловую систему:
 * ядро Linux перенаправляет каждый системный вызов программы к смонтированному каталогу
 * (open, read, write, readdir, ...) этому процессу в виде вызова метода, а то, что вернёт
 * этот класс, становится результатом системного вызова.
 * jfuse - это Java-обвязка: она превращает вызовы ядра в вызовы методов FuseOperations
 * ниже, используя под капотом C-библиотеку libfuse.
 * <p>
 * Сам SpyFs данные не хранит и никуда не отправляет: операции над содержимым файлов он
 * раздаёт списку экшенов (см. {@link Action}), а за собой оставляет только пространство
 * имён - getattr/readdir/mkdir/unlink/rename/statfs поверх каталога-хранилища.
 */
@Slf4j
public class SpyFs implements FuseOperations {

    private final Errno errno;
    private final Path storage;
    private final List<Action> actions;

    /** Выданные ядру номера file handle - по ним отличаем свой дескриптор от чужого. */
    private final Set<Long> handles = new HashSet<>();
    private long nextFh = 1;

    public SpyFs(Errno errno, Path storage) {
        this.errno = errno;
        this.storage = storage;
        // Порядок в этом списке - это и есть порядок выполнения: сначала байты ложатся
        // на диск, затем из них считаются части для лога, затем они уезжают в S3.
        this.actions = List.of(
                new StorageAction(storage),
                new PartLogAction(),
                new S3Action(System.getenv("S3_ENDPOINT"), System.getenv("S3_BUCKET"), "recordings")
        );
    }

    public static void main(String[] args) throws Exception {
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
     * симлинки, xattr, проверки прав, ... - просто никогда не вызывается, и ядро само
     * получает общую ошибку "не реализовано" (ENOSYS).
     */
    @Override
    public Set<Operation> supportedOperations() {
        return EnumSet.of(
                Operation.GET_ATTR, Operation.READ_DIR, Operation.MKDIR, Operation.RMDIR,
                Operation.CREATE, Operation.OPEN, Operation.READ, Operation.WRITE,
                Operation.TRUNCATE, Operation.RELEASE, Operation.FSYNC, Operation.FLUSH,
                Operation.CHMOD, Operation.CHOWN, Operation.UTIMENS,
                Operation.UNLINK, Operation.RENAME, Operation.STATFS
        );
    }

    /**
     * В каждый вызов FUSE приходит абсолютный путь ВНУТРИ ТОЧКИ МОНТИРОВАНИЯ
     * (например, "/3f2504e0-.../screen"), а не настоящий путь в файловой системе.
     * Отображение на каталог-хранилище - это просто отбрасывание ведущего "/"; Path.resolve()
     * сам корректно разберёт вложенные сегменты пути, так что эта функция не меняется от
     * глубины пути.
     */
    private Path resolve(String path) {
        return storage.resolve(path.substring(1));
    }

    /**
     * getattr - это аналог stat(2) в мире FUSE: ядро вызывает его постоянно (перед open,
     * перед read, для `ls -l`, ...), чтобы узнать, существует ли путь, и если да - его тип и
     * размер. Мы заполняем ровно то, что нужно: бит типа записи (каталог или обычный файл),
     * объединённый через "или" с фиксированными правами, счётчик ссылок и - для файлов -
     * реальный размер на диске. Каталог здесь может быть не только корнем, но и папкой сессии
     * на любой глубине - проверяем это через resolve(path), а не только через сравнение с "/".
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
        Path node = resolve(path);
        if (Files.isDirectory(node)) {
            stat.setMode(Stat.S_IFDIR | 0755);
            stat.setNLink((short) 2);
            return 0;
        }
        if (!Files.isRegularFile(node)) {
            // Операция FUSE сообщает об ошибке, возвращая *отрицательное* значение errno
            // (например, -ENOENT), а не бросая исключение - именно так libfuse ждёт ошибки.
            return -errno.enoent();
        }
        try {
            stat.setMode(Stat.S_IFREG | 0644);
            stat.setNLink((short) 1);
            stat.setSize(Files.size(node));
            return 0;
        } catch (IOException e) {
            log.warn("GETATTR path={} error={}", path, e.toString());
            return -errno.eio();
        }
    }

    /**
     * readdir отвечает на `ls`/`readdir(3)` для каталога - и для корня, и для вложенной
     * папки сессии, поэтому листим именно resolve(path), а не всегда корень хранилища.
     * Каждая запись сообщается через filler.fill(name); записи "." и ".." - это
     * общепринятое соглашение, ожидаемое даже при том, что здесь ими никто не пользуется.
     */
    @Override
    public int readdir(String path, DirFiller filler, long offset, FileInfo fi, int flags) {
        log.trace("READDIR path={}", path);
        try {
            filler.fill(".");
            filler.fill("..");
            try (var stream = Files.newDirectoryStream(resolve(path))) {
                for (Path child : stream) {
                    filler.fill(child.getFileName().toString());
                }
            }
            return 0;
        } catch (IOException e) {
            log.warn("READDIR path={} error={}", path, e.toString());
            return -errno.eio();
        }
    }

    // mkdir(2): программа-писатель создаёт под каждую сессию отдельную папку (обычно с
    // именем-UUID) перед тем, как начать писать в неё файл. Права доступа мы, как и
    // остальные метаданные, не отслеживаем (см. chmod ниже) - каталог всегда 0755.
    @Override
    public int mkdir(String path, int mode) {
        log.debug("MKDIR path={}", path);
        try {
            Files.createDirectory(resolve(path));
            return 0;
        } catch (FileAlreadyExistsException e) {
            return -errno.eexist();
        } catch (IOException e) {
            log.warn("MKDIR path={} error={}", path, e.toString());
            return -errno.eio();
        }
    }

    // rmdir(2): удаление (обязательно уже пустой) папки сессии - симметрично mkdir().
    @Override
    public int rmdir(String path) {
        log.debug("RMDIR path={}", path);
        try {
            Files.delete(resolve(path));
            return 0;
        } catch (IOException e) {
            log.warn("RMDIR path={} error={}", path, e.toString());
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
        // Всегда открываем READ+WRITE независимо от того, что запрашивал вызывающий: так
        // проще, и здесь ни на что не влияет строгое соблюдение O_RDONLY/O_WRONLY.
        Set<StandardOpenOption> options = EnumSet.of(StandardOpenOption.READ, StandardOpenOption.WRITE);
        if (create) {
            options.add(StandardOpenOption.CREATE);
        }
        // В libfuse 3 O_TRUNC приходит именно как один из этих флагов открытия, а не как
        // отдельный вызов truncate(), поэтому именно здесь мы его и проверяем.
        if (fi.getOpenFlags().contains(StandardOpenOption.TRUNCATE_EXISTING)) {
            options.add(StandardOpenOption.TRUNCATE_EXISTING);
        }
        // Номер выдаём сами; ядро вернёт нам ровно его в каждом следующем вызове для
        // этого открытого файла, и по нему же каждый экшен найдёт своё состояние.
        long fh = nextFh++;
        try {
            for (Action action : actions) {
                action.open(path, fh, options);
            }
        } catch (Exception e) {
            log.warn("OPEN path={} error={}", path, e.toString());
            return -errno.eio();
        }
        fi.setFh(fh);
        handles.add(fh);
        return 0;
    }

    /**
     * read() - это ПОЗИЦИОННОЕ чтение: в отличие от обычного InputStream, FUSE всегда
     * сообщает нам точное место в файле (`offset`), потому что позицию в файле отслеживает
     * ядро, а не мы. Отдаёт данные первый экшен, который их хранит (см. Action.read).
     */
    @Override
    public int read(String path, ByteBuffer buf, long count, long offset, FileInfo fi) {
        if (!handles.contains(fi.getFh())) {
            // Ядро прислало номер file handle, который мы никогда не выдавали (или уже
            // освободили) - "bad file descriptor" - стандартная errno для такого случая.
            return -errno.ebadf();
        }
        try {
            for (Action action : actions) {
                int read = action.read(path, fi.getFh(), buf, count, offset);
                if (read >= 0) {
                    return read;
                }
            }
            return 0;
        } catch (Exception e) {
            log.warn("READ path={} error={}", path, e.toString());
            return -errno.eio();
        }
    }

    /**
     * write() тоже позиционный (см. read() выше). `buf` - это нативный буфер, который живёт
     * только на время вызова, поэтому первым делом копируем его в обычный byte[]: экшены
     * держат эти байты у себя и после возврата из метода.
     * <p>
     * Ошибка любого экшена (включая сетевую от S3) превращается в EIO для программы-писателя:
     * молча терять данные хуже, чем честно сказать, что запись не удалась.
     */
    @Override
    public int write(String path, ByteBuffer buf, long count, long offset, FileInfo fi) {
        if (!handles.contains(fi.getFh())) {
            return -errno.ebadf();
        }
        byte[] data = new byte[(int) count];
        buf.get(data);
        try {
            for (Action action : actions) {
                action.write(path, fi.getFh(), offset, data);
            }
            return (int) count;
        } catch (Exception e) {
            log.warn("WRITE path={} error={}", path, e.toString());
            return -errno.eio();
        }
    }

    /**
     * truncate() отвечает и за truncate(2), и за ftruncate(2) - изменение размера файла,
     * которое может и УВЕЛИЧИВАТЬ его. Дескриптора может не быть вовсе (ядро пришло по
     * пути) - тогда экшены получают fh = -1.
     */
    @Override
    public int truncate(String path, long size, FileInfo fi) {
        log.debug("TRUNCATE path={} size={}", path, size);
        long fh = fi == null ? -1 : fi.getFh();
        try {
            for (Action action : actions) {
                action.truncate(path, fh, size);
            }
            return 0;
        } catch (Exception e) {
            log.warn("TRUNCATE path={} error={}", path, e.toString());
            return -errno.eio();
        }
    }

    /**
     * release() вызывается один раз, когда закрывается ПОСЛЕДНЯЯ ссылка на открытый файл
     * (close(2)). Для экшенов это сигнал дописать хвост и закрыться: StorageAction закрывает
     * канал, S3Action завершает multipart-загрузку.
     */
    @Override
    public int release(String path, FileInfo fi) {
        if (!handles.remove(fi.getFh())) {
            return -errno.ebadf();
        }
        try {
            for (Action action : actions) {
                action.release(path, fi.getFh());
            }
            return 0;
        } catch (Exception e) {
            log.warn("RELEASE path={} error={}", path, e.toString());
            return -errno.eio();
        }
    }

    /**
     * fsync(2)/fdatasync(2): вызывающему нужно, чтобы записи надёжно попали на диск, прежде
     * чем продолжать. Ненулевой `datasync` означает семантику fdatasync (только содержимое
     * файла, без метаданных вроде времени изменения).
     */
    @Override
    public int fsync(String path, int datasync, FileInfo fi) {
        log.debug("FSYNC path={}", path);
        if (!handles.contains(fi.getFh())) {
            return -errno.ebadf();
        }
        try {
            for (Action action : actions) {
                action.fsync(path, fi.getFh(), datasync == 0);
            }
            return 0;
        } catch (Exception e) {
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
     * Мы просто передаём настоящие цифры того диска, на котором лежит `storage`, блоками по
     * фиксированным 4096 байт.
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
}
