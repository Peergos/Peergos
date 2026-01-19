package peergos.server.storage;

import peergos.server.space.UsageStore;
import peergos.server.util.Logging;
import peergos.shared.corenode.CoreNode;
import peergos.shared.crypto.hash.PublicKeyHash;
import peergos.shared.io.ipfs.*;
import peergos.shared.storage.*;
import peergos.shared.util.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.logging.*;

/** A local file based block cache LRU
 *
 */
public class FileBlockBuffer implements BlockBuffer {
    private static final Logger LOG = Logging.LOG();
    private final Path root;
    private final UsageStore usage;

    public FileBlockBuffer(Path root, UsageStore usage) {
        this.root = root;
        this.usage = usage;
        File rootDir = root.toFile();
        if (!rootDir.exists()) {
            final boolean mkdirs = root.toFile().mkdirs();
            if (!mkdirs)
                throw new IllegalStateException("Unable to create directory " + root);
        }
        if (!rootDir.isDirectory())
            throw new IllegalStateException("File store path must be a directory! " + root);
    }

    public void setPki(CoreNode pki) {
    }

    private Path getFilePath(PublicKeyHash owner, Cid h) {
        String key = DirectS3BlockStore.hashToKey(h);

        Path path = PathUtil.get("")
                .resolve(usage.getUsage(owner).owner)
                .resolve(key.substring(key.length() - 3, key.length() - 1))
                .resolve(key + ".data");
        return path;
    }

    private Path getLegacyFilePath(Cid h) {
        String key = DirectS3BlockStore.hashToKey(h);

        Path path = PathUtil.get("")
                .resolve(key.substring(key.length() - 3, key.length() - 1))
                .resolve(key + ".data");
        return path;
    }

    @Override
    public CompletableFuture<Optional<byte[]>> get(PublicKeyHash owner, Cid hash) {
        try {
            if (hash.isIdentity())
                return Futures.of(Optional.of(hash.getHash()));
            Path path = owner == null ?
                    getLegacyFilePath(hash) :
                    getFilePath(owner, hash);
            File file = root.resolve(path).toFile();
            if (! file.exists()){
                return CompletableFuture.completedFuture(Optional.empty());
            }
            try (DataInputStream din = new DataInputStream(new BufferedInputStream(new FileInputStream(file)))) {
                byte[] block = Serialize.readFully(din);
                return CompletableFuture.completedFuture(Optional.of(block));
            }
        } catch (IOException e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    @Override
    public boolean hasBlock(PublicKeyHash owner, Cid hash) {
        Path path = getFilePath(owner, hash);
        File file = root.resolve(path).toFile();
        return file.exists();
    }

    @Override
    public CompletableFuture<Boolean> put(PublicKeyHash owner, Cid hash, byte[] data) {
        try {
            Path filePath = getFilePath(owner, hash);
            Path target = root.resolve(filePath);
            Path parent = target.getParent();
            File parentDir = parent.toFile();

            if (! parentDir.exists())
                Files.createDirectories(parent);

            for (Path someParent = parent; !someParent.equals(root); someParent = someParent.getParent()) {
                File someParentFile = someParent.toFile();
                if (! someParentFile.canWrite()) {
                    final boolean b = someParentFile.setWritable(true, false);
                    if (!b)
                        throw new IllegalStateException("Could not make " + someParent + ", ancestor of " + parentDir + " writable");
                }
            }
            Path tmp = root.resolve(filePath.getFileName().toString() + ".tmp");
            Files.write(tmp, data, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
            return Futures.of(true);
        } catch (IOException e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    public CompletableFuture<Optional<Integer>> getSize(PublicKeyHash owner, Multihash h) {
        Path path = getFilePath(owner, (Cid)h);
        File file = root.resolve(path).toFile();
        return CompletableFuture.completedFuture(file.exists() ? Optional.of((int) file.length()) : Optional.empty());
    }

    @Override
    public CompletableFuture<Boolean> delete(PublicKeyHash owner, Cid h) {
        Path path = getFilePath(owner, h);
        File file = root.resolve(path).toFile();
        if (file.exists())
            file.delete();
        return Futures.of(true);
    }

    public void applyToAll(BiConsumer<PublicKeyHash, Cid> processor) {
        FileContentAddressedStorage.getFilesRecursive(root, processor, root);
    }
}
