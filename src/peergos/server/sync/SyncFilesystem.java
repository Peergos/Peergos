package peergos.server.sync;

import peergos.shared.user.fs.AsyncReader;
import peergos.shared.user.fs.Blake3state;
import peergos.shared.user.fs.FileWrapper;
import peergos.shared.util.Pair;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

interface SyncFilesystem {

    boolean exists(Path p);

    void mkdirs(Path p);

    void delete(Path p);

    void moveTo(Path src, Path target);

    long getLastModified(Path p);

    void setModificationTime(Path p, long t);

    void setHash(Path p, Blake3state hash);

    void setHashes(List<Pair<FileWrapper, Blake3state>> toUpdate);

    long size(Path p);

    void truncate(Path p, long size) throws IOException;

    void setBytes(Path p, long fileOffset, AsyncReader data, long size) throws IOException;

    AsyncReader getBytes(Path p, long fileOffset) throws IOException;

    Blake3state hashFile(Path p, Optional<FileWrapper> meta);

    void applyToSubtree(Path start, Consumer<FileProps> file, Consumer<Path> dir) throws IOException;

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
