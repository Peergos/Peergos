package peergos.util;

import java.io.*;

public class ResetableFileInputStream extends InputStream {

    private final RandomAccessFile raf;
    private long currentIndex = 0;

    public ResetableFileInputStream(RandomAccessFile raf) {
        this.raf = raf;
    }

    public ResetableFileInputStream(File f) throws IOException {
        this(new RandomAccessFile(f, "r"));
    }

    @Override
    public int read() throws IOException {
        currentIndex++;
        return raf.read();
    }

    @Override
    public synchronized void reset() throws IOException {
        raf.seek(0);
        currentIndex = 0;
    }

    @Override
    public void close() throws IOException {
        raf.close();
        super.close();
    }

    @Override
    public int available() throws IOException {
        return 0;
    }

    @Override
    public long skip(long n) throws IOException {
        raf.seek(n + currentIndex);
        return n;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        return super.read(b, off, len);
    }

    @Override
    public int read(byte[] b) throws IOException {
        return super.read(b);
    }
}
