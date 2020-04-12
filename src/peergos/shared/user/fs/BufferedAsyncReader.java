package peergos.shared.user.fs;

import peergos.shared.util.*;

import java.util.concurrent.*;

public class BufferedAsyncReader implements AsyncReader {

    private final AsyncReader source;
    private final long fileSize;
    private final byte[] buffer;
    // bufferStartOffset <= readOffset <= bufferEndOffset at all times
    private long readOffsetInFile, bufferStartInFile, bufferEndInFile;
    private int startInBuffer; // index in buffer corresponding to bufferStartInFile
    private volatile boolean closed = false;
    private final AsyncLock<Integer> lock = new AsyncLock<>(Futures.of(0));

    public BufferedAsyncReader(AsyncReader source, int nChunksToBuffer, long fileSize, long bufferStartInFile) {
        this.source = source;
        this.buffer = new byte[nChunksToBuffer * Chunk.MAX_SIZE];
        this.fileSize = fileSize;
        this.bufferStartInFile = bufferStartInFile;
        this.readOffsetInFile = bufferStartInFile;
        this.bufferEndInFile = bufferStartInFile;
        this.startInBuffer = 0;
    }

    public BufferedAsyncReader(AsyncReader source, int nChunksToBuffer, long fileSize) {
        this(source, nChunksToBuffer, fileSize, 0);
    }

    private void asyncBufferFill() {
        ForkJoinPool.commonPool().execute(() -> lock.runWithLock(x -> bufferNextChunk()));
    }

    private synchronized CompletableFuture<Integer> bufferNextChunk() {
        System.out.println("BufferNextChunk() " + toString());
        if (closed)
            return Futures.errored(new RuntimeException("Stream Closed!"));
        if (bufferEndInFile - bufferStartInFile >= buffer.length) {
            System.out.println("Buffer full!");
            return Futures.errored(new RuntimeException("Buffer already full!"));
        }

        long initialBufferEndOffset = bufferEndInFile;
        int writeFromBufferOffset = (int) (initialBufferEndOffset - bufferStartInFile + startInBuffer) % buffer.length;
        int toCopy = Math.min(buffer.length - writeFromBufferOffset, Chunk.MAX_SIZE);
        if (fileSize - bufferEndInFile < Chunk.MAX_SIZE)
            toCopy = (int) (fileSize - bufferEndInFile);
        if (toCopy == 0)
            return Futures.of(0);
        System.out.println("Buffering from " + bufferEndInFile + " to array index " + writeFromBufferOffset + " size " + toCopy);
        return source.readIntoArray(buffer, writeFromBufferOffset, toCopy)
                .thenApply(read -> {
                    this.bufferEndInFile = initialBufferEndOffset + read;
                    if (buffered() < buffer.length && buffered() < fileSize && ! closed)
                        asyncBufferFill();
                    return read;
                }).exceptionally(t -> {
                    t.printStackTrace();
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
            int readStartInBuffer = (int) (startInBuffer + readOffsetInFile - bufferStartInFile) % buffer.length;
            int toCopy = Math.min(length, buffer.length - readStartInBuffer);
            System.arraycopy(buffer, readStartInBuffer, res, offset, toCopy);
            if (toCopy < length)
                System.arraycopy(buffer, 0, res, offset + toCopy, length - toCopy);

            readOffsetInFile += length;
            while (read() >= Chunk.MAX_SIZE) {
                bufferStartInFile += Chunk.MAX_SIZE;
                startInBuffer += Chunk.MAX_SIZE;
            }
            System.out.println("Finished read from buffer of " + length);
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
            return lock.runWithLock(x -> bufferNextChunk())
                    .thenCompose(x -> readIntoArray(res, offset + toCopy, length - toCopy)
                            .thenApply(i -> length))
                    .exceptionally(Futures::logAndThrow);
        }
        return lock.runWithLock(x -> bufferNextChunk())
                .thenCompose(x -> readIntoArray(res, offset, length))
                .exceptionally(Futures::logAndThrow);
    }

    @Override
    public CompletableFuture<AsyncReader> seekJS(int high32, int low32) {
        long seek = ((long) (high32) << 32) | (low32 & 0xFFFFFFFFL);
        return seek(seek);
    }

    @Override
    public synchronized CompletableFuture<AsyncReader> seek(long offset) {
        System.out.println("BufferedReader.seek " + offset);
        if (offset == readOffsetInFile)
            return Futures.of(this);
        close();
        long aligned = offset - offset % Chunk.MAX_SIZE;
        return source.seek(aligned)
                .thenCompose(r -> {
                    BufferedAsyncReader res = new BufferedAsyncReader(r, buffer.length / Chunk.MAX_SIZE, fileSize, aligned);
                    // do a dummy read into our buffer to get to correct position
                    return res.readIntoArray(buffer, 0, (int) (offset - aligned))
                            .thenApply(n -> res);
                });
    }

    @Override
    public CompletableFuture<AsyncReader> reset() {
        System.out.println("BufferedReader.reset()");
        close();
        return source.reset()
                .thenApply(r -> new BufferedAsyncReader(r, buffer.length/Chunk.MAX_SIZE, fileSize));
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
