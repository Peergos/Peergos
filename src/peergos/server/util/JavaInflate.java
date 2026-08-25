package peergos.server.util;

import peergos.shared.user.fs.archive.*;
import peergos.shared.util.*;

import java.util.concurrent.*;
import java.util.zip.Inflater;

/** Raw deflate decompression on the JVM, for the server, the CLI and Android.
 */
public class JavaInflate implements Inflate.Provider {

    public static void init() {
        Inflate.setProvider(new JavaInflate());
    }

    @Override
    public Inflate.Session start() {
        return new JavaSession();
    }

    private static class JavaSession implements Inflate.Session {
        private final Inflater inflater = new Inflater(true);

        @Override
        public boolean needsInput() {
            return inflater.needsInput();
        }

        @Override
        public void setInput(byte[] compressed, int offset, int length) {
            inflater.setInput(compressed, offset, length);
        }

        @Override
        public CompletableFuture<Integer> inflate(byte[] res, int offset, int length) {
            try {
                return Futures.of(inflater.inflate(res, offset, length));
            } catch (java.util.zip.DataFormatException e) {
                return Futures.errored(new IllegalStateException("Corrupt deflate stream in zip entry: " + e.getMessage()));
            }
        }

        @Override
        public void finish() {
            // the JVM inflater discovers the end of the stream from the stream itself
        }

        @Override
        public boolean finished() {
            return inflater.finished();
        }

        @Override
        public void reset() {
            inflater.reset();
        }

        @Override
        public void close() {
            inflater.end();
        }
    }
}
