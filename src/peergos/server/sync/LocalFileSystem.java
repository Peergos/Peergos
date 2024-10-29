package peergos.server.sync;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import peergos.server.crypto.hash.Blake3;
import peergos.server.simulation.FileAsyncReader;
import peergos.shared.user.fs.AsyncReader;
import peergos.shared.user.fs.Blake3state;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

public class LocalFileSystem implements SyncFilesystem {

    private static final Logger LOG = LoggerFactory.getLogger(LocalFileSystem.class);

    @Override
    public boolean exists(Path p) {
        return p.toFile().exists();
    }

    @Override
    public void mkdirs(Path p) {
        File f = p.toFile();
        if (f.exists() && f.isDirectory())
            return;
        if (!f.mkdirs())
            throw new IllegalStateException("Couldn't create " + p);
    }

    @Override
    public void delete(Path p) {
        p.toFile().delete();
    }

    @Override
    public void moveTo(Path src, Path target) {
        target.getParent().toFile().mkdirs();
        src.toFile().renameTo(target.toFile());
    }

    @Override
    public long getLastModified(Path p) {
        long millis = p.toFile().lastModified();
        return millis / 1000 * 1000;
    }

    @Override
    public void setModificationTime(Path p, long modificationTime) {
        p.toFile().setLastModified(modificationTime / 1000 * 1000);
    }

    @Override
    public void setHash(Path p, Blake3state hash) {}

    @Override
    public long size(Path p) {
        return p.toFile().length();
    }

    @Override
    public void truncate(Path p, long size) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(p.toFile(), "rw")) {
            raf.setLength(size);
        }
    }

    @Override
    public void setBytes(Path p, long fileOffset, AsyncReader fin, long size) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(p.toFile(), "rw")) {
            raf.seek(fileOffset);
            byte[] buf = new byte[4096];
            long done = 0;
            while (done < size) {
                int read = fin.readIntoArray(buf, 0, (int) Math.min(buf.length, size - done)).join();
                raf.write(buf, 0, read);
                done += read;
            }
        }
    }

    @Override
    public AsyncReader getBytes(Path p, long fileOffset) throws IOException {
        return new FileAsyncReader(p.toFile());
    }

    @Override
    public Blake3state hashFile(Path p) {
        byte[] buf = new byte[4 * 1024];
        long size = p.toFile().length();
        Blake3 state = Blake3.initHash();

        try (FileInputStream fin = new FileInputStream(p.toFile())) {
            for (long i = 0; i < size; ) {
                int read = fin.read(buf);
                state.update(buf, 0, read);
                i += read;
            }

            byte[] hash = state.doFinalize(32);
            return new Blake3state(hash);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void applyToSubtree(Path start, Consumer<Path> file, Consumer<Path> dir) throws IOException {
        Files.list(start).forEach(c -> {
            if (Files.isRegularFile(c)) {
                file.accept(c);
            } else if (Files.isDirectory(c)) {
                dir.accept(start.resolve(c.getFileName()));
                try {
                    applyToSubtree(start.resolve(c.getFileName()), file, dir);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        });
    }
}
