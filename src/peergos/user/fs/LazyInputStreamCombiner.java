package peergos.user.fs;

import peergos.crypto.symmetric.*;
import peergos.user.*;

import java.io.*;
import java.util.function.*;

public class LazyInputStreamCombiner extends InputStream {
    private final UserContext context;
    private final SymmetricKey dataKey;
    private final Consumer<Long> monitor;
    private byte[] current;
    private int index;
    private Location next;

    public LazyInputStreamCombiner(FileRetriever stream, UserContext context, SymmetricKey dataKey, byte[] chunk, Consumer<Long> monitor) {
        this.context = context;
        this.dataKey = dataKey;
        this.current = chunk;
        this.index = 0;
        this.next = stream.getNext();
        this.monitor = monitor;
    }

    public byte[] getNextStream(int len) throws IOException {
        if (this.next != null) {
            FileAccess meta = context.getMetadata(this.next);
            FileRetriever nextRet = meta.retriever();
            next = nextRet.getNext();
            return nextRet.getChunkInputStream(context, dataKey, len, monitor);
        }
        throw new IllegalStateException("End Of File!");
    }

    public int bytesReady() {
        return this.current.length - this.index;
    }

    public byte readByte() throws IOException {
        try {
            return this.current[this.index++];
        } catch (Exception e) {}
        current = getNextStream(-1);
        index = 0;
        return current[index++];
    }

    @Override
    public int read() throws IOException {
        return readByte() & 0xff;
    }

    public byte[] read(int len, byte[] res, int offset) throws IOException {
        if (res == null) {
            res = new byte[len];
            offset = 0;
        }
        int available = bytesReady();
        int toRead = Math.min(available, len);
        for (int i=0; i < toRead; i++)
            res[offset + i] = readByte();
        if (available >= len)
            return res;
        int nextSize = len - toRead > Chunk.MAX_SIZE ? Chunk.MAX_SIZE : (len-toRead) % Chunk.MAX_SIZE;
        current = this.getNextStream(nextSize);
        index = 0;
        return read(len-toRead, res, offset + toRead);
    }
}
