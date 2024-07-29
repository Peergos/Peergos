package peergos.server.storage;

import peergos.server.util.Logging;
import peergos.shared.io.ipfs.*;
import peergos.shared.storage.*;
import peergos.shared.util.*;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import java.util.logging.*;

/** A local file based block cache LRU
 *
 */
public class FileBlockBuffer implements BlockBuffer {
    private static final Logger LOG = Logging.LOG();
    private final Path root;
    public FileBlockBuffer(Path root) {
        this.root = root;
        File rootDir = root.toFile();
        if (!rootDir.exists()) {
            final boolean mkdirs = root.toFile().mkdirs();
            if (!mkdirs)
                throw new IllegalStateException("Unable to create directory " + root);
        }
        if (!rootDir.isDirectory())
            throw new IllegalStateException("File store path must be a directory! " + root);
    }

    private Path getFilePath(Cid h) {
        String key = DirectS3BlockStore.hashToKey(h);

        Path path = PathUtil.get("")
                .resolve(key.substring(key.length() - 3, key.length() - 1))
                .resolve(key + ".data");
        return path;
    }

    @Override
    public CompletableFuture<Optional<byte[]>> get(Cid hash) {
        try {
            if (hash.isIdentity())
                return Futures.of(Optional.of(hash.getHash()));
            Path path = getFilePath(hash);
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
    public boolean hasBlock(Cid hash) {
        Path path = getFilePath(hash);
        File file = root.resolve(path).toFile();
        return file.exists();
    }

    public CompletableFuture<Boolean> put(Cid hash, byte[] data) {
        try {
            Path filePath = getFilePath(hash);
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
            Files.write(target, data, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            return Futures.of(true);
        } catch (IOException e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    public CompletableFuture<Optional<Integer>> getSize(Multihash h) {
        Path path = getFilePath((Cid)h);
        File file = root.resolve(path).toFile();
        return CompletableFuture.completedFuture(file.exists() ? Optional.of((int) file.length()) : Optional.empty());
    }

    public CompletableFuture<Boolean> delete(Cid h) {
        Path path = getFilePath(h);
        File file = root.resolve(path).toFile();
        if (file.exists())
            file.delete();
        return Futures.of(true);
    }

    public void applyToAll(Consumer<Cid> processor) {
        FileContentAddressedStorage.getFilesRecursive(root, processor);
    }
}
