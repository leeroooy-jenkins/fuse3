package fuse;

import lombok.RequiredArgsConstructor;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Зеркалирует всё, что пишут в точку монтирования, в настоящие файлы каталога-хранилища.
 * Этот же экшен обслуживает чтения: он единственный, у кого данные реально есть.
 */
@RequiredArgsConstructor
public class StorageAction implements Action {

    private final Path storage;
    private final Map<Long, FileChannel> channels = new HashMap<>();

    @Override
    public void open(String path, long fh, Set<StandardOpenOption> options) throws IOException {
        channels.put(fh, FileChannel.open(storage.resolve(path.substring(1)), options));
    }

    @Override
    public void write(String path, long fh, long offset, byte[] data) throws IOException {
        FileChannel channel = channels.get(fh);
        ByteBuffer toWrite = ByteBuffer.wrap(data);
        long written = 0;
        while (toWrite.hasRemaining()) {
            written += channel.write(toWrite, offset + written);
        }
    }

    @Override
    public int read(String path, long fh, ByteBuffer buf, long count, long offset) throws IOException {
        FileChannel channel = channels.get(fh);
        long total = 0;
        while (total < count) {
            int r = channel.read(buf, offset + total);
            if (r < 0) {
                break;
            }
            total += r;
        }
        return (int) total;
    }

    /**
     * RandomAccessFile.setLength() умеет и удлинять файл, в отличие от FileChannel.truncate(),
     * а работать по пути (а не по дескриптору) нужно потому, что ядро может прийти без него.
     */
    @Override
    public void truncate(String path, long fh, long size) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(storage.resolve(path.substring(1)).toFile(), "rw")) {
            raf.setLength(size);
        }
    }

    @Override
    public void fsync(String path, long fh, boolean metadata) throws IOException {
        channels.get(fh).force(metadata);
    }

    @Override
    public void release(String path, long fh) throws IOException {
        FileChannel channel = channels.remove(fh);
        if (channel != null) {
            channel.close();
        }
    }
}
