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
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
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
 * ЗАДАЧА. Есть внешняя программа ("программа-писатель"), которая пишет поток в файл с именем
 * {@code screen} (см. TRACKED_FILENAME) внутри своего выходного каталога. Прежде чем реально
 * интегрировать её с S3, нужно выяснить, годится ли её паттерн записи под multipart-загрузку:
 * пишет ли она файл последовательно, от начала к концу, кусками не меньше 5 МиБ (см. PART_MIN).
 * SpyFs монтируется поверх её выходного каталога, прозрачно зеркалирует все операции в
 * настоящий каталог `storage` и пишет в лог, как именно шли записи.
 * ПРОДУКТ ЭТОЙ ПРОГРАММЫ - НЕ ФАЙЛЫ, А СТРОКИ ЛОГА. Запуск и окружение - см. README.md.
 *
 * <p>КАК ЭТО РАБОТАЕТ. FUSE ("Filesystem in Userspace") позволяет обычному процессу
 * реализовать файловую систему: ядро Linux перенаправляет каждый системный вызов программы
 * к смонтированному каталогу (open, read, write, readdir, ...) этому процессу в виде вызова
 * метода, а то, что вернёт этот класс, становится результатом системного вызова. Поэтому
 * SpyFs сам данные не хранит - он лишь зеркалирует чтения/записи в настоящие файлы под
 * `storage` и логирует, что видит. jfuse - это Java-обвязка: она превращает вызовы ядра в
 * вызовы методов FuseOperations ниже, используя под капотом C-библиотеку libfuse.
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
@RequiredArgsConstructor
public class SpyFs implements FuseOperations {

    /**
     * Как только у дескриптора накопилось столько последовательных байт, мы логируем это как
     * "часть" (part) - прообраз части multipart-загрузки в S3, у которой тоже есть минимальный
     * размер.
     */
    private static final int PART_MIN = 5 * 1024 * 1024;

    /**
     * Программа-писатель создаёт под каждую сессию отдельную папку (с именем-UUID) и пишет
     * в ней файл с этим именем. Логируем части только для него - остальные файлы (если
     * появятся) просто зеркалируются в хранилище молча, без отслеживания частей.
     */
    private static final String TRACKED_FILENAME = "screen";

    /**
     * Фабрика платформенных констант из errno.h (ENOENT, EIO, EBADF, ...), которую даёт jfuse.
     * Числовые значения этих констант различаются между Linux/macOS/Windows, поэтому мы их не
     * хардкодим, а спрашиваем у библиотеки. Приходит извне - см. main().
     */
    private final Errno errno;

    /**
     * НАСТОЯЩИЙ каталог на настоящем диске, в который мы зеркалируем всё, что пишут в нашу
     * файловую систему. Не путать с точкой монтирования: в вызовы FUSE приходят пути ВНУТРИ
     * точки монтирования, а resolve() переводит их в пути под `storage`.
     */
    private final Path storage;

    /**
     * FUSE опознаёт открытый файл по непрозрачному номеру "file handle", который МЫ САМИ
     * выбираем в open()/create(), а ядро затем возвращает нам же в каждом следующем вызове
     * (read, write, release, ...) через fi.getFh(). По этой карте мы находим своё состояние
     * для конкретного открытия файла; само состояние - в классе Handle в конце файла.
     *
     * <p>Ни карта, ни счётчик ниже не синхронизированы, и это намеренно: монтируемся с "-s"
     * (см. main()), поэтому libfuse отдаёт нам запросы строго по одному и все методы этого
     * класса выполняются в одном потоке. Уберёте "-s" - сюда понадобятся ConcurrentHashMap и
     * AtomicLong.
     */
    private final Map<Long, Handle> handles = new HashMap<>();

    /**
     * Откуда берём следующий номер file handle. Начинаем с 1, а не с 0, чтобы ноль оставался
     * заведомо невыданным значением: незаполненный fi.getFh() тогда не совпадёт случайно с
     * настоящим дескриптором и честно упрётся в EBADF.
     */
    private long nextFh = 1;

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

    /**
     * Через этот метод jfuse спрашивает, какой набор констант errno использовать: они нужны
     * самой библиотеке, чтобы отвечать ядру за нас - например, вернуть ENOSYS на операцию,
     * которой нет в supportedOperations().
     */
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
     *
     * <p>Про path traversal: выбраться из хранилища путём вида "/../../etc/passwd" нельзя -
     * "." и ".." разбирает само ядро, ещё на своей стороне, и до FUSE-вызова доходит уже
     * нормализованный путь. Сюда попадают только имена, реально лежащие внутри точки
     * монтирования.
     */
    private Path resolve(String path) {
        return storage.resolve(path.substring(1));
    }

    /**
     * Имя файла (последний сегмент пути) - по нему решаем, логировать ли части записи.
     */
    private static String fileName(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
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
     *
     * <p>Времена (mtime/atime/ctime) намеренно не заполняем: задаче они не нужны, поэтому
     * `ls -l` покажет для наших файлов начало эпохи. Понадобится реалистичный вывод - брать
     * их из Files.readAttributes() и класть в stat.
     *
     * <p>Параметр `fi` не используем: он заполнен, только если getattr пришёл по уже открытому
     * дескриптору (fstat(2)), а нам в любом случае хватает пути - ответ от этого не меняется.
     */
    @Override
    public int getattr(String path, Stat stat, FileInfo fi) {
        log.trace("GETATTR path={}", path);
        if ("/".equals(path)) {
            // Корень отвечаем, не заглядывая на диск: он обязан быть виден всегда, даже если
            // каталог-хранилище ещё не создан, - иначе монтирование просто не состоится.
            stat.setMode(Stat.S_IFDIR | 0755);
            // Счётчик жёстких ссылок, unix-соглашение: у каталога их минимум 2 (запись о нём
            // в родителе плюс "." внутри него самого), у обычного файла - 1. Реально
            // подкаталоги мы не считаем: это число `ls -l` только печатает в своей колонке.
            stat.setNLink((short) 2);
            return 0;
        }
        Path node = resolve(path);
        if (Files.isDirectory(node)) {
            stat.setMode(Stat.S_IFDIR | 0755);
            stat.setNLink((short) 2); // см. про счётчик ссылок в ветке с корнем выше
            return 0;
        }
        if (!Files.isRegularFile(node)) {
            // Операция FUSE сообщает об ошибке, возвращая ОТРИЦАТЕЛЬНОЕ значение errno
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
     *
     * <p>Параметры `offset` и `flags` игнорируем: offset нужен для постраничной отдачи очень
     * больших каталогов (ядро может попросить продолжить с середины), а мы всегда отдаём весь
     * каталог за один вызов. По той же причине не проверяем результат filler.fill() - он
     * сообщил бы, что буфер ядра кончился и пора остановиться.
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
        } catch (NoSuchFileException e) {
            // Ожидаемые ошибки переводим в точные errno: иначе пользователь вместо привычных
            // "No such file or directory" и "Directory not empty" увидел бы от `rmdir`
            // невнятное "Input/output error" (так работает ветка с EIO ниже).
            return -errno.enoent();
        } catch (DirectoryNotEmptyException e) {
            return -errno.enotempty();
        } catch (IOException e) {
            log.warn("RMDIR path={} error={}", path, e.toString());
            return -errno.eio();
        }
    }

    // create() - это то, что ядро вызывает для open(O_CREAT) по имени, которого ещё нет
    // (файл создаётся И открывается за один шаг); open() - обычный open() уже существующего
    // файла. Обоим нужно вернуть file handle, поэтому они используют общий openInternal().
    // Параметр `mode` (права создаваемого файла) игнорируем по той же причине, что и в
    // mkdir(): метаданные мы не отслеживаем, файл в хранилище получает права по umask.
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
        // Допущение: каталог-хранилище всегда доступен на запись. Если это не так, open
        // упадёт с EIO даже на чтение - случай сознательно не разбираем.
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
     * достижении конца файла, и сообщаем в ответ, сколько байт реально удалось отдать
     * (недостающее до `count` ядро само добьёт нулями - так и должно быть на конце файла).
     *
     * <p>Здесь и в write() нет log.trace, в отличие от остальных операций: это горячий путь,
     * лог на нём заметно замедлил бы саму измеряемую программу. Жизненный цикл файлов и так
     * виден по create/open/truncate/release.
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
                // r < 0 - конец файла; r == 0 - в `buf` больше нет места (jfuse обычно даёт
                // буфер ровно на `count` байт, но API этого не обещает). Выходим в обоих
                // случаях: иначе `total` перестал бы расти и цикл стал бы вечным, а с "-s"
                // это подвесило бы всю файловую систему, а не один вызов.
                if (r <= 0) {
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
        // `count` объявлен как long, но ядро никогда не присылает за один вызов больше
        // max_write (по умолчанию 128 КиБ), поэтому сужение до int здесь безопасно.
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

        // Отслеживаем части только для файла с отслеживаемым именем (TRACKED_FILENAME) -
        // в какой бы папке сессии он ни лежал. Остальные файлы просто зеркалируются выше,
        // без единой строки в логе.
        if (TRACKED_FILENAME.equals(fileName(path))) {
            trackPart(path, handle, offset, data);
        }
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
                dropped = handle.part.size();
                handle.part.reset();
                handle.writes = 0;
                // Байты ЭТОЙ записи в часть НЕ добавляем: именно она порвала
                // последовательность, поэтому выбрасываем и накопленное, и её саму. Следующую
                // часть начинаем с `end` - с первого байта, про который мы снова сможем
                // сказать, что он идёт следующим по порядку. `offset` здесь был бы неверен:
                // он указывает внутрь диапазона, который мы уже перестали отслеживать.
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
        // Выбран не из соображений криптостойкости (для неё MD5 давно не годится), а потому
        // что S3 считает ETag части именно как MD5: нам нужно то же самое число, чтобы его
        // было с чем сверять.
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
        // `fi` заполнен, только когда обрезание пришло как ftruncate(2), то есть по уже
        // открытому дескриптору: тогда у нас есть его Handle. У truncate(2) по пути
        // (`truncate`, `> file`) открытого дескриптора нет, fi == null, и сбрасывать нечего.
        // Оговорка: если тот же файл открыт ДРУГИМ дескриптором, его накопленная часть
        // останется протухшей - этот случай мы не покрываем.
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

    /**
     * flush() срабатывает на каждый close(2) (даже если файл ещё держат открытым другие
     * дескрипторы) отдельно от release(). Сбрасывать нам тут нечего: write() уже отдал байты
     * в FileChannel, а на диск их допишет ядро; настоящий sync делает fsync() выше. Ядру
     * достаточно получить 0.
     */
    @Override
    public int flush(String path, FileInfo fi) {
        return 0;
    }

    /**
     * chmod(2): смена прав доступа. Метаданные мы не отслеживаем - права в getattr() жёстко
     * зашиты (0755/0644), менять нечего. Рапортуем об успехе, чтобы вызывающий не падал.
     */
    @Override
    public int chmod(String path, int mode, FileInfo fi) {
        return 0;
    }

    /**
     * chown(2): смена владельца и группы. Не отслеживаем, как и права выше, - возвращаем успех.
     */
    @Override
    public int chown(String path, int uid, int gid, FileInfo fi) {
        return 0;
    }

    /**
     * utimens(2): смена времён доступа и изменения. getattr() времена не отдаёт вовсе, так что
     * хранить их негде - возвращаем успех, чтобы `touch` и копирующие утилиты не падали.
     */
    @Override
    public int utimens(String path, TimeSpec atime, TimeSpec mtime, FileInfo fi) {
        return 0;
    }

    /**
     * unlink(2): удалить имя. rename(2): переместить/перезаписать имя. `flags` в rename могут
     * запрашивать более новую семантику Linux - атомарный обмен или запрет замены
     * (renameat2); мы её не поддерживаем, поэтому любое ненулевое значение flags сразу
     * отклоняется, а не молча игнорируется.
     */
    @Override
    public int unlink(String path) {
        log.debug("UNLINK path={}", path);
        try {
            Files.delete(resolve(path));
            return 0;
        } catch (NoSuchFileException e) {
            // Как и в rmdir(): без этой ветки `rm` на несуществующем имени сообщил бы
            // "Input/output error" вместо привычного "No such file or directory".
            return -errno.enoent();
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
        } catch (NoSuchFileException e) {
            return -errno.enoent();
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
            // statvfs отчитывается в блоках, а FileStore отдаёт байты, поэтому делим на
            // фиксированный размер блока. 4096 - обычный размер блока ext4; конкретное
            // значение не важно, лишь бы в него делили и его же сообщали ядру.
            long bsize = 4096;
            statvfs.setBsize(bsize);
            statvfs.setFrsize(bsize);
            statvfs.setBlocks(store.getTotalSpace() / bsize);
            statvfs.setBfree(store.getUnallocatedSpace() / bsize);
            statvfs.setBavail(store.getUsableSpace() / bsize);
            // 255 - предел длины имени файла в ext4 и большинстве Linux-ФС; FileStore его не
            // отдаёт, поэтому подставляем константой.
            statvfs.setNameMax(255);
            // files/ffree (счётчики инодов, колонки `df -i`) не заполняем: своей таблицы
            // инодов у нас нет, считать нечего - `df -i` покажет по нашей ФС нули.
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
         * Сквозной в пределах ОДНОГО открытия файла: повторный open начнёт нумерацию заново.
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
