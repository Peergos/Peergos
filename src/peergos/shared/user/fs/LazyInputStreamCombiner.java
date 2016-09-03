package peergos.shared.user.fs;

import peergos.shared.crypto.symmetric.*;
import peergos.shared.user.*;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
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

    public CompletableFuture<byte[]> getNextStream(int len) {
        if (this.next != null) {
            Location nextLocation = this.next;
            return context.getMetadata(nextLocation).thenCompose(meta -> {
                if (!meta.isPresent()) {
                    CompletableFuture<byte[]> err = new CompletableFuture<>();
                    err.completeExceptionally(new EOFException());
                    return err;
                }
                FileRetriever nextRet = meta.get().retriever();
                this.next = nextRet.getNext();
                return nextRet.getChunkInputStream(context, dataKey, 0, len, nextLocation, monitor)
                        .thenApply(x -> x.get().chunk.data());
            });
        }
        CompletableFuture<byte[]> err = new CompletableFuture<>();
        err.completeExceptionally(new EOFException());
        return err;
    }

    public int bytesReady() {
        return this.current.length - this.index;
    }

    private static final class EndOfChunkException extends RuntimeException {};

    public byte readByte() throws IOException {
        try {
            return this.current[this.index++];
        } catch (Exception e) {}
        globalIndex += Chunk.MAX_SIZE;
        if (globalIndex >= totalLength)
            throw new EOFException();
        throw new EndOfChunkException();
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

    public CompletableFuture<byte[]> readArray(int len, byte[] res, int offset) {
        int available = bytesReady();
        int toRead = Math.min(available, len);
        for (int i=0; i < toRead; i++) {
            try {
                res[offset + i] = readByte();
            } catch (EndOfChunkException e) {
                int remainingToRead = totalLength - globalIndex > Chunk.MAX_SIZE ? Chunk.MAX_SIZE : (int) (totalLength - globalIndex);
                byte[] result = res;
                int offsetAdd = i;
                return getNextStream(remainingToRead).thenCompose(nextChunk -> {
                    current = nextChunk;
                    index = 0;
                    return readArray(remainingToRead, result, offset + offsetAdd);
                });
            } catch (IOException e) {
                CompletableFuture<byte[]> err = new CompletableFuture<>();
                err.completeExceptionally(e);
                return err;
            }
        }
        if (available >= len)
            return CompletableFuture.completedFuture(res);
        int nextSize = len - toRead > Chunk.MAX_SIZE ? Chunk.MAX_SIZE : (len-toRead) % Chunk.MAX_SIZE;
        int newOffset = offset;
        return getNextStream(nextSize).thenCompose(nextCurrent -> {
            this.current = nextCurrent;
            index = 0;
            byte[] result = new byte[len];

            return readArray(len - toRead, result, newOffset + toRead);
        });
    }
}
