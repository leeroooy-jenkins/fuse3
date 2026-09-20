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

@Slf4j
@RequiredArgsConstructor
public class SpyFs implements FuseOperations {

    private static final int PART_MIN = 5 * 1024 * 1024;

    private final Errno errno;
    private final Path storage;

    private final Map<Long, Handle> handles = new HashMap<>();
    private long nextFh = 1;

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: SpyFs <mountpoint> <storage> [libfuse options]");
            System.exit(1);
        }
        Path mountPoint = Path.of(args[0]);
        Path storage = Path.of(args[1]);

        var builder = Fuse.builder();
        var fuse = builder.build(new SpyFs(builder.errno(), storage));
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                fuse.close();
            } catch (Exception e) {
                log.warn("close failed: {}", e.toString());
            }
        }));

        String[] flags = new String[args.length - 2 + 1];
        flags[0] = "-s";
        System.arraycopy(args, 2, flags, 1, args.length - 2);

        fuse.mount("fuse", mountPoint, flags);
        Thread.currentThread().join();
    }

    @Override
    public Errno errno() {
        return errno;
    }

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

    private Path resolve(String path) {
        return storage.resolve(path.substring(1));
    }

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
        Set<StandardOpenOption> opts = EnumSet.of(StandardOpenOption.READ, StandardOpenOption.WRITE);
        if (create) {
            opts.add(StandardOpenOption.CREATE);
        }
        if (fi.getOpenFlags().contains(StandardOpenOption.TRUNCATE_EXISTING)) {
            opts.add(StandardOpenOption.TRUNCATE_EXISTING);
        }
        try {
            FileChannel channel = FileChannel.open(file, opts);
            long fh = nextFh++;
            fi.setFh(fh);
            handles.put(fh, new Handle(channel));
            return 0;
        } catch (IOException e) {
            log.warn("OPEN path={} error={}", path, e.toString());
            return -errno.eio();
        }
    }

    @Override
    public int read(String path, ByteBuffer buf, long count, long offset, FileInfo fi) {
        Handle handle = handles.get(fi.getFh());
        if (handle == null) {
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

    @Override
    public int write(String path, ByteBuffer buf, long count, long offset, FileInfo fi) {
        Handle handle = handles.get(fi.getFh());
        if (handle == null) {
            return -errno.ebadf();
        }
        byte[] data = new byte[(int) count];
        buf.get(data);

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

    private void trackPart(String path, Handle handle, long offset, byte[] data) {
        if (handle.partStart == -1) {
            handle.partStart = offset;
        }
        long expected = handle.partStart + handle.part.size();
        if (offset == expected) {
            handle.part.writeBytes(data);
            handle.writes++;
            if (handle.part.size() >= PART_MIN) {
                logPart(path, handle);
                handle.partStart += handle.part.size();
                handle.part.reset();
                handle.partNumber++;
                handle.writes = 0;
            }
        } else {
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

    @Override
    public int truncate(String path, long size, FileInfo fi) {
        log.debug("TRUNCATE path={} size={}", path, size);
        try (RandomAccessFile raf = new RandomAccessFile(resolve(path).toFile(), "rw")) {
            raf.setLength(size);
        } catch (IOException e) {
            log.warn("TRUNCATE path={} error={}", path, e.toString());
            return -errno.eio();
        }
        Handle handle = fi == null ? null : handles.get(fi.getFh());
        if (handle != null && handle.part.size() > 0) {
            log.warn("TRUNCATE path={} size={} dropped={}", path, size, handle.part.size());
            handle.part.reset();
            handle.writes = 0;
            handle.partStart = size;
        }
        return 0;
    }

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

    private static final class Handle {
        private final FileChannel channel;
        private final ByteArrayOutputStream part = new ByteArrayOutputStream();
        private long partStart = -1;
        private int partNumber = 1;
        private int writes = 0;

        private Handle(FileChannel channel) {
            this.channel = channel;
        }
    }
}
