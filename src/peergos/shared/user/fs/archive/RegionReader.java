package peergos.shared.user.fs.archive;

import peergos.shared.user.fs.*;
import peergos.shared.util.*;

import java.util.concurrent.*;

/** A view of a byte range of another AsyncReader, which reads past the end of the range as an end
 *  of stream rather than continuing into the next entry.
 */
class RegionReader implements AsyncReader {

    private final AsyncReader source;
    private final long start, length;
    private long position;
    private boolean positioned = false;

    public RegionReader(AsyncReader source, long start, long length) {
        this.source = source;
        this.start = start;
        this.length = length;
    }

    public long remaining() {
        return length - position;
    }

    @Override
    public CompletableFuture<AsyncReader> seekJS(int high32, int low32) {
        long offset = (low32 & 0xFFFFFFFFL) | (((long) high32) << 32);
        if (offset < 0 || offset > length)
            throw new IllegalStateException("Seek outside of zip entry: " + offset);
        position = offset;
        return source.seek(start + offset)
                .thenApply(x -> {
                    positioned = true;
                    return this;
                });
    }

    @Override
    public CompletableFuture<Integer> readIntoArray(byte[] res, int offset, int length) {
        int toRead = (int) Math.min(length, remaining());
        if (toRead == 0)
            return Futures.of(0);
        if (! positioned)
            return seek(position).thenCompose(x -> readIntoArray(res, offset, length));
        return source.readIntoArray(res, offset, toRead)
                .thenApply(read -> {
                    position += read;
                    return read;
                });
    }

    @Override
    public CompletableFuture<AsyncReader> reset() {
        return seek(0);
    }

    @Override
    public void close() {
        source.close();
    }
}
