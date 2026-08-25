package peergos.shared.user.fs.archive;

import peergos.shared.user.fs.*;
import peergos.shared.util.*;

import java.util.concurrent.*;
import java.util.function.*;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/** A reader over the raw deflate compression of another reader, which also tracks the CRC of the
 *  uncompressed bytes it consumes.
 *
 *  Unlike inflating, deflating is available to shared code: the GWT emulation of Deflater is a real
 *  implementation rather than a stub, and it is synchronous, so only reading the source is async.
 */
class DeflatingReader implements AsyncReader {

    private static final int INPUT_BUFFER = 16 * 1024;

    private final Supplier<CompletableFuture<AsyncReader>> source;
    private final long sourceLength;
    private final Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
    private final CRC32 crc = new CRC32();
    private final byte[] input = new byte[INPUT_BUFFER];
    private AsyncReader reader = null;
    private long sourceRead = 0;

    public DeflatingReader(Supplier<CompletableFuture<AsyncReader>> source, long sourceLength) {
        this.source = source;
        this.sourceLength = sourceLength;
    }

    /** The CRC of the uncompressed bytes, valid once they have all been read.
     */
    public long crc() {
        return crc.getValue();
    }

    @Override
    public CompletableFuture<Integer> readIntoArray(byte[] res, int offset, int length) {
        return fill(res, offset, length, 0);
    }

    private CompletableFuture<Integer> fill(byte[] res, int offset, int length, int alreadyRead) {
        if (alreadyRead == length || deflater.finished())
            return Futures.of(alreadyRead);
        if (reader == null)
            return source.get().thenCompose(opened -> {
                reader = opened;
                return fill(res, offset, length, alreadyRead);
            });
        if (deflater.needsInput() && sourceRead < sourceLength) {
            int toRead = (int) Math.min(INPUT_BUFFER, sourceLength - sourceRead);
            return reader.readIntoArray(input, 0, toRead)
                    .thenCompose(read -> {
                        if (read <= 0)
                            throw new IllegalStateException("Unexpected end of the data being added to an archive");
                        crc.update(input, 0, read);
                        sourceRead += read;
                        deflater.setInput(input, 0, read);
                        if (sourceRead == sourceLength)
                            deflater.finish();
                        return fill(res, offset, length, alreadyRead);
                    });
        }
        if (deflater.needsInput() && sourceRead == sourceLength)
            deflater.finish();
        int written = deflater.deflate(res, offset + alreadyRead, length - alreadyRead);
        return fill(res, offset, length, alreadyRead + written);
    }

    @Override
    public CompletableFuture<AsyncReader> seekJS(int high32, int low32) {
        long offset = (low32 & 0xFFFFFFFFL) | (((long) high32) << 32);
        if (offset != 0)
            throw new IllegalStateException("Deflated data can only be read from the start");
        return reset();
    }

    @Override
    public CompletableFuture<AsyncReader> reset() {
        deflater.reset();
        crc.reset();
        sourceRead = 0;
        if (reader == null)
            return Futures.of(this);
        return reader.reset().thenApply(x -> this);
    }

    @Override
    public void close() {
        deflater.end();
        if (reader != null)
            reader.close();
    }
}
