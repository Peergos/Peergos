package peergos.server.sync;

import peergos.shared.crypto.hash.PublicKeyHash;
import peergos.shared.user.fs.*;
import peergos.shared.util.Triple;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;

public interface SyncFilesystem {

    long totalSpace() throws IOException;

    long freeSpace() throws IOException;

    String getRoot();

    Path resolve(String p);

    boolean exists(Path p);

    void mkdirs(Path p);

    void delete(Path p);

    void bulkDelete(Path dir, Set<String> children);

    void moveTo(Path src, Path target);

    long getLastModified(Path p);

    void setModificationTime(Path p, long t);

    void setHash(Path p, HashTree hashTree, long fileSize);

    void setHashes(List<Triple<String, FileWrapper, HashTree>> toUpdate);

    long size(Path p);

    void truncate(Path p, long size) throws IOException;

    /**
     *
     * @param p
     * @param fileOffset
     * @param data
     * @param size
     * @param hash
     * @param modificationTime
     * @param thumbnail
     * @param props
     * @param isCancelled
     * @param progress
     * @return The actual modification time the filesystem returns after write
     * @throws IOException
     */
    Optional<LocalDateTime> setBytes(Path p,
                                     long fileOffset,
                                     AsyncReader data,
                                     long size,
                                     Optional<HashTree> hash,
                                     Optional<LocalDateTime> modificationTime,
                                     Optional<Thumbnail> thumbnail,
                                     ResumeUploadProps props,
                                     Supplier<Boolean> isCancelled,
                                     Consumer<String> progress) throws IOException;

    AsyncReader getBytes(Path p, long fileOffset) throws IOException;

    void uploadSubtree(Stream<FileWrapper.FolderUploadProperties> directories);

    Optional<Thumbnail> getThumbnail(Path p);

    HashTree hashFile(Path p, Optional<FileWrapper> meta, String relativePath, SyncState syncedState);

    /**
     *
     * @param file
     * @param dir
     * @return the writer to ignore from snapshots (we only have read access to it as the entry point)
     * @throws IOException
     */
    Optional<PublicKeyHash> applyToSubtree(Consumer<FileProps> file, Consumer<FileProps> dir) throws IOException;

    long filesCount() throws IOException;

    class FileProps {
        public final String relPath;
        public final long modifiedTime;
        public final long size;
        public final Optional<FileWrapper> meta;

        public FileProps(String relPath, long modifiedTime, long size, Optional<FileWrapper> meta) {
            this.relPath = relPath;
            this.modifiedTime = modifiedTime;
            this.size = size;
            this.meta = meta;
        }
    }
}
