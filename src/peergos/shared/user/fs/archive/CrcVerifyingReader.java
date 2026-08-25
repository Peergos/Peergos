package peergos.shared.user.fs.archive;

import peergos.shared.user.fs.*;

import java.util.concurrent.*;
import java.util.zip.CRC32;

/** Checks an entry's CRC as its bytes stream past, and fails the final read if it doesn't match.
 *
 *  A seek away from the sequential path means we no longer see every byte, so verification is
 *  abandoned until the next reset rather than reporting a mismatch we can't justify.
 */
class CrcVerifyingReader implements AsyncReader {

    private final AsyncReader source;
    private final long size, expected;
    private final CRC32 crc = new CRC32();
    private long position = 0;
    private boolean verifying = true;

    public CrcVerifyingReader(AsyncReader source, long size, long expected) {
        this.source = source;
        this.size = size;
        this.expected = expected;
    }

    @Override
    public CompletableFuture<Integer> readIntoArray(byte[] res, int offset, int length) {
        long readFrom = position;
        return source.readIntoArray(res, offset, length)
                .thenApply(read -> {
                    position = readFrom + read;
                    if (verifying && read > 0) {
                        crc.update(res, offset, read);
                        if (position == size && crc.getValue() != expected)
                            throw new IllegalStateException("Corrupted zip entry: CRC mismatch");
                    }
                    return read;
                });
    }

    @Override
    public CompletableFuture<AsyncReader> seekJS(int high32, int low32) {
        long offset = (low32 & 0xFFFFFFFFL) | (((long) high32) << 32);
        return source.seek(offset)
                .thenApply(x -> {
                    verifying = offset == 0 && position == 0;
                    position = offset;
                    return this;
                });
    }

    @Override
    public CompletableFuture<AsyncReader> reset() {
        return source.reset()
                .thenApply(x -> {
                    crc.reset();
                    position = 0;
                    verifying = true;
                    return this;
                });
    }

    @Override
    public void close() {
        source.close();
    }
}
