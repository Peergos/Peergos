package peergos.server.storage;

import peergos.shared.user.fs.*;

import java.io.*;
import java.util.concurrent.*;

public class ResetableFileInputStream implements AsyncReader {

    private final RandomAccessFile raf;
    private long currentIndex = 0;

    public ResetableFileInputStream(RandomAccessFile raf) {
        this.raf = raf;
    }

    public ResetableFileInputStream(File f) throws IOException {
        this(new RandomAccessFile(f, "r"));
    }

    @Override
    public CompletableFuture<Boolean> seek(long offset) {
        try {
            raf.seek(offset);
            return CompletableFuture.completedFuture(true);
        } catch (IOException e) {
            CompletableFuture<Boolean> err = new CompletableFuture<>();
            err.completeExceptionally(e);
            return err;
        }
    }

    @Override
    public CompletableFuture<Integer> readIntoArray(byte[] res, int offset, int length) {
        try {
            int read = raf.read(res, offset, length);
            return CompletableFuture.completedFuture(read);
        } catch (IOException e) {
            CompletableFuture<Integer> err = new CompletableFuture<>();
            err.completeExceptionally(e);
            return err;
        }
    }

    @Override
    public synchronized CompletableFuture<Boolean> reset() {
        try {
            raf.seek(0);
            currentIndex = 0;
            return CompletableFuture.completedFuture(true);
        } catch (IOException e) {
            CompletableFuture<Boolean> err = new CompletableFuture<>();
            err.completeExceptionally(e);
            return err;
        }
    }

    @Override
    public void close() {
        try {
            raf.close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

}
