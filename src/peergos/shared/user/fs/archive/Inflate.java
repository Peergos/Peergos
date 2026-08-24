package peergos.shared.user.fs.archive;

import jsinterop.annotations.*;

import java.util.concurrent.*;

/** Raw deflate decompression, supplied by the platform.
 *
 *  The JVM has java.util.zip.Inflater and the browser has DecompressionStream("deflate-raw"), but
 *  neither is available to shared code, and the GWT emulation of java.util.zip.InflaterInputStream
 *  is a stub that throws. So an implementation is injected per platform, the same way hashing and
 *  thumbnail generation are.
 *
 *  Nothing needs an implementation to be registered unless it actually inflates: a zip of stored
 *  entries browses and reads without one.
 */
public class Inflate {

    /** A single decompression, driven by pushing compressed input and pulling decompressed output.
     *
     *  Output is pulled rather than returned wholesale so that a small compressed input cannot
     *  force an allocation proportional to its decompressed size.
     */
    public interface Session {

        /** Whether the session has consumed all the input it has been given.
         */
        boolean needsInput();

        /** Supply more compressed bytes. Only valid when needsInput() is true.
         */
        void setInput(byte[] compressed, int offset, int length);

        /** Decompress into res, returning the number of bytes written, which is 0 if more input is
         *  needed or the deflate stream has ended.
         */
        CompletableFuture<Integer> inflate(byte[] res, int offset, int length);

        /** Signal that no more compressed input will be supplied.
         *
         *  A decompressor that only produces output asynchronously cannot know that the input it
         *  has is the last of it, so it is told: the browser implementation closes its stream here
         *  and drains the remaining output.
         */
        void finish();

        /** Whether the end of the deflate stream has been reached.
         */
        boolean finished();

        /** Return to the start of a new deflate stream, discarding any input.
         */
        void reset();

        void close();
    }

    public interface Provider {
        Session start();
    }

    private static Provider instance;

    public static synchronized void setProvider(Provider provider) {
        Inflate.instance = provider;
    }

    public static synchronized boolean isAvailable() {
        return instance != null;
    }

    @JsMethod
    public static synchronized void initJS() {
        setProvider(new JSInflate());
    }

    public static synchronized Session start() {
        if (instance == null)
            throw new IllegalStateException("No inflate implementation has been registered on this platform!");
        return instance.start();
    }

    private Inflate() {}
}
