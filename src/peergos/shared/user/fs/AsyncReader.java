package peergos.shared.user.fs;

import java.util.concurrent.*;

public interface AsyncReader extends AutoCloseable {

    CompletableFuture<Boolean> seek(long offset);

    /**
     *
     * @param res array to store data in
     * @param offset initial index to store data in res
     * @param length number of bytes to read
     * @return number of bytes read
     */
    CompletableFuture<Integer> readIntoArray(byte[] res, int offset, int length);

    /**
     *  reset to original starting position
     * @return
     */
    CompletableFuture<Boolean> reset();

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
        public CompletableFuture<Boolean> seek(long offset) {
            index += (int) offset;
            return CompletableFuture.completedFuture(true);
        }

        @Override
        public CompletableFuture<Integer> readIntoArray(byte[] res, int offset, int length) {
            System.arraycopy(data, index, res, offset, length);
            index += length;
            return CompletableFuture.completedFuture(length);
        }

        @Override
        public CompletableFuture<Boolean> reset() {
            return CompletableFuture.completedFuture(true);
        }

        @Override
        public void close() {
        }
    }
}
