package peergos.shared.user.fs;

import peergos.shared.crypto.symmetric.*;
import peergos.shared.user.*;

import java.io.*;
import java.util.*;
import java.util.function.*;

public class LazyInputStreamCombiner extends InputStream {
    private final UserContext context;
    private final SymmetricKey dataKey;
    private final Consumer<Long> monitor;
    private final long totalLength;
    private final byte[] original;
    private final Location originalNext;
    private long globalIndex = 0;
    private byte[] current;
    private int index;
    private Location next;

    public LazyInputStreamCombiner(FileRetriever stream, UserContext context, SymmetricKey dataKey, byte[] chunk, long totalLength, Consumer<Long> monitor) {
        this.context = context;
        this.dataKey = dataKey;
        this.current = chunk;
        this.index = 0;
        this.next = stream.getNext();
        this.totalLength = totalLength;
        this.monitor = monitor;
        this.original = chunk;
        this.originalNext = next;
    }

    public byte[] getNextStream(int len) throws IOException {
        if (this.next != null) {
            Location nextLocation = this.next;
            Optional<FileAccess> meta = context.getMetadata(nextLocation);
            if (!meta.isPresent())
                throw new EOFException();
            FileRetriever nextRet = meta.get().retriever();
            this.next = nextRet.getNext();
            return nextRet.getChunkInputStream(context, dataKey, 0, len, nextLocation, monitor).get().chunk.data();
        }
        throw new EOFException();
    }

    public int bytesReady() {
        return this.current.length - this.index;
    }

    private static final EOFException EOF = new EOFException();

    public byte readByte() throws IOException {
        try {
            return this.current[this.index++];
        } catch (Exception e) {}
        globalIndex += Chunk.MAX_SIZE;
        if (globalIndex >= totalLength)
            throw EOF;
        int toRead = totalLength - globalIndex > Chunk.MAX_SIZE ? Chunk.MAX_SIZE : (int) (totalLength - globalIndex);
        current = getNextStream(toRead);
        index = 0;
        return current[index++];
    }

    @Override
    public void reset() {
        index = 0;
        current = original;
        next = originalNext;
    }

    @Override
    public boolean markSupported() {
        return true;
    }

    @Override
    public int read() throws IOException {
        try {
            return readByte() & 0xff;
        } catch (EOFException eofe) {
            return -1;
        }
    }

    public byte[] readArray(int len, byte[] res, int offset) throws IOException {
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
        return readArray(len-toRead, res, offset + toRead);
    }
}
