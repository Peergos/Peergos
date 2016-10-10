package peergos.shared.user.fs;

import peergos.shared.crypto.symmetric.*;
import peergos.shared.user.*;

import java.io.*;
import java.util.concurrent.*;
import java.util.function.*;

public class LazyInputStreamCombiner implements AsyncReader {
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

    @Override
    public CompletableFuture<Boolean> seek(long offset) {
        throw new IllegalStateException("Unimplemented!");
    }

    public int bytesReady() {
        return this.current.length - this.index;
    }

    public void close() {}

    public CompletableFuture<Boolean> reset() {
        index = 0;
        current = original;
        next = originalNext;
        return CompletableFuture.completedFuture(true);
    }

    /**
     *
     * @param res array to store data in
     * @param offset initial index to store data in res
     * @param length number of bytes to read
     * @return number of bytes read
     */
    public CompletableFuture<Integer> readIntoArray(byte[] res, int offset, int length) {
        int available = bytesReady();
        int toRead = Math.min(available, length);
        System.arraycopy(current, index, res, offset, toRead);
        globalIndex += toRead;
        if (available >= length) // we are done
            return CompletableFuture.completedFuture(length);
        if (globalIndex >= totalLength) {
            CompletableFuture<Integer> err=  new CompletableFuture<>();
            err.completeExceptionally(new EOFException());
            return err;
        }
        int remainingToRead = totalLength - globalIndex > Chunk.MAX_SIZE ? Chunk.MAX_SIZE : (int) (totalLength - globalIndex);
        return getNextStream(remainingToRead).thenCompose(nextChunk -> {
            current = nextChunk;
            index = 0;
            return readIntoArray(res, offset + toRead, length - toRead).thenApply(bytesRead -> bytesRead + toRead);
        });
    }
}
