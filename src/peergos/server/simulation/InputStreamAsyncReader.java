package peergos.server.simulation;

import peergos.shared.user.fs.AsyncReader;
import peergos.shared.util.Futures;

import java.io.*;
import java.util.concurrent.CompletableFuture;

public class InputStreamAsyncReader implements AsyncReader {

    private final InputStream is;

    public InputStreamAsyncReader(InputStream is) {
        this.is = is;
    }

    @Override
    public CompletableFuture<AsyncReader> seek(long offset) {
        throw new IllegalStateException("Operation not supported!");
    }

    @Override
    public CompletableFuture<Integer> readIntoArray(byte[] res, int offset, int length) {
        try {
            return Futures.of(is.read(res, offset, length));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public CompletableFuture<AsyncReader> reset() {
        try {
            is.reset();
            return Futures.of(this);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void close() {
        try {
            is.close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
