package peergos.shared.user.fs;

import jsinterop.annotations.*;

import java.util.concurrent.*;

@JsType(isNative = true, namespace = "browserio")
public class JSFileReader {

    public native CompletableFuture<Boolean> seek(int high32, int low32);
    /**
     *
     * @param res array to store data in
     * @param offset initial index to store data in res
     * @param length number of bytes to read
     * @return number of bytes read
     */
    public native CompletableFuture<Integer> readIntoArray(byte[] res, int offset, int length);

    /**
     *  reset to original starting position
     * @return
     */
    public native CompletableFuture<Boolean> reset();

    /**
     * Close and dispose of any resources
     */
    public native void close();
}
