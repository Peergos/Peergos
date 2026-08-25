package peergos.shared.user.fs.archive;

import jsinterop.annotations.*;

import java.util.concurrent.*;

/** The browser's raw deflate decompression, one instance per deflate stream.
 *
 *  Implemented in web-ui by `vendor/priors/zip.js` over DecompressionStream("deflate-raw").
 */
@JsType(namespace = "zipInflate", isNative = true)
public class NativeJSInflate {

    public native boolean needsInput();

    public native void setInput(byte[] compressed, int offset, int length);

    public native CompletableFuture<Integer> inflate(byte[] res, int offset, int length);

    public native void finish();

    public native boolean finished();

    public native void reset();

    public native void close();
}
