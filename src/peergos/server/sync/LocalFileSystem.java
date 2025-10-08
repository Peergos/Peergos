package peergos.server.sync;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import peergos.server.crypto.hash.ScryptJava;
import peergos.server.simulation.FileAsyncReader;
import peergos.shared.crypto.hash.Hasher;
import peergos.shared.crypto.hash.PublicKeyHash;
import peergos.shared.user.fs.*;
import peergos.shared.util.PathUtil;
import peergos.shared.util.Triple;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;

public class LocalFileSystem implements SyncFilesystem {

    private static final Logger LOG = LoggerFactory.getLogger(LocalFileSystem.class);
    private final Hasher hasher;
    private final Path root;
    private final boolean hasBackSlashes;

    public LocalFileSystem(Path root, Hasher hasher) {
        this.root = root;
        this.hasher = hasher;
        if (! exists(root))
            throw new IllegalStateException("Dir does not exist: " + root);
        this.hasBackSlashes = ! root.getFileSystem().getSeparator().equals("/");
    }

    @Override
    public long totalSpace() throws IOException {
        return Files.getFileStore(root).getTotalSpace();
    }

    @Override
    public long freeSpace() throws IOException {
        return Files.getFileStore(root).getUsableSpace();
    }

    @Override
    public String getRoot() {
        return root.toString();
    }

    @Override
    public Path resolve(String p) {
        return PathUtil.get(p);
    }

    @Override
    public boolean exists(Path p) {
        return root.resolve(p).toFile().exists();
    }

    @Override
    public void mkdirs(Path p) {
        File f = root.resolve(p).toFile();
        if (f.exists() && f.isDirectory())
            return;
        if (!f.mkdirs() && ! f.exists())
            throw new IllegalStateException("Couldn't create " + root.resolve(p));
    }

    @Override
    public void delete(Path p) {
        p = root.resolve(p);
        try {
            if (Files.isDirectory(p))
                try (Stream<Path> stream = Files.list(p)) {
                    if (stream.anyMatch(f -> true))
                        throw new IllegalStateException("Trying to delete non empty directory: " + p);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        try {
            Files.delete(p);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void bulkDelete(Path dir, Set<String> children) {
        children.forEach(kid -> delete(dir.resolve(kid)));
    }

    @Override
    public void moveTo(Path src, Path target) {
        try {
            Files.createDirectories(root.resolve(target).getParent());
            Files.move(root.resolve(src), root.resolve(target));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public long getLastModified(Path p) {
        long millis = root.resolve(p).toFile().lastModified();
        return millis / 1000 * 1000;
    }

    @Override
    public void setModificationTime(Path p, long modificationTime) {
        root.resolve(p).toFile().setLastModified(modificationTime / 1000 * 1000);
    }

    @Override
    public void setHash(Path p, HashTree hashTree, long fileSize) {}

    @Override
    public void setHashes(List<Triple<String, FileWrapper, HashTree>> toUpdate) {}

    @Override
    public long size(Path p) {
        return root.resolve(p).toFile().length();
    }

    @Override
    public void truncate(Path p, long size) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(root.resolve(p).toFile(), "rw")) {
            raf.setLength(size);
        }
    }

    @Override
    public Optional<LocalDateTime> setBytes(Path p,
                                            long fileOffset,
                                            AsyncReader fin,
                                            long size,
                                            Optional<HashTree> hash,
                                            Optional<LocalDateTime> modificationTime,
                                            Optional<Thumbnail> thumbnail,
                                            ResumeUploadProps props,
                                            Supplier<Boolean> isCancelled,
                                            Consumer<String> progress) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(root.resolve(p).toFile(), "rw")) {
            raf.seek(fileOffset);
            byte[] buf = new byte[4096];
            long done = 0;
            while (done < size) {
                int read = fin.readIntoArray(buf, 0, (int) Math.min(buf.length, size - done)).join();
                raf.write(buf, 0, read);
                done += read;
                if (done >= 1024*1024)
                    progress.accept("Downloaded " + (done/1024/1024) + " / " + (size / 1024/1024) + " MiB of " + p.getFileName().toString());
            }
            if (modificationTime.isPresent()) {
                long time = modificationTime.get().toInstant(ZoneOffset.UTC).toEpochMilli() / 1000 * 1000;
                if (time >= 0) {
                    root.resolve(p).toFile().setLastModified(time);
                    return modificationTime;
                } else
                    return Optional.empty();
            }
            return modificationTime;
        }
    }

    @Override
    public AsyncReader getBytes(Path p, long fileOffset) throws IOException {
        return new FileAsyncReader(root.resolve(p).toFile());
    }

    @Override
    public void uploadSubtree(Stream<FileWrapper.FolderUploadProperties> directories) {
        byte[] buf = new byte[5*1024*1024];
        directories.forEach(folder -> {
            Path dir = root.resolve(folder.path());
            dir.toFile().mkdirs();
            for (FileWrapper.FileUploadProperties file : folder.files) {
                try (AsyncReader reader = file.fileData.get()) {
                    long written = 0;
                    try (FileOutputStream fout = new FileOutputStream(dir.resolve(file.filename).toFile())) {
                        while (written < file.length) {
                            int read = reader.readIntoArray(buf, 0, (int) Math.min(buf.length, file.length - written)).join();
                            fout.write(buf, 0, read);
                            written += read;
                        }
                        fout.flush();
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }
            }
        });
    }

    @Override
    public Optional<Thumbnail> getThumbnail(Path p) {
        return ThumbnailGenerator.getVideo().generateVideoThumbnail(root.resolve(p).toFile());
    }

    @Override
    public HashTree hashFile(Path p, Optional<FileWrapper> meta, String relPath, SyncState syncedVersions) {
        return ScryptJava.hashFile(root.resolve(p), hasher);
    }

    @Override
    public long filesCount() throws IOException {
        AtomicLong count = new AtomicLong(0);
        try (Stream<Path> stream = Files.list(root)) {
            stream.forEach(p -> {
                if (Files.isRegularFile(p))
                    count.incrementAndGet();
            });
        }
        return count.get();
    }

    @Override
    public Optional<PublicKeyHash> applyToSubtree(Consumer<FileProps> file, Consumer<FileProps> dir) throws IOException {
        applyToSubtree(root, file, dir);
        return Optional.empty();
    }

    private void applyToSubtree(Path start, Consumer<FileProps> file, Consumer<FileProps> dir) throws IOException {
        try (Stream<Path> stream = Files.list(start)) {
            stream.forEach(c -> {
                String relPath = root.relativize(start.resolve(c.getFileName())).normalize().toString();
                String canonicalRelPath = hasBackSlashes ? relPath.replaceAll("\\\\", "/") : relPath;
                FileProps props = new FileProps(canonicalRelPath, c.toFile().lastModified() / 1000 * 1000, c.toFile().length(), Optional.empty());
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
}
