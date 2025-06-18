package peergos.server.storage;

import peergos.server.util.Logging;
import peergos.server.util.Threads;
import peergos.shared.io.ipfs.Cid;
import peergos.shared.io.ipfs.Multihash;
import peergos.shared.io.ipfs.api.JSONParser;
import peergos.shared.storage.*;
import peergos.shared.util.*;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import java.util.logging.*;

/** A local file based block cache LRU
 *
 */
public class FileBlockCache implements BlockCache {
    private static final Logger LOG = Logging.LOG();
    private final Path root;
    private volatile long maxSizeBytes;
    private long lastSizeCheckTime = 0;
    private AtomicLong totalSize = new AtomicLong(0);
    private AtomicBoolean needToCommitSize = new AtomicBoolean(true);
    private final SecureRandom rnd = new SecureRandom();

    public FileBlockCache(Path root, long maxSizeBytes) {
        this.root = root;
        this.maxSizeBytes = getOrSetMaxSize(maxSizeBytes);
        File rootDir = root.toFile();
        if (!rootDir.exists()) {
            final boolean mkdirs = root.toFile().mkdirs();
            if (!mkdirs)
                throw new IllegalStateException("Unable to create directory " + root);
        }
        if (!rootDir.isDirectory())
            throw new IllegalStateException("File store path must be a directory! " + root);

        File sizeFile = root.resolve("size.bin").toFile();
        if (sizeFile.exists()) {
            try {
                DataInputStream din = new DataInputStream(new FileInputStream(sizeFile));
                long size = din.readLong();
                din.close();
                totalSize.set(size);
                LOG.info("Loaded file block cache size from disk: " + totalSize.get() / 1024 / 1024 + " MiB");
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        } else {
            LOG.info("Listing file block cache...");
            long t0 = System.currentTimeMillis();
            applyToAll((p, a) -> totalSize.addAndGet(a.size()));
            long t1 = System.currentTimeMillis();
            LOG.info("Finished listing file block cache in " + (t1 - t0) / 1000 + "s, total size " + totalSize.get() / 1024 / 1024 + " MiB");
        }
        ForkJoinPool.commonPool().submit(() -> ensureWithinSizeLimit(maxSizeBytes));
        Thread sizeCommitter = new Thread(this::sizeCommitter, "FileBlockCache size");
        sizeCommitter.setDaemon(true);
        sizeCommitter.start();
    }

    private long getOrSetMaxSize(long maxSizeBytes) {
        Path json = root.resolve("config.json");
        try {
            if (json.toFile().exists()) {
                Map<String, Object> decoded = (Map<String, Object>) JSONParser.parse(new String(Files.readAllBytes(json)));
                Object maxsize = decoded.get("maxsize");
                if (maxsize instanceof Integer)
                    return (Integer) maxsize;
                return (Long) maxsize;
            } else {
                json.getParent().toFile().mkdirs();
                Files.write(json, ("{\"maxsize\":" + maxSizeBytes + "}").getBytes("UTF-8"), StandardOpenOption.CREATE);
                return maxSizeBytes;
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public long getMaxSize() {
        return maxSizeBytes;
    }

    @Override
    public void setMaxSize(long maxSizeBytes) {
        this.maxSizeBytes = maxSizeBytes;
        Path json = root.resolve("config.json");
        try {
            Files.write(json, ("{\"maxsize\":" + maxSizeBytes + "}").getBytes("UTF-8"));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void sizeCommitter() {
        while (true) {
            if (needToCommitSize.get()) {
                try {
                    File sizeFile = root.resolve("size.bin").toFile();
                    DataOutputStream dout = new DataOutputStream(new FileOutputStream(sizeFile));
                    dout.writeLong(totalSize.get());
                    dout.flush();
                    dout.close();
                    needToCommitSize.set(false);
                } catch (Exception e) {
                    LOG.log(Level.WARNING, e, () -> e.getMessage());
                }
            }
            Threads.sleep(30_000);
        }
    }

    private Path getFilePath(Cid h) {
        String key = DirectS3BlockStore.hashToKey(h);

        Path path = PathUtil.get("")
                .resolve(key.substring(key.length() - 3, key.length() - 1))
                .resolve(key + ".data");
        return path;
    }

    /**
     * Remove all files stored as part of this FileContentAddressedStorage.
     */
    public void remove() {
        root.toFile().delete();
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
            if (target.toFile().exists())
                return Futures.of(true);
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
            Path tmp = target.getParent().resolve(target.getFileName() + "-" + rnd.nextInt(Integer.MAX_VALUE) + ".tmp");
            Files.write(tmp, data, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
            totalSize.addAndGet(data.length);
            if (lastSizeCheckTime < System.currentTimeMillis() - 30_000) {
                lastSizeCheckTime = System.currentTimeMillis();
                ForkJoinPool.commonPool().submit(() -> ensureWithinSizeLimit(maxSizeBytes));
            }
            needToCommitSize.set(true);
            return Futures.of(true);
        } catch (IOException e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    protected List<Cid> getFiles() {
        List<Cid> existing = new ArrayList<>();
        FileContentAddressedStorage.getFilesRecursive(root, existing::add);
        return existing;
    }

    public CompletableFuture<Optional<Integer>> getSize(Multihash h) {
        Path path = getFilePath((Cid)h);
        File file = root.resolve(path).toFile();
        return CompletableFuture.completedFuture(file.exists() ? Optional.of((int) file.length()) : Optional.empty());
    }

    @Override
    public CompletableFuture<Boolean> clear() {
        applyToAll((p, a) -> delete(p.toFile()));
        return Futures.of(true);
    }

    public void delete(Multihash h) {
        Path path = getFilePath((Cid)h);
        File file = root.resolve(path).toFile();
        delete(file);
    }

    private void delete(File file) {
        if (file.exists()) {
            long size = file.length();
            file.delete();
            totalSize.addAndGet(-size);
            Path parent = file.toPath().getParent();
            File[] files = parent.toFile().listFiles();
            if (files != null && files.length == 0) {
                parent.toFile().delete();
            }
        }
    }

    public Optional<Long> getLastAccessTimeMillis(Cid h) {
        Path path = getFilePath(h);
        File file = root.resolve(path).toFile();
        if (! file.exists())
            return Optional.empty();
        try {
            BasicFileAttributes attrs = Files.readAttributes(root.resolve(path), BasicFileAttributes.class);
            FileTime time = attrs.lastAccessTime();
            return Optional.of(time.toMillis());
        } catch (NoSuchFileException nope) {
            return Optional.empty();
        } catch (IOException e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    public void applyToAll(BiConsumer<Path, BasicFileAttributes> processor) {
        try {
            Files.walkFileTree(root, new FileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path path, BasicFileAttributes attr) throws IOException {
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path path, BasicFileAttributes attr) throws IOException {
                    if (path.getFileName().toString().endsWith(".data")) {
                        processor.accept(path, attr);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path path, IOException e) throws IOException {
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path path, IOException e) throws IOException {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private AtomicBoolean cleaning = new AtomicBoolean(false);

    public void ensureWithinSizeLimit(long maxSize) {
        if (totalSize.get() <= maxSize/2 || cleaning.get())
            return;
        if (! cleaning.compareAndSet(false, true))
            return;
        // delete files randomly, don't bother trying to sort by access time
        Logging.LOG().info("Starting FileBlockCache reduction from " + totalSize.get());
        applyToAll((p, a) -> {
            if (totalSize.get() > maxSize / 2) {
                delete(p.toFile());
                totalSize.addAndGet(-a.size());
            }
        });
        Logging.LOG().info("Reduced FileBlockCache down to " + totalSize.get());
        cleaning.set(false);
    }
}
