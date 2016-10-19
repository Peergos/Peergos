package peergos.shared.user.fs;

import jsinterop.annotations.*;

import java.util.concurrent.*;

public class BrowserFileReader implements AsyncReader {

    private final JSFileReader reader;

    @JsConstructor
    public BrowserFileReader(JSFileReader reader) {
        this.reader = reader;
    }

    public CompletableFuture<Boolean> seek(int high32, int low32) {
        return reader.seek(high32, low32);
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
    public CompletableFuture<Boolean> reset() {
        return reader.reset();
    }

    /**
     * Close and dispose of any resources
     */
    public void close() {
        reader.close();
    }
}
