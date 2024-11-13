package peergos.server.sync;

import peergos.server.crypto.hash.Blake3;
import peergos.server.util.Logging;
import peergos.shared.storage.auth.BatId;
import peergos.shared.user.UserContext;
import peergos.shared.user.fs.AsyncReader;
import peergos.shared.user.fs.Blake3state;
import peergos.shared.user.fs.FileProperties;
import peergos.shared.user.fs.FileWrapper;
import peergos.shared.util.Futures;
import peergos.shared.util.Pair;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class PeergosSyncFS implements SyncFilesystem {
    private static final Logger LOG = Logging.LOG();

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
        Optional<BatId> mirrorBat = context.mirrorBatId();
        if (exists(p))
            return;
        mkdirs(p.getParent());
        FileWrapper parent = context.getByPath(p.getParent()).join().get();
        parent.mkdir(p.getFileName().toString(), context.network, false, mirrorBat, context.crypto).join();
    }

    @Override
    public void delete(Path p) {
        FileWrapper f = context.getByPath(p).join().get();
        if (f.isDirectory() && f.hasChildren(context.network).join())
            throw new IllegalStateException("Trying to delete non empty directory: " + p);
        FileWrapper parent = context.getByPath(p.getParent()).join().get();
        f.remove(parent, p, context).join();
    }

    @Override
    public void bulkDelete(Path dir, Set<String> children) {
        FileWrapper parent = context.getByPath(dir).join().get();
        Set<FileWrapper> kids = parent.getChildren(children, context.crypto.hasher, context.network, false).join();
        FileWrapper.deleteChildren(parent, kids, dir, context).join();
    }

    @Override
    public void moveTo(Path src, Path target) {
        if (target.getParent().equals(src.getParent())) { // rename
            Optional<FileWrapper> parentOpt = context.getByPath(src.getParent()).join();
            if (parentOpt.isEmpty())
                throw new IllegalStateException("Couldn't retrieve " + src.getParent());
            FileWrapper parent = parentOpt.get();
            Optional<FileWrapper> srcOpt = context.getByPath(src).join();
            if (srcOpt.isEmpty())
                throw new IllegalStateException("Couldn't retrieve " + src);
            FileWrapper from = srcOpt.get();
            from.rename(target.getFileName().toString(), parent, src, context).join();
        } else {
            Optional<FileWrapper> newParent = context.getByPath(target.getParent()).join();
            if (newParent.isEmpty()) {
                mkdirs(target.getParent());
                newParent = context.getByPath(target.getParent()).join();
            }
            Optional<FileWrapper> srcOpt = context.getByPath(src).join();
            if (srcOpt.isEmpty())
                throw new IllegalStateException("Couldn't retrieve " + src);
            FileWrapper from = srcOpt.get();
            Optional<FileWrapper> parentOpt = context.getByPath(src.getParent()).join();
            if (parentOpt.isEmpty())
                throw new IllegalStateException("Couldn't retrieve " + src.getParent());
            FileWrapper parent = parentOpt.get();
            from.moveTo(newParent.get(), parent, src, context, () -> Futures.of(true));
        }
    }

    @Override
    public long getLastModified(Path p) {
        Optional<FileWrapper> file = context.getByPath(p).join();
        if (file.isEmpty())
            throw new IllegalStateException("Couldn't retrieve file modification time for " + p);
        LocalDateTime modified = file.get().getFileProperties().modified;
        return modified.toInstant(ZoneOffset.UTC).toEpochMilli() / 1000 * 1000;
    }

    @Override
    public void setModificationTime(Path p, long t) {
        FileWrapper f = context.getByPath(p).join().get();
        LocalDateTime newModified = LocalDateTime.ofInstant(Instant.ofEpochSecond(t / 1000, 0), ZoneOffset.UTC);
        Optional<FileWrapper> parent = context.getByPath(p.getParent()).join();
        f.setProperties(f.getFileProperties().withModified(newModified), context.crypto.hasher, context.network, parent).join();
    }

    @Override
    public void setHash(Path p, Blake3state hash) {
        FileWrapper f = context.getByPath(p).join().get();
        Optional<FileWrapper> parent = context.getByPath(p.getParent()).join();
        f.setProperties(f.getFileProperties().withHash(Optional.of(hash)), context.crypto.hasher, context.network, parent).join();
    }

    @Override
    public void setHashes(List<Pair<FileWrapper, Blake3state>> toUpdate) {
        FileWrapper.bulkSetSameNameProperties(toUpdate.stream()
                .map(p -> new Pair<>(p.left, p.left.getFileProperties().withHash(Optional.of(p.right))))
                .collect(Collectors.toList()), context.network).join();
    }

    @Override
    public long size(Path p) {
        Optional<FileWrapper> file = context.getByPath(p).join();
        if (file.isEmpty())
            throw new IllegalStateException("Couldn't retrieve file size for " + p);
        return file.get().getFileProperties().size;
    }

    @Override
    public void truncate(Path p, long size) throws IOException {
        FileWrapper f = context.getByPath(p).join().get();
        f.truncate(size, context.network, context.crypto).join();
    }

    @Override
    public void setBytes(Path p, long fileOffset, AsyncReader data, long size) throws IOException {
        Optional<FileWrapper> existing = context.getByPath(p).join();
        if (existing.isEmpty() && fileOffset == 0) {
            FileWrapper parent = context.getByPath(p.getParent()).join().get();
            parent.uploadOrReplaceFile(p.getFileName().toString(), data, size, context.network, context.crypto, x -> {}).join();
        } else {
            FileWrapper f = existing.get();
            if (f.isDirty()) {
                FileWrapper ff = f;
                context.network.synchronizer.applyComplexUpdate(f.owner(), f.signingPair(), (v, c) -> ff.clean(v, c, context.network, context.crypto)
                        .thenApply(r -> r.right)).join();
                f = context.getByPath(p).join().get();
            }

            long end = fileOffset + size;
            f.overwriteSectionJS(data, (int) (fileOffset >>> 32), (int) fileOffset, (int) (end >>> 32), (int) end, context.network, context.crypto, x -> {
            }).join();
        }
    }

    @Override
    public AsyncReader getBytes(Path p, long fileOffset) throws IOException {
        Optional<FileWrapper> file = context.getByPath(p).join();
        if (file.isEmpty())
            throw new IllegalStateException("Couldn't retrieve " + p);
        FileWrapper f = file.get();
        AsyncReader reader = f.getInputStream(context.network, context.crypto, x -> {}).join();
        return reader.seek(fileOffset).join();
    }

    @Override
    public void uploadSubtree(Path baseDir, Stream<FileWrapper.FolderUploadProperties> directories) {
        FileWrapper base = context.getByPath(baseDir).join().get();
        Optional<BatId> mirrorBat = base.mirrorBatId();
        base.uploadSubtree(directories, mirrorBat, context.network, context.crypto, context.getTransactionService(), x -> Futures.of(false), () -> true).join();
    }

    @Override
    public Blake3state hashFile(Path p, Optional<FileWrapper> meta) {
        FileWrapper f = meta.orElseGet(() -> context.getByPath(p).join().get());
        FileProperties props = f.getFileProperties();
        if (props.hash.isPresent())
            return props.hash.get();

        byte[] buf = new byte[4 * 1024];
        Blake3 state = Blake3.initHash();

        long size = f.getSize();
        AsyncReader reader = f.getInputStream(context.network, context.crypto, x -> {}).join();
        for (long i = 0; i < size; ) {
            int read = reader.readIntoArray(buf, 0, (int)Math.min(buf.length, size - i)).join();
            state.update(buf, 0, read);
            i += read;
        }

        byte[] hash = state.doFinalize(32);
        return new Blake3state(hash);
    }

    @Override
    public void applyToSubtree(Path start, Consumer<FileProps> onFile, Consumer<Path> onDir) {
        FileWrapper base = context.getByPath(start).join().get();
        applyToSubtree(start, base, onFile, onDir);

    }

    private void applyToSubtree(Path basePath, FileWrapper base, Consumer<FileProps> onFile, Consumer<Path> onDir) {
        Set<FileWrapper> children = base.getChildren(base.version, context.crypto.hasher, context.network, false).join();
        for (FileWrapper child : children) {
            Path childPath = basePath.resolve(child.getName());
            if (! child.isDirectory()) {
                onFile.accept(new FileProps(childPath,
                        child.getFileProperties().modified.toInstant(ZoneOffset.UTC).toEpochMilli() / 1000 * 1000,
                        child.getSize(), Optional.of(child)));
            } else {
                onDir.accept(childPath);
                applyToSubtree(childPath, child, onFile, onDir);
            }
        }
    }
}
