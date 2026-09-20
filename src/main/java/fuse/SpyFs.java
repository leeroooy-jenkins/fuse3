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

// FUSE ("Filesystem in Userspace") lets an ordinary process implement a filesystem:
// the Linux kernel forwards every syscall a program makes against the mounted directory
// (open, read, write, readdir, ...) to this process as a method call, and whatever this
// class returns becomes the syscall's result. So SpyFs doesn't store any file data itself -
// it just mirrors reads/writes onto real files under `storage` and logs what it sees.
// jfuse is the Java binding: it turns kernel calls into calls on the FuseOperations methods
// below, using the libfuse C library under the hood.
@Slf4j
@RequiredArgsConstructor
public class SpyFs implements FuseOperations {

    // Once a file handle has this many consecutive bytes buffered, we log it as a "part" -
    // a stand-in for an S3 multipart upload part, which also has a minimum size.
    private static final int PART_MIN = 5 * 1024 * 1024;

    private final Errno errno;
    private final Path storage;

    // FUSE identifies an open file by an opaque "file handle" number that *we* choose in
    // open()/create() and the kernel then echoes back on every later call (read, write,
    // release, ...) via fi.getFh(). This map is how we find our per-open state again.
    private final Map<Long, Handle> handles = new HashMap<>();
    private long nextFh = 1;

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: SpyFs <mountpoint> <storage> [libfuse options]");
            System.exit(1);
        }
        Path mountPoint = Path.of(args[0]);
        Path storage = Path.of(args[1]);

        // builder.errno() gives us the platform's actual errno.h constants (EIO, ENOENT, ...),
        // since their numeric values differ between Linux/macOS/Windows.
        var builder = Fuse.builder();
        var fuse = builder.build(new SpyFs(builder.errno(), storage));
        // If the JVM is killed (Ctrl+C, `docker stop`/SIGTERM), politely unmount first instead
        // of leaving a dangling mountpoint behind.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                fuse.close();
            } catch (Exception e) {
                log.warn("close failed: {}", e.toString());
            }
        }));

        // "-s" tells libfuse to run single-threaded: the kernel sends us one request at a
        // time, so read()/write()/etc. never run concurrently and need no locking. jfuse adds
        // "-f" (stay in foreground) and the mountpoint itself automatically.
        String[] flags = new String[args.length - 2 + 1];
        flags[0] = "-s";
        System.arraycopy(args, 2, flags, 1, args.length - 2);

        // mount() blocks until the filesystem is actually attached, then returns while the
        // FUSE event loop keeps running on a background thread. Without the join() below the
        // JVM would just exit immediately and tear the mount back down.
        fuse.mount("fuse", mountPoint, flags);
        Thread.currentThread().join();
    }

    @Override
    public Errno errno() {
        return errno;
    }

    // jfuse only wires up (registers with libfuse) the operations listed here; anything else
    // - subdirectories, symlinks, xattrs, permission checks, ... - is simply never called, and
    // the kernel gets the generic "not implemented" error (ENOSYS) on its own.
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

    // Every FUSE callback receives an absolute path *inside the mount* (e.g. "/report.bin"),
    // never a real filesystem path. We keep a flat mount, so mapping it onto the backing
    // storage directory is just stripping the leading "/".
    private Path resolve(String path) {
        return storage.resolve(path.substring(1));
    }

    // getattr is the FUSE equivalent of stat(2): the kernel calls it constantly (before open,
    // before read, for `ls -l`, ...) to learn whether a path exists and, if so, its type/size.
    // We fill in just enough of the `stat` struct for a flat read/write filesystem to work:
    // the entry type bit (directory vs. regular file) or'd with a fixed permission mode, a
    // link count, and - for files - the real size on disk.
    // jfuse itself probes "/jfuse_mount_probe" right after mounting to confirm the mount is
    // live; since no such file exists in storage, that probe naturally (and correctly) gets
    // ENOENT here, same as any other missing name.
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
            // A FUSE operation reports failure by returning the *negative* errno value
            // (e.g. -ENOENT), never by throwing - that's how libfuse expects errors back.
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

    // readdir answers `ls`/`readdir(3)` on a directory. Since this is the *only* directory in
    // the whole mount (the spec explicitly rules out subdirectories), the kernel only ever
    // asks us to list "/". Every entry is reported through filler.fill(name); the "." and
    // ".." entries are conventional and expected even though nothing else uses them here.
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

    // create() is what the kernel calls for open(O_CREAT) on a name that doesn't exist yet
    // (it makes the file *and* opens it in one step); open() is a plain open() on a file that
    // is already there. Both need to hand back a file handle, so they share openInternal().
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
        // We always open READ+WRITE regardless of what the caller asked for: it keeps this
        // method simple, and nothing here depends on enforcing O_RDONLY/O_WRONLY.
        Set<StandardOpenOption> opts = EnumSet.of(StandardOpenOption.READ, StandardOpenOption.WRITE);
        if (create) {
            opts.add(StandardOpenOption.CREATE);
        }
        // libfuse 3 delivers O_TRUNC as one of these open-time flags rather than as a separate
        // truncate() call, so this is the one place we need to look for it.
        if (fi.getOpenFlags().contains(StandardOpenOption.TRUNCATE_EXISTING)) {
            opts.add(StandardOpenOption.TRUNCATE_EXISTING);
        }
        try {
            FileChannel channel = FileChannel.open(file, opts);
            // Hand the kernel a file handle number of our own choosing; it will pass this
            // exact number back on every later call against this open file (see `handles`).
            long fh = nextFh++;
            fi.setFh(fh);
            handles.put(fh, new Handle(channel));
            return 0;
        } catch (IOException e) {
            log.warn("OPEN path={} error={}", path, e.toString());
            return -errno.eio();
        }
    }

    // read() is a *positional* read: unlike a plain InputStream, FUSE always tells us exactly
    // where in the file to read from (`offset`), because the kernel - not us - tracks each
    // process's file position. We fill `buf` with up to `count` bytes, stopping early only at
    // end-of-file, and report back how many bytes we actually managed to supply.
    @Override
    public int read(String path, ByteBuffer buf, long count, long offset, FileInfo fi) {
        Handle handle = handles.get(fi.getFh());
        if (handle == null) {
            // The kernel gave us a file handle number we never issued (or already released) -
            // "bad file descriptor" is the standard errno for that.
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

    // write() is likewise positional (see read() above). `buf` is a native buffer that only
    // stays valid for the duration of this call, so the first thing we do is copy it into a
    // plain byte[] we can keep around (in Handle.part) after returning.
    @Override
    public int write(String path, ByteBuffer buf, long count, long offset, FileInfo fi) {
        Handle handle = handles.get(fi.getFh());
        if (handle == null) {
            return -errno.ebadf();
        }
        byte[] data = new byte[(int) count];
        buf.get(data);

        // The backing file on disk always gets the full write, regardless of what the
        // part-tracking below decides to do - correctness of the mirrored file never depends
        // on the logging logic.
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

    // This is the actual "spying": we don't care what the bytes mean, only whether a program
    // is writing its file out in order, front to back, in >= PART_MIN chunks - which is what
    // an S3 multipart upload needs. Each open file handle tracks its own in-progress part in
    // Handle.part; `partStart` is where in the *file* that part began.
    private void trackPart(String path, Handle handle, long offset, byte[] data) {
        if (handle.partStart == -1) {
            // First write seen on this handle: whatever offset it lands at becomes our
            // starting point, since we have nothing earlier to compare it against.
            handle.partStart = offset;
        }
        // "Where the next byte of this part should land" if writes keep arriving in order.
        long expected = handle.partStart + handle.part.size();
        if (offset == expected) {
            // Exactly the next byte in sequence: fold it into the buffered part.
            handle.part.writeBytes(data);
            handle.writes++;
            if (handle.part.size() >= PART_MIN) {
                // Buffered enough for one S3-style part: log it and start the next one where
                // this one left off.
                logPart(path, handle);
                handle.partStart += handle.part.size();
                handle.part.reset();
                handle.partNumber++;
                handle.writes = 0;
            }
        } else {
            // Out of order: either a rewrite of bytes we already logged (offset < expected,
            // harmless - nothing to lose) or a jump ahead that leaves a gap (offset+size >
            // expected). Only the latter forces us to give up on the in-progress part, since
            // it can now never be completed sequentially.
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
        // MD5 doubles as what S3 would hand back as a part's ETag, so this log line is enough
        // to later verify the "uploaded" range was never silently overwritten afterwards.
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

    // truncate() answers both truncate(2) and ftruncate(2) - resizing a file, which can also
    // *grow* it (the grown region reads back as zero bytes). FileChannel can only shrink, so
    // we go through RandomAccessFile.setLength() instead, which handles both directions.
    @Override
    public int truncate(String path, long size, FileInfo fi) {
        log.debug("TRUNCATE path={} size={}", path, size);
        try (RandomAccessFile raf = new RandomAccessFile(resolve(path).toFile(), "rw")) {
            raf.setLength(size);
        } catch (IOException e) {
            log.warn("TRUNCATE path={} error={}", path, e.toString());
            return -errno.eio();
        }
        // A resize invalidates whatever part we were mid-way through buffering for this
        // handle - the file layout just changed under it - so drop it and restart part
        // tracking from the new end of file, same as a NONSEQ gap would.
        Handle handle = fi == null ? null : handles.get(fi.getFh());
        if (handle != null && handle.part.size() > 0) {
            log.warn("TRUNCATE path={} size={} dropped={}", path, size, handle.part.size());
            handle.part.reset();
            handle.writes = 0;
            handle.partStart = size;
        }
        return 0;
    }

    // release() is called once when the *last* reference to an open file is closed
    // (close(2)); this is where we give back the file handle number and the FileChannel.
    // Any part bytes still buffered at this point (`tail`) are simply too small to have ever
    // reached PART_MIN and are dropped without comment - only DEBUG shows this happened.
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

    // fsync(2)/fdatasync(2): the caller wants its writes durably on disk before continuing.
    // `datasync` nonzero means fdatasync semantics (file contents only, not metadata like
    // timestamps), which FileChannel.force(false) matches.
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

    // flush() fires on every close(2) (even when other file descriptors keep the file open),
    // separately from release(). chmod/chown/utimens change permissions/ownership/timestamps.
    // None of that matters for a disposable spy filesystem, so we just report success without
    // doing anything - the kernel is satisfied as long as we return 0.
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

    // unlink(2): delete a name. rename(2): move/overwrite a name. `flags` on rename can
    // request Linux's newer atomic-swap/no-replace semantics (renameat2); we don't support
    // those, so any nonzero flags value is rejected up front rather than silently ignored.
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

    // statfs(2) is what backs `df`: total/free space for the filesystem. We just relay the
    // real numbers from whatever disk `storage` lives on, in fixed 4096-byte blocks.
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

    // Per-open-file state, keyed by file handle in `handles`. It lives only as long as the
    // file stays open under that one handle - reopening the same path starts a fresh Handle
    // and therefore fresh part tracking, since a new file descriptor means a new write
    // pattern to watch.
    private static final class Handle {
        private final FileChannel channel;
        // Bytes accumulated for the part currently in progress, not yet flushed to the log.
        private final ByteArrayOutputStream part = new ByteArrayOutputStream();
        // File offset where the in-progress part began; -1 means "no write seen yet".
        private long partStart = -1;
        // 1-based index of the in-progress part, purely for the log line (n=...).
        private int partNumber = 1;
        // How many write() calls contributed to the in-progress part.
        private int writes = 0;

        private Handle(FileChannel channel) {
            this.channel = channel;
        }
    }
}
