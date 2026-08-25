package peergos.shared.user.fs.archive;

import peergos.shared.user.fs.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** A reader over a sequence of parts, each of a known length, opened only as it is reached.
 *
 *  This is how a new tail for an archive is assembled: entry headers and the rebuilt central
 *  directory are small enough to hold, while the entry data streams through from wherever it
 *  comes from.
 */
class ConcatReader implements AsyncReader {

    public static class Part {
        public final long length;
        public final Supplier<CompletableFuture<AsyncReader>> open;

        public Part(long length, Supplier<CompletableFuture<AsyncReader>> open) {
            this.length = length;
            this.open = open;
        }

        public static Part of(byte[] data) {
            return new Part(data.length, () -> Futures.of(AsyncReader.build(data)));
        }
    }

    private final List<Part> parts;
    private final long totalLength;
    private int index = 0;
    private long withinPart = 0;
    private long position = 0;
    private AsyncReader current = null;

    public ConcatReader(List<Part> parts) {
        this.parts = parts;
        this.totalLength = parts.stream().mapToLong(p -> p.length).sum();
    }

    public long length() {
        return totalLength;
    }

    @Override
    public CompletableFuture<Integer> readIntoArray(byte[] res, int offset, int length) {
        if (length == 0 || position == totalLength)
            return Futures.of(0);
        while (index < parts.size() && withinPart == parts.get(index).length) {
            index++;
            withinPart = 0;
            current = null;
        }
        if (index == parts.size())
            return Futures.of(0);
        Part part = parts.get(index);
        int toRead = (int) Math.min(length, part.length - withinPart);
        if (current == null)
            return part.open.get().thenCompose(opened -> {
                current = opened;
                return readIntoArray(res, offset, length);
            });
        return current.readIntoArray(res, offset, toRead)
                .thenCompose(read -> {
                    if (read <= 0)
                        throw new IllegalStateException("Unexpected end of part " + index + " of a zip write");
                    withinPart += read;
                    position += read;
                    // callers expect a read to return everything they asked for
                    return read == length || position == totalLength ?
                            Futures.of(read) :
                            readIntoArray(res, offset + read, length - read).thenApply(more -> read + more);
                });
    }

    @Override
    public CompletableFuture<AsyncReader> seekJS(int high32, int low32) {
        long offset = (low32 & 0xFFFFFFFFL) | (((long) high32) << 32);
        if (offset < 0 || offset > totalLength)
            throw new IllegalStateException("Seek outside of a zip write: " + offset);
        index = 0;
        withinPart = 0;
        position = 0;
        current = null;
        return skip(offset);
    }

    private CompletableFuture<AsyncReader> skip(long bytes) {
        if (bytes == 0)
            return Futures.of(this);
        byte[] scratch = new byte[(int) Math.min(16 * 1024, bytes)];
        return readIntoArray(scratch, 0, scratch.length)
                .thenCompose(read -> {
                    if (read <= 0)
                        throw new IllegalStateException("Unexpected end of a zip write");
                    return skip(bytes - read);
                });
    }

    @Override
    public CompletableFuture<AsyncReader> reset() {
        return seek(0);
    }

    @Override
    public void close() {
        if (current != null)
            current.close();
    }
}
