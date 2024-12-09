package peergos.server.sync;

import peergos.shared.user.fs.*;
import peergos.shared.util.Pair;
import peergos.shared.util.Triple;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;

interface SyncFilesystem {

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

    void setBytes(Path p, long fileOffset, AsyncReader data, long size, Optional<HashTree> hash, Optional<LocalDateTime> modificationTime) throws IOException;

    AsyncReader getBytes(Path p, long fileOffset) throws IOException;

    void uploadSubtree(Path baseDir, Stream<FileWrapper.FolderUploadProperties> directories);

    HashTree hashFile(Path p, Optional<FileWrapper> meta, String relativePath, SyncState syncedState);

    void applyToSubtree(Path start, Consumer<FileProps> file, Consumer<FileProps> dir) throws IOException;

    class FileProps {
        public final Path path;
        public final long modifiedTime;
        public final long size;
        public final Optional<FileWrapper> meta;

        public FileProps(Path path, long modifiedTime, long size, Optional<FileWrapper> meta) {
            this.path = path;
            this.modifiedTime = modifiedTime;
            this.size = size;
            this.meta = meta;
        }
    }
}
