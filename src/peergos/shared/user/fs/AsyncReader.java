package peergos.shared.user.fs;

import jsinterop.annotations.*;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@JsType
public interface AsyncReader extends AutoCloseable {

    CompletableFuture<AsyncReader> seek(int high32, int low32);

    /**
     *
     * @param res array to store data in
     * @param offset initial index to store data in res
     * @param length number of bytes to read
     * @return number of bytes read
     */
    CompletableFuture<Integer> readIntoArray(byte[] res, int offset, int length);

    /**Files
     *  reset to original starting position
     * @return
     */
    CompletableFuture<AsyncReader> reset();

    /**
     * Close and dispose of any resources
     */
    void close();

    class ArrayBacked implements AsyncReader {
        private final byte[] data;
        private int index = 0;

        public ArrayBacked(byte[] data) {
            this.data = data;
        }

        @Override
        public CompletableFuture<AsyncReader> seek(int high32, int low32) {
            if (high32 != 0)
                throw new IllegalArgumentException("Cannot have arrays larger than 4GiB!");
            index += low32;
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public CompletableFuture<Integer> readIntoArray(byte[] res, int offset, int length) {
            try {
                System.arraycopy(data, index, res, offset, length);
            } catch (ArrayIndexOutOfBoundsException e) {
                System.out.println();
            }
            index += length;
            return CompletableFuture.completedFuture(length);
        }

        @Override
        public CompletableFuture<AsyncReader> reset() {
            index = 0;
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public void close() {
        }
    }

    static AsyncReader build(byte[] data) {
        return new ArrayBacked(data);
    }
}
