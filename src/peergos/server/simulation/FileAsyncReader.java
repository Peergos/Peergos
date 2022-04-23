package peergos.server.simulation;

import peergos.shared.user.fs.*;
import peergos.shared.util.*;

import java.io.*;
import java.util.concurrent.*;

public class FileAsyncReader implements AsyncReader {

    private final RandomAccessFile file;

    public FileAsyncReader(File f) throws FileNotFoundException {
        this.file = new RandomAccessFile(f, "r");
    }

    @Override
    public CompletableFuture<AsyncReader> seek(long offset) {
        try {
            file.seek(offset);
            return Futures.of(this);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public CompletableFuture<Integer> readIntoArray(byte[] res, int offset, int length) {
        try {
            return Futures.of(file.read(res, offset, length));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public CompletableFuture<AsyncReader> reset() {
        try {
            file.seek(0);
            return Futures.of(this);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void close() {
        try {
            file.close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
