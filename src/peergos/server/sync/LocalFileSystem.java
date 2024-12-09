package peergos.server.sync;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import peergos.server.crypto.hash.ScryptJava;
import peergos.server.simulation.FileAsyncReader;
import peergos.shared.crypto.hash.Hasher;
import peergos.shared.user.fs.*;
import peergos.shared.util.Pair;
import peergos.shared.util.Triple;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;

public class LocalFileSystem implements SyncFilesystem {

    private static final Logger LOG = LoggerFactory.getLogger(LocalFileSystem.class);
    private final Hasher hasher;

    public LocalFileSystem(Hasher hasher) {
        this.hasher = hasher;
    }

    @Override
    public boolean exists(Path p) {
        return p.toFile().exists();
    }

    @Override
    public void mkdirs(Path p) {
        File f = p.toFile();
        if (f.exists() && f.isDirectory())
            return;
        if (!f.mkdirs() && ! f.exists())
            throw new IllegalStateException("Couldn't create " + p);
    }

    @Override
    public void delete(Path p) {
        try {
            if (Files.isDirectory(p) && Files.list(p).anyMatch(f -> true))
                throw new IllegalStateException("Trying to delete non empty directory: " + p);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        p.toFile().delete();
    }

    @Override
    public void bulkDelete(Path dir, Set<String> children) {
        throw new IllegalStateException("Unimplemented");
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
    public void setHash(Path p, HashTree hashTree, long fileSize) {}

    @Override
    public void setHashes(List<Triple<String, FileWrapper, HashTree>> toUpdate) {}

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
    public void setBytes(Path p, long fileOffset, AsyncReader fin, long size, Optional<HashTree> hash, Optional<LocalDateTime> modificationTime) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(p.toFile(), "rw")) {
            raf.seek(fileOffset);
            byte[] buf = new byte[4096];
            long done = 0;
            while (done < size) {
                int read = fin.readIntoArray(buf, 0, (int) Math.min(buf.length, size - done)).join();
                raf.write(buf, 0, read);
                done += read;
            }
            if (modificationTime.isPresent())
                p.toFile().setLastModified(modificationTime.get().toInstant(ZoneOffset.UTC).toEpochMilli() / 1000 * 1000);
        }
    }

    @Override
    public AsyncReader getBytes(Path p, long fileOffset) throws IOException {
        return new FileAsyncReader(p.toFile());
    }

    @Override
    public void uploadSubtree(Path baseDir, Stream<FileWrapper.FolderUploadProperties> directories) {
        throw new IllegalStateException("Unimplemented!");
    }

    @Override
    public HashTree hashFile(Path p, Optional<FileWrapper> meta, String relPath, SyncState syncedVersions) {
        return ScryptJava.hashFile(p, hasher);
    }

    @Override
    public void applyToSubtree(Path start, Consumer<FileProps> file, Consumer<FileProps> dir) throws IOException {
        Files.list(start).forEach(c -> {
            FileProps props = new FileProps(start.resolve(c.getFileName()), c.toFile().lastModified() / 1000 * 1000, c.toFile().length(), Optional.empty());
            if (Files.isRegularFile(c)) {
                file.accept(props);
            } else if (Files.isDirectory(c)) {
                dir.accept(props);
                try {
                    applyToSubtree(start.resolve(c.getFileName()), file, dir);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        });
    }
}
