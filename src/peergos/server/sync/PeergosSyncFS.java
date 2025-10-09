package peergos.server.sync;

import peergos.server.util.Logging;
import peergos.shared.crypto.hash.PublicKeyHash;
import peergos.shared.storage.auth.Bat;
import peergos.shared.storage.auth.BatId;
import peergos.shared.user.UserContext;
import peergos.shared.user.fs.*;
import peergos.shared.user.fs.cryptree.CryptreeNode;
import peergos.shared.util.Futures;
import peergos.shared.util.Pair;
import peergos.shared.util.PathUtil;
import peergos.shared.util.Triple;

import java.io.IOException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class PeergosSyncFS implements SyncFilesystem {
    private static final Logger LOG = Logging.LOG();

    private final UserContext context;
    private final Path root;

    public PeergosSyncFS(UserContext context, Path root) {
        this.context = context;
        this.root = root;
    }

    @Override
    public long totalSpace() throws IOException {
        throw new IllegalStateException("Unimplemented!");
    }

    @Override
    public long freeSpace() throws IOException {
        throw new IllegalStateException("Unimplemented!");
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
        return context.getByPath(root.resolve(p)).join().isPresent();
    }

    @Override
    public void mkdirs(Path p) {
        if (p == null) // base dir
            return;
        Optional<BatId> mirrorBat = context.mirrorBatId();
        if (exists(p))
            return;
        mkdirs(p.getParent());
        FileWrapper parent = context.getByPath(root.resolve(p).getParent()).join().get();
        parent.mkdir(p.getFileName().toString(), context.network, false, mirrorBat, context.crypto).join();
    }

    @Override
    public void delete(Path p) {
        Optional<FileWrapper> parentOpt = context.getByPath(root.resolve(p)).join();
        if (parentOpt.isEmpty())
            return;
        FileWrapper f = parentOpt.get();
        if (f.isDirectory() && f.hasChildren(context.network).join())
            throw new IllegalStateException("Trying to delete non empty directory: " + p);
        FileWrapper parent = context.getByPath(root.resolve(p).getParent()).join().get();
        f.remove(parent, root.resolve(p), context).join();
    }

    @Override
    public void bulkDelete(Path dir, Set<String> children) {
        Optional<FileWrapper> parentOpt = context.getByPath(root.resolve(dir)).join();
        if (parentOpt.isEmpty())
            return;
        FileWrapper parent = parentOpt.get();
        Set<FileWrapper> kids = parent.getChildren(children, context.crypto.hasher, context.network, false).join();
        FileWrapper.deleteChildren(parent, kids, dir, context).join();
    }

    @Override
    public void moveTo(Path src, Path target) {
        if (Objects.equals(target.getParent(), src.getParent())) { // rename
            Optional<FileWrapper> parentOpt = context.getByPath(root.resolve(src).getParent()).join();
            if (parentOpt.isEmpty())
                throw new IllegalStateException("Couldn't retrieve " + root.resolve(src).getParent());
            FileWrapper parent = parentOpt.get();
            Optional<FileWrapper> srcOpt = context.getByPath(root.resolve(src)).join();
            if (srcOpt.isEmpty())
                throw new IllegalStateException("Couldn't retrieve " + root.resolve(src));
            FileWrapper from = srcOpt.get();
            from.rename(target.getFileName().toString(), parent, root.resolve(src), context).join();
        } else {
            Optional<FileWrapper> newParent = context.getByPath(root.resolve(target).getParent()).join();
            if (newParent.isEmpty()) {
                mkdirs(target.getParent());
                newParent = context.getByPath(root.resolve(target).getParent()).join();
            }
            Optional<FileWrapper> srcOpt = context.getByPath(root.resolve(src)).join();
            if (srcOpt.isEmpty())
                throw new IllegalStateException("Couldn't retrieve " + root.resolve(src));
            FileWrapper from = srcOpt.get();
            Optional<FileWrapper> parentOpt = context.getByPath(root.resolve(src).getParent()).join();
            if (parentOpt.isEmpty())
                throw new IllegalStateException("Couldn't retrieve " + root.resolve(src).getParent());
            FileWrapper parent = parentOpt.get();
            from.moveTo(newParent.get(), parent, root.resolve(src), context, () -> Futures.of(true));
        }
    }

    @Override
    public long getLastModified(Path p) {
        Optional<FileWrapper> file = context.getByPath(root.resolve(p)).join();
        if (file.isEmpty())
            throw new IllegalStateException("Couldn't retrieve file modification time for " + root.resolve(p));
        LocalDateTime modified = file.get().getFileProperties().modified;
        return modified.toInstant(ZoneOffset.UTC).toEpochMilli() / 1000 * 1000;
    }

    @Override
    public void setModificationTime(Path p, long t) {
        FileWrapper f = context.getByPath(root.resolve(p)).join().get();
        LocalDateTime newModified = LocalDateTime.ofInstant(Instant.ofEpochSecond(t / 1000, 0), ZoneOffset.UTC);
        Optional<FileWrapper> parent = context.getByPath(root.resolve(p).getParent()).join();
        f.setProperties(f.getFileProperties().withModified(newModified), context.crypto.hasher, context.network, parent).join();
    }

    @Override
    public void setHash(Path p, HashTree hashTree, long fileSize) {
        FileWrapper f = context.getByPath(root.resolve(p)).join().get();
        Optional<FileWrapper> parent = context.getByPath(root.resolve(p).getParent()).join();
        FileProperties withHash = f.getFileProperties().withHash(Optional.of(hashTree.branch(0)));
        f.setProperties(withHash, context.crypto.hasher, context.network, parent).join();
        long nBranches = (fileSize + 1024 * Chunk.MAX_SIZE - 1) / (1024 * Chunk.MAX_SIZE);
        for (long b = 1; b < nBranches; b++) {
            WritableAbsoluteCapability cap = f.writableFilePointer();
            Pair<byte[], Optional<Bat>> loc = FileProperties.calculateMapKey(withHash.streamSecret.get(),
                    cap.getMapKey(), cap.bat, b * 1024 * Chunk.MAX_SIZE, context.crypto.hasher).join();
            WritableAbsoluteCapability chunkCap = cap.withMapKey(loc.left, loc.right);
            long chunkIndex = b * 1024;
            context.network.synchronizer.applyComplexUpdate(f.owner(), f.signingPair(),
                    (s, c) -> {
                        CryptreeNode meta = context.network.getMetadata(s.get(f.writer()), chunkCap).join().get();
                        return meta.updateProperties(s, c, chunkCap, Optional.of(f.signingPair()), meta.getProperties(chunkCap.rBaseKey)
                                .withHash(Optional.of(hashTree.branch(chunkIndex))), context.network);
                    }, () -> true).join();
        }
    }

    @Override
    public void setHashes(List<Triple<String, FileWrapper, HashTree>> toUpdate) {
        List<FileWrapper.PropsUpdate> hashUpdates = toUpdate.stream()
                .flatMap(p -> p.middle.getHashUpdates(p.right, context.network, context.crypto.hasher).join().stream())
                .collect(Collectors.toList());
        FileWrapper.bulkSetSameNameProperties(hashUpdates, context.network).join();
    }

    @Override
    public long size(Path p) {
        Optional<FileWrapper> file = context.getByPath(root.resolve(p)).join();
        if (file.isEmpty())
            throw new IllegalStateException("Couldn't retrieve file size for " + p);
        return file.get().getFileProperties().size;
    }

    @Override
    public void truncate(Path p, long size) throws IOException {
        FileWrapper f = context.getByPath(root.resolve(p)).join().get();
        f.truncate(size, context.network, context.crypto).join();
    }

    @Override
    public Optional<LocalDateTime> setBytes(Path p,
                                            long fileOffset,
                                            AsyncReader data,
                                            long size,
                                            Optional<HashTree> hash,
                                            Optional<LocalDateTime> modificationTime,
                                            Optional<Thumbnail> thumbnail,
                                            ResumeUploadProps props,
                                            Supplier<Boolean> isCancelled,
                                            Consumer<String> progress) throws IOException {
        Optional<FileWrapper> existing = context.getByPath(root.resolve(p)).join();
        String filename = p.getFileName().toString();
        if (existing.isEmpty() && fileOffset == 0) {
            Optional<FileWrapper> parentOpt = context.getByPath(root.resolve(p).getParent()).join();
            if (parentOpt.isEmpty()) {
                mkdirs(p.getParent());
                parentOpt = context.getByPath(root.resolve(p).getParent()).join();
            }
            FileWrapper parent = parentOpt.get();
            AtomicLong done = new AtomicLong(0);
            parent.uploadFileWithHash(filename, data, size, hash, modificationTime, thumbnail,
                    Optional.of(props),
                    context.network, context.crypto, isCancelled, x -> {
                        long total = done.addAndGet(x);
                        if (total >= 1024*1024)
                            progress.accept("Uploaded " + (total/1024/1024) + " / " + (size / 1024/1024) + " MiB of " + filename);
                    }).join();
        } else {
            FileWrapper f = existing.get();
            if (f.isDirty()) {
                FileWrapper ff = f;
                context.network.synchronizer.applyComplexUpdate(f.owner(), f.signingPair(), (v, c) -> ff.clean(v, c, context.network, context.crypto)
                        .thenApply(r -> r.right)).join();
                f = context.getByPath(root.resolve(p)).join().get();
            }

            long end = fileOffset + size;
            AtomicLong done = new AtomicLong(0);
            f.overwriteSectionJS(data, (int) (fileOffset >>> 32), (int) fileOffset, (int) (end >>> 32), (int) end, modificationTime, context.network, context.crypto, x -> {
                long total = done.addAndGet(x);
                if (total >= 1024*1024)
                    progress.accept("Uploaded " + (total/1024/1024) + " / " + (size / 1024/1024) + " MiB of " + filename);
            }).join();
        }
        return modificationTime;
    }

    @Override
    public AsyncReader getBytes(Path p, long fileOffset) throws IOException {
        Optional<FileWrapper> file = context.getByPath(root.resolve(p)).join();
        if (file.isEmpty())
            throw new IllegalStateException("Couldn't retrieve " + root.resolve(p));
        FileWrapper f = file.get();
        AsyncReader reader = f.getInputStream(context.network, context.crypto, x -> {}).join();
        return reader.seek(fileOffset).join();
    }

    @Override
    public void uploadSubtree(Stream<FileWrapper.FolderUploadProperties> directories) {
        FileWrapper base = context.getByPath(root).join().get();
        Optional<BatId> mirrorBat = base.mirrorBatId();
        base.uploadSubtree(directories, mirrorBat, context.network, context.crypto, context.getTransactionService(), x -> Futures.of(false), () -> true).join();
    }

    @Override
    public Optional<Thumbnail> getThumbnail(Path p) {
        return Optional.empty();
    }

    @Override
    public HashTree hashFile(Path p, Optional<FileWrapper> meta, String relativePath, SyncState syncedVersions) {
        FileWrapper f = meta.orElseGet(() -> context.getByPath(root.resolve(p)).join().get());
        FileProperties props = f.getFileProperties();
        if (props.treeHash.isPresent()) {
            FileState synced = syncedVersions.byPath(relativePath);
            HashBranch branch = props.treeHash.get();
            if (synced != null && synced.hashTree.rootHash.equals(branch.rootHash))
                return synced.hashTree;
            if (props.size < 1024L * Chunk.MAX_SIZE)
                return new HashTree(branch.rootHash, branch.level1.map(List::of)
                        .orElseThrow(() -> new IllegalStateException("Invalid hash branch")),
                        Collections.emptyList(),
                        Collections.emptyList());
        }

        byte[] buf = new byte[4 * 1024];

        long size = f.getSize();
        AsyncReader reader = f.getInputStream(context.network, context.crypto, x -> {}).join();
        int chunkOffset = 0;
        List<byte[]> chunkHashes = new ArrayList<>();
        try {
            MessageDigest chunkHash = MessageDigest.getInstance("SHA-256");

            for (long i = 0; i < size; ) {
                int read = reader.readIntoArray(buf, 0, (int) Math.min(buf.length, size - i)).join();
                chunkOffset += read;
                if (chunkOffset >= Chunk.MAX_SIZE) {
                    int thisChunk = read - chunkOffset + Chunk.MAX_SIZE;
                    chunkHash.update(buf, 0, thisChunk);
                    chunkHashes.add(chunkHash.digest());
                    chunkHash = MessageDigest.getInstance("SHA-256");
                    chunkOffset = 0;
                } else
                    chunkHash.update(buf, 0, read);
                i += read;
            }
            if (size == 0 || chunkOffset % Chunk.MAX_SIZE != 0)
                chunkHashes.add(chunkHash.digest());

            return HashTree.build(chunkHashes, context.crypto.hasher).join();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public long filesCount() throws IOException {
        throw new IllegalStateException("Unimplemented file count.");
    }

    @Override
    public Optional<PublicKeyHash> applyToSubtree(Consumer<FileProps> onFile, Consumer<FileProps> onDir) {
        return applyToSubtree(root, onFile, onDir);
    }

    private Optional<PublicKeyHash> applyToSubtree(Path start, Consumer<FileProps> onFile, Consumer<FileProps> onDir) {
        Optional<FileWrapper> baseDir = context.getByPath(start).join();
        if (baseDir.isEmpty())
            throw new IllegalStateException("Couldn't retrieve Peergos base directory!");
        applyToSubtree(start, baseDir.get(), onFile, onDir);
        return Optional.of(baseDir.get().getLinkPointer().capability.writer);
    }

    private void applyToSubtree(Path basePath, FileWrapper base, Consumer<FileProps> onFile, Consumer<FileProps> onDir) {
        Set<FileWrapper> children = base.getChildren(base.version, context.crypto.hasher, context.network, false).join();
        for (FileWrapper child : children) {
            Path childPath = basePath.resolve(child.getName());
            FileProps childProps = new FileProps(root.relativize(childPath).normalize().toString().replaceAll("\\\\", "/"),
                    child.getFileProperties().modified.toInstant(ZoneOffset.UTC).toEpochMilli() / 1000 * 1000,
                    child.getSize(), Optional.of(child));
            if (! child.isDirectory()) {
                onFile.accept(childProps);
            } else {
                onDir.accept(childProps);
                applyToSubtree(childPath, child, onFile, onDir);
            }
        }
    }
}
