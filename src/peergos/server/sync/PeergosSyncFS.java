package peergos.server.sync;

import peergos.server.crypto.hash.Blake3;
import peergos.shared.storage.auth.BatId;
import peergos.shared.user.UserContext;
import peergos.shared.user.fs.AsyncReader;
import peergos.shared.user.fs.FileWrapper;
import peergos.shared.util.Futures;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

public class PeergosSyncFS implements SyncFilesystem {

    private final UserContext context;

    public PeergosSyncFS(UserContext context) {
        this.context = context;
    }

    @Override
    public boolean exists(Path p) {
        return context.getByPath(p).join().isPresent();
    }

    @Override
    public void mkdirs(Path p) {
        int depth = p.getNameCount();
        Optional<BatId> mirrorBat = context.mirrorBatId();
        for (int i=1; i < depth; i++) {
            Optional<FileWrapper> current = context.getByPath(p.subpath(0, i)).join();
            if (current.isEmpty()) {
                FileWrapper parent = context.getByPath(p.subpath(0, i - 1)).join().get();
                parent.mkdir(p.getName(i).toString(), context.network, false, mirrorBat, context.crypto).join();
            }
        }
    }

    @Override
    public void delete(Path p) {
        FileWrapper f = context.getByPath(p).join().get();
        FileWrapper parent = context.getByPath(p.getParent()).join().get();
        f.remove(parent, p, context).join();
    }

    @Override
    public void moveTo(Path src, Path target) {
        FileWrapper from = context.getByPath(src).join().get();
        FileWrapper newParent = context.getByPath(target.getParent()).join().get();
        FileWrapper parent = context.getByPath(src.getParent()).join().get();
        from.moveTo(newParent, parent, src, context, () -> Futures.of(true));
    }

    @Override
    public long getLastModified(Path p) {
        LocalDateTime modified = context.getByPath(p).join().get().getFileProperties().modified;
        return modified.toInstant(ZoneOffset.UTC).toEpochMilli();
    }

    @Override
    public void setModificationTime(Path p, long t) {
        FileWrapper f = context.getByPath(p).join().get();
        LocalDateTime newModified = LocalDateTime.ofInstant(Instant.ofEpochSecond(t / 1000, t % 1000), ZoneOffset.UTC);
        Optional<FileWrapper> parent = context.getByPath(p.getParent()).join();
        f.setProperties(f.getFileProperties().withModified(newModified), context.crypto.hasher, context.network, parent).join();
    }

    @Override
    public long size(Path p) {
        return context.getByPath(p).join().get().getFileProperties().size;
    }

    @Override
    public void truncate(Path p, long size) throws IOException {
        FileWrapper f = context.getByPath(p).join().get();
        f.truncate(size, context.network, context.crypto).join();
    }

    @Override
    public void setBytes(Path p, long fileOffset, AsyncReader data, long size) throws IOException {
        FileWrapper f = context.getByPath(p).join().get();
        long end = fileOffset + size;
        f.overwriteSectionJS(data, (int)(fileOffset >>> 32), (int)fileOffset, (int) (end >>> 32), (int)end, context.network, context.crypto, x -> {}).join();
    }

    @Override
    public AsyncReader getBytes(Path p, long fileOffset) throws IOException {
        FileWrapper f = context.getByPath(p).join().get();
        return f.getInputStream(context.network, context.crypto, x -> {}).join();
    }

    @Override
    public DirectorySync.Blake3state hashFile(Path p) {
        byte[] buf = new byte[4 * 1024];
        Blake3 state = Blake3.initHash();

        FileWrapper f = context.getByPath(p).join().get();
        long size = f.getSize();
        AsyncReader reader = f.getInputStream(context.network, context.crypto, x -> {}).join();
        for (long i = 0; i < size; ) {
            int read = reader.readIntoArray(buf, 0, (int)Math.min(buf.length, size - i)).join();
            state.update(buf, 0, read);
            i += read;
        }

        byte[] hash = state.doFinalize(32);
        return new DirectorySync.Blake3state(hash);
    }

    @Override
    public void applyToSubtree(Path start, Consumer<Path> onFile, Consumer<Path> onDir) {
        FileWrapper base = context.getByPath(start).join().get();
        applyToSubtree(start, base, onFile, onDir);

    }

    private void applyToSubtree(Path basePath, FileWrapper base, Consumer<Path> onFile, Consumer<Path> onDir) {
        Set<FileWrapper> children = base.getChildren(context.crypto.hasher, context.network).join();
        for (FileWrapper child : children) {
            Path childPath = basePath.resolve(child.getName());
            if (! child.isDirectory()) {
                onFile.accept(childPath);
            } else {
                onDir.accept(childPath);
                applyToSubtree(childPath, child, onFile, onDir);
            }
        }
    }
}
