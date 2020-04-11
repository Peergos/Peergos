package peergos.shared.user.fs;

import peergos.shared.util.*;

import java.util.concurrent.*;

public class BufferedReader implements AsyncReader {

    private final AsyncReader source;
    private final byte[] buffer;
    private final int nChunks;
    // bufferStartOffset <= readOffset <= bufferEndOffset at all times
    private long readOffsetInFile, bufferStartInFile, bufferEndInFile;
    private int startInBuffer; // index in buffer corresponding to bufferStartInFile
    private volatile boolean closed = false;
    private CompletableFuture<Integer> currentRetrieval = Futures.of(0);

    public BufferedReader(AsyncReader source, int nChunksToBuffer) {
        this.source = source;
        this.buffer = new byte[nChunksToBuffer * Chunk.MAX_SIZE];
        this.nChunks = nChunksToBuffer;
        this.readOffsetInFile = 0;
        this.bufferStartInFile = 0;
        this.bufferEndInFile = 0;
        this.startInBuffer = 0;
        asyncBufferFill();
    }

    private void asyncBufferFill() {
        ForkJoinPool.commonPool().execute(this::bufferNextChunk);
    }

    private synchronized CompletableFuture<Integer> bufferNextChunk() {
        System.out.println("BufferNextChunk() " + toString());
        if (closed)
            return Futures.errored(new RuntimeException("Stream Closed!"));
        if (bufferEndInFile - bufferStartInFile >= buffer.length) {
            System.out.println("Buffer full!");
            return Futures.errored(new RuntimeException("Buffer already full!"));
        }
        System.out.println("Buffer lock: " + (currentRetrieval.isDone() ? "done" : "waiting"));
        if (! currentRetrieval.isDone())
            return currentRetrieval;
        CompletableFuture<Integer> lock = new CompletableFuture<>();
        this.currentRetrieval = lock;

        long initialBufferEndOffset = bufferEndInFile;
        int writeFromBufferOffset = (int) (initialBufferEndOffset - bufferStartInFile + startInBuffer) % buffer.length;
        int toCopy = Math.min(buffer.length - writeFromBufferOffset, Chunk.MAX_SIZE);
        System.out.println("Buffering from " + bufferEndInFile + " to array index " + writeFromBufferOffset);
        return source.readIntoArray(buffer, writeFromBufferOffset, toCopy)
                .thenApply(read -> {
                    this.bufferEndInFile = initialBufferEndOffset + read;
                    lock.complete(read);
                    if (buffered() < buffer.length)
                        asyncBufferFill();
                    return read;
                }).exceptionally(t -> {
                    lock.completeExceptionally(t);
                    return 0;
                });
    }

    /**
     *
     * @return Number of buffered bytes
     */
    private synchronized int buffered() {
        return (int) (bufferEndInFile - bufferStartInFile);
    }

    /**
     *
     * @return Number of buffered bytes available to read
     */
    private synchronized int available() {
        return (int) (bufferEndInFile - readOffsetInFile);
    }

    /**
     *
     * @return Number of buffered bytes that have already been read
     */
    private synchronized int read() {
        return (int) (readOffsetInFile - bufferStartInFile);
    }

    @Override
    public synchronized CompletableFuture<Integer> readIntoArray(byte[] res, int offset, int length) {
        System.out.println("Read "+length+" from buffer " + toString());
        int available = available();
        if (available >= length) {
            // we already have all the data buffered
            int readStartInBuffer = startInBuffer + (int) (readOffsetInFile - bufferStartInFile);
            int toCopy = Math.min(length, buffer.length - readStartInBuffer);
            System.arraycopy(buffer, readStartInBuffer, res, offset, toCopy);
            if (toCopy < length)
                System.arraycopy(buffer, 0, res, offset + toCopy, length - toCopy);

            readOffsetInFile += length;
            while (read() >= Chunk.MAX_SIZE) {
                bufferStartInFile += Chunk.MAX_SIZE;
                startInBuffer += Chunk.MAX_SIZE;
            }
            return Futures.of(length);
        }
        if (available > 0) {
            // drain the rest of the buffer
            int readStartInBuffer = startInBuffer + (int) (readOffsetInFile - bufferStartInFile);
            int toCopy = Math.min(available, buffer.length - readStartInBuffer);
            System.arraycopy(buffer, readStartInBuffer, res, offset, toCopy);
            if (toCopy < available)
                System.arraycopy(buffer, 0, res, offset + toCopy, available - toCopy);

            readOffsetInFile += toCopy;
            while (read() >= Chunk.MAX_SIZE) {
                bufferStartInFile += Chunk.MAX_SIZE;
                startInBuffer += Chunk.MAX_SIZE;
            }
        }
        return bufferNextChunk().thenCompose(x -> readIntoArray(res, offset, length)).exceptionally(Futures::logAndThrow);
    }

    @Override
    public CompletableFuture<AsyncReader> seekJS(int high32, int low32) {
        long seek = ((long) (high32) << 32) | (low32 & 0xFFFFFFFFL);
        return seek(seek);
    }

    @Override
    public CompletableFuture<AsyncReader> seek(long offset) {
        System.out.println("BufferedReader.seek " + offset);
        if (offset == readOffsetInFile)
            return Futures.of(this);
        return source.seek(offset)
                .thenApply(r -> this);
    }

    @Override
    public CompletableFuture<AsyncReader> reset() {
        System.out.println("BufferedReader.reset()");
        return source.reset()
                .thenApply(reset -> this);
    }

    @Override
    public void close() {
        System.out.println("BufferedReader.close()");
        this.closed = true;
    }

    @Override
    public String toString() {
        return "BufferedReader{" +
                "readOffsetInFile=" + readOffsetInFile +
                ", bufferStartInFile=" + bufferStartInFile +
                ", bufferEndInFile=" + bufferEndInFile +
                ", startInBuffer=" + startInBuffer +
                '}';
    }
}
