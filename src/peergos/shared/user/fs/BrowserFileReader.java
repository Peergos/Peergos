package peergos.shared.user.fs;

import jsinterop.annotations.*;

import java.util.concurrent.*;

public class BrowserFileReader implements AsyncReader {

    private final JSFileReader reader;

    @JsConstructor
    public BrowserFileReader(JSFileReader reader) {
        this.reader = reader;
    }

    public CompletableFuture<AsyncReader> seekJS(int high32, int low32) {
        return reader.seek(high32, low32).thenApply(x -> this);
    }

    /**
     *
     * @param res array to store data in
     * @param offset initial index to store data in res
     * @param length number of bytes to read
     * @return number of bytes read
     */
    public CompletableFuture<Integer> readIntoArray(byte[] res, int offset, int length) {
        return reader.readIntoArray(res, offset, length);
    }

    /**
     *  reset to original starting position
     * @return
     */
    public CompletableFuture<AsyncReader> reset() {
        return reader.reset().thenApply(x -> this);
    }

    /**
     * Close and dispose of any resources
     */
    public void close() {
        reader.close();
    }
}
