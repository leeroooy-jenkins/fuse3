package fuse;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.StandardOpenOption;
import java.util.Set;

/**
 * Одно действие над потоком данных открытого файла. SpyFs получает вызовы от ядра и
 * раздаёт их всем экшенам по очереди - в том порядке, в котором они перечислены в
 * конструкторе SpyFs. Так зеркалирование на диск, логирование частей и загрузка в S3
 * становятся независимыми друг от друга потребителями одного и того же потока записей.
 * <p>
 * Каждый экшен сам хранит своё состояние на открытый файл, ключом служит `fh` -
 * тот самый номер file handle, который SpyFs выдал ядру в open()/create().
 */
public interface Action {

    /**
     * Файл открыт (или создан). `options` - это то, с чем открылся бы FileChannel:
     * READ+WRITE, плюс CREATE и/или TRUNCATE_EXISTING, если их запросило ядро.
     */
    void open(String path, long fh, Set<StandardOpenOption> options) throws IOException;

    /**
     * Позиционная запись: `data` должны оказаться в файле начиная со смещения `offset`.
     * Записи не обязаны приходить по порядку - экшен сам решает, что с этим делать.
     */
    void write(String path, long fh, long offset, byte[] data) throws IOException;

    /** Размер файла изменён. `fh` равен -1, если ядро пришло без дескриптора. */
    void truncate(String path, long fh, long size) throws IOException;

    /** Последняя ссылка на открытый файл закрыта - самое время дописать хвост и закрыться. */
    void release(String path, long fh) throws IOException;

    /**
     * Позиционное чтение. Возвращает число прочитанных байт либо -1, если этот экшен
     * чтения не обслуживает (тогда SpyFs спросит следующий). Данные отдаёт тот экшен,
     * который реально хранит файл, - остальным читать нечего.
     */
    default int read(String path, long fh, ByteBuffer buf, long count, long offset) throws IOException {
        return -1;
    }

    /** Ядро просит гарантировать, что записанное лежит на диске. */
    default void fsync(String path, long fh, boolean metadata) throws IOException {
    }
}
