package peergos.server.sync;

import peergos.shared.user.fs.AsyncReader;
import peergos.shared.user.fs.Blake3state;

import java.io.IOException;
import java.nio.file.Path;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

interface SyncFilesystem {

    boolean exists(Path p);

    void mkdirs(Path p);

    void delete(Path p);

    void moveTo(Path src, Path target);

    long getLastModified(Path p);

    void setModificationTime(Path p, long t);

    void setHash(Path p, Blake3state hash);

    long size(Path p);

    void truncate(Path p, long size) throws IOException;

    void setBytes(Path p, long fileOffset, AsyncReader data, long size) throws IOException;

    AsyncReader getBytes(Path p, long fileOffset) throws IOException;

    Blake3state hashFile(Path p);

    void applyToSubtree(Path start, BiConsumer<Path, Long> file, Consumer<Path> dir) throws IOException;
}
