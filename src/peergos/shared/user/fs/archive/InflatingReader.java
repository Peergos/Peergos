package peergos.shared.user.fs.archive;

import peergos.shared.user.fs.*;
import peergos.shared.util.*;

import java.util.concurrent.*;

/** A reader over the decompressed bytes of a raw deflate stream.
 *
 *  Deflate has no index, so seeking backwards restarts the stream and seeking forwards discards.
 *  Neither ever allocates more than the fixed buffers here, whatever the size of the entry.
 */
class InflatingReader implements AsyncReader {

    private static final int INPUT_BUFFER = 16 * 1024;
    private static final int SKIP_BUFFER = 16 * 1024;

    private final RegionReader compressed;
    private final long size;
    private final Inflate.Session session;
    private final byte[] input = new byte[INPUT_BUFFER];
    private long position = 0;

    public InflatingReader(RegionReader compressed, long size) {
        this.compressed = compressed;
        this.size = size;
        this.session = Inflate.start();
    }

    @Override
    public CompletableFuture<Integer> readIntoArray(byte[] res, int offset, int length) {
        int toRead = (int) Math.min(length, size - position);
        if (toRead == 0)
            return Futures.of(0);
        if (session.needsInput()) {
            if (session.finished())
                return Futures.of(0);
            return compressed.readIntoArray(input, 0, INPUT_BUFFER)
                    .thenCompose(read -> {
                        if (read <= 0)
                            throw new IllegalStateException("Truncated deflate stream in zip entry");
                        session.setInput(input, 0, read);
                        return readIntoArray(res, offset, length);
                    });
        }
        return session.inflate(res, offset, toRead)
                .thenCompose(written -> {
                    if (written == 0)
                        return session.finished() ? Futures.of(0) : readIntoArray(res, offset, length);
                    position += written;
                    return Futures.of(written);
                });
    }

    @Override
    public CompletableFuture<AsyncReader> seekJS(int high32, int low32) {
        long offset = (low32 & 0xFFFFFFFFL) | (((long) high32) << 32);
        if (offset < 0 || offset > size)
            throw new IllegalStateException("Seek outside of zip entry: " + offset);
        if (offset < position)
            return reset().thenCompose(x -> seek(offset));
        return skip(offset - position);
    }

    private CompletableFuture<AsyncReader> skip(long bytes) {
        if (bytes == 0)
            return Futures.of(this);
        byte[] scratch = new byte[(int) Math.min(SKIP_BUFFER, bytes)];
        return readIntoArray(scratch, 0, scratch.length)
                .thenCompose(read -> {
                    if (read <= 0)
                        throw new IllegalStateException("Truncated deflate stream in zip entry");
                    return skip(bytes - read);
                });
    }

    @Override
    public CompletableFuture<AsyncReader> reset() {
        session.reset();
        position = 0;
        return compressed.reset().thenApply(x -> this);
    }

    @Override
    public void close() {
        session.close();
        compressed.close();
    }
}
