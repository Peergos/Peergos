package peergos.server.storage;

import peergos.server.storage.auth.*;
import peergos.server.util.Logging;
import peergos.shared.cbor.*;
import peergos.shared.corenode.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.Cid;
import peergos.shared.io.ipfs.Multihash;
import peergos.shared.storage.*;
import peergos.shared.storage.auth.*;
import peergos.shared.user.fs.*;
import peergos.shared.util.*;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.logging.*;
import java.util.stream.*;

/** A local directory implementation of ContentAddressedStorage. Only used for testing.
 *
 */
public class FileContentAddressedStorage implements DeletableContentAddressedStorage {
    private static final Logger LOG = Logging.LOG();
    private static final int CID_V1 = 1;
    private final Path root;
    private final TransactionStore transactions;
    private final BlockRequestAuthoriser authoriser;
    private final Hasher hasher;

    public FileContentAddressedStorage(Path root, TransactionStore transactions, BlockRequestAuthoriser authoriser, Hasher hasher) {
        this.root = root;
        this.transactions = transactions;
        this.authoriser = authoriser;
        this.hasher = hasher;
        File rootDir = root.toFile();
        if (!rootDir.exists()) {
            final boolean mkdirs = root.toFile().mkdirs();
            if (!mkdirs)
                throw new IllegalStateException("Unable to create directory " + root);
        }
        if (!rootDir.isDirectory())
            throw new IllegalStateException("File store path must be a directory! " + root);
    }

    @Override
    public void setPki(CoreNode pki) {}

    @Override
    public ContentAddressedStorage directToOrigin() {
        return this;
    }

    @Override
    public CompletableFuture<Cid> id() {
        return CompletableFuture.completedFuture(new Cid(1, Cid.Codec.LibP2pKey, Multihash.Type.sha2_256, RAMStorage.hash("FileStorage".getBytes())));
    }

    @Override
    public CompletableFuture<List<Cid>> ids() {
        return CompletableFuture.completedFuture(List.of(new Cid(1, Cid.Codec.LibP2pKey, Multihash.Type.sha2_256, RAMStorage.hash("FileStorage".getBytes()))));
    }

    @Override
    public CompletableFuture<TransactionId> startTransaction(PublicKeyHash owner) {
        return CompletableFuture.completedFuture(transactions.startTransaction(owner));
    }

    @Override
    public CompletableFuture<Boolean> closeTransaction(PublicKeyHash owner, TransactionId tid) {
        transactions.closeTransaction(owner, tid);
        return CompletableFuture.completedFuture(true);
    }

    @Override
    public CompletableFuture<List<byte[]>> getChampLookup(PublicKeyHash owner, Cid root, byte[] champKey, Optional<BatWithId> bat, Optional<Cid> committedRoot) {
        if (! hasBlock(root))
            return Futures.errored(new IllegalStateException("Champ root not present locally: " + root));
        return getChampLookup(owner, root, champKey, bat, committedRoot, hasher);
    }

    @Override
    public List<Cid> getOpenTransactionBlocks() {
        return transactions.getOpenTransactionBlocks();
    }

    @Override
    public void clearOldTransactions(long cutoffMillis) {
        transactions.clearOldTransactions(cutoffMillis);
    }

    @Override
    public CompletableFuture<List<Cid>> put(PublicKeyHash owner,
                                            PublicKeyHash writer,
                                            List<byte[]> signedHashes,
                                            List<byte[]> blocks,
                                            TransactionId tid) {
        return put(owner, writer, signedHashes, blocks, false, tid);
    }

    @Override
    public CompletableFuture<List<Cid>> putRaw(PublicKeyHash owner,
                                               PublicKeyHash writer,
                                               List<byte[]> signatures,
                                               List<byte[]> blocks,
                                               TransactionId tid,
                                               ProgressConsumer<Long> progressConsumer) {
        return put(owner, writer, signatures, blocks, true, tid);
    }

    private CompletableFuture<List<Cid>> put(PublicKeyHash owner,
                                             PublicKeyHash writer,
                                             List<byte[]> signatures,
                                             List<byte[]> blocks,
                                             boolean isRaw,
                                             TransactionId tid) {
        return CompletableFuture.completedFuture(blocks.stream()
                .map(b -> put(b, isRaw, tid, owner))
                .collect(Collectors.toList()));
    }

    private static Path getFilePath(Cid h) {
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
    public CompletableFuture<Optional<CborObject>> get(List<Multihash> peerIds, Cid hash, String auth) {
        if (hash.codec == Cid.Codec.Raw)
            throw new IllegalStateException("Need to call getRaw if cid is not cbor!");
        return getRaw(Collections.emptyList(), hash, auth).thenApply(opt -> opt.map(CborObject::fromByteArray));
    }

    @Override
    public CompletableFuture<Optional<CborObject>> get(PublicKeyHash owner, Cid hash, Optional<BatWithId> bat) {
        return get(Collections.emptyList(), hash, bat, id().join(), hasher);
    }

    @Override
    public CompletableFuture<Optional<byte[]>> getRaw(PublicKeyHash owner, Cid hash, Optional<BatWithId> bat) {
        return getRaw(Collections.emptyList(), hash, bat, id().join(), hasher);
    }

    @Override
    public CompletableFuture<Optional<byte[]>> getRaw(List<Multihash> peerIds, Cid hash, String auth) {
        return getRaw(peerIds, hash, auth, true);
    }

    @Override
    public CompletableFuture<Optional<byte[]>> getRaw(List<Multihash> peerIds, Cid hash, String auth, boolean doAuth) {
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
                if (doAuth && ! authoriser.allowRead(hash, block, id().join(), auth).join())
                    return Futures.errored(new IllegalStateException("Unauthorised!"));
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

    @Override
    public List<List<Cid>> bulkGetLinks(List<Multihash> peerIds, List<Want> wants) {
        throw new IllegalStateException("Unimplemented!");
    }

    @Override
    public CompletableFuture<List<Cid>> getLinks(Cid root) {
        if (root.codec == Cid.Codec.Raw)
            return CompletableFuture.completedFuture(Collections.emptyList());
        return getRaw(Collections.emptyList(), root, "", false)
                .thenApply(opt -> opt.map(CborObject::fromByteArray))
                .thenApply(opt -> opt
                        .map(cbor -> cbor.links().stream().map(c -> (Cid) c).collect(Collectors.toList()))
                        .orElse(Collections.emptyList())
                );
    }

    public Cid put(byte[] data, boolean isRaw, TransactionId tid, PublicKeyHash owner) {
        try {
            Cid cid = new Cid(CID_V1, isRaw ? Cid.Codec.Raw : Cid.Codec.DagCbor,
                    Multihash.Type.sha2_256, RAMStorage.hash(data));
            Path filePath = getFilePath(cid);
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
                        throw new IllegalStateException("Could not make " + someParent.toString() + ", ancestor of " + parentDir.toString() + " writable");
                }
            }
            transactions.addBlock(cid, tid, owner);
            Files.write(target, data, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            return cid;
        } catch (IOException e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    protected List<Cid> getFiles() {
        List<Cid> existing = new ArrayList<>();
        getFilesRecursive(root, existing::add);
        return existing;
    }

    @Override
    public CompletableFuture<Optional<Integer>> getSize(Multihash h) {
        Path path = getFilePath((Cid)h);
        File file = root.resolve(path).toFile();
        return CompletableFuture.completedFuture(file.exists() ? Optional.of((int) file.length()) : Optional.empty());
    }

    @Override
    public CompletableFuture<IpnsEntry> getIpnsEntry(Multihash signer) {
        throw new IllegalStateException("Unimplemented!");
    }

    @Override
    public Stream<Cid> getAllBlockHashes(boolean useBlockstore) {
        return getFiles().stream();
    }

    @Override
    public void getAllBlockHashVersions(Consumer<List<BlockVersion>> res) {
        res.accept(getAllBlockHashes(false)
                .map(c -> new BlockVersion(c, null, true))
                .collect(Collectors.toList()));
    }

    @Override
    public void delete(Cid h) {
        Path path = getFilePath(h);
        File file = root.resolve(path).toFile();
        if (file.exists())
            file.delete();
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

    public void applyToAll(Consumer<Cid> processor) {
        getFilesRecursive(root, processor);
    }

    public static void getFilesRecursive(Path path, Consumer<Cid> accumulator) {
        File pathFile = path.toFile();
        if (pathFile.isFile()) {
            if (pathFile.getName().endsWith(".data")) {
                String name = pathFile.getName();
                accumulator.accept(DirectS3BlockStore.keyToHash(name.substring(0, name.length() - 5)));
            }
            return;
        }
        else if (!  pathFile.isDirectory())
            throw new IllegalStateException("Specified path "+ path +" is not a file or directory");

        String[] filenames = pathFile.list();
        if (filenames == null)
            throw new IllegalStateException("Couldn't retrieve children of directory: " + path);
        for (String filename : filenames) {
            Path child = path.resolve(filename);
            if (child.toFile().isDirectory()) {
                getFilesRecursive(child, accumulator);
            } else if (filename.endsWith(".data")) {
                try {
                    String name = child.toFile().getName();
                    accumulator.accept(DirectS3BlockStore.keyToHash(name.substring(0, name.length() - 5)));
                } catch (IllegalStateException e) {
                    // ignore files who's name isn't a valid multihash
                    LOG.info("Ignoring file "+ child +" since name is not of form $cid.data");
                }
            }
        }
    }

    public Set<Cid> retainOnly(Set<Cid> pins) {
        List<Cid> existing = getFiles();
        Set<Cid> removed = new HashSet<>();
        for (Cid h : existing) {
            if (! pins.contains(h)) {
                removed.add(h);
                File file = root.resolve(getFilePath(h)).toFile();
                if (file.exists() && !file.delete())
                    LOG.warning("Could not delete " + file);
                File legacy = root.resolve(h.toString()).toFile();
                if (legacy.exists() && ! legacy.delete())
                    LOG.warning("Could not delete " + legacy);
            }
        }
        return removed;
    }

    public boolean contains(Multihash multihash) {
        Path path = getFilePath((Cid)multihash);
        File file = root.resolve(path).toFile();
        if (! file.exists()) { // for backwards compatibility with existing data
            file = root.resolve(path.getFileName()).toFile();
        }
        return file.exists();
    }

    @Override
    public CompletableFuture<EncryptedCapability> getSecretLink(SecretLink link) {
        throw new IllegalStateException("Shouldn't get here.");
    }

    @Override
    public CompletableFuture<LinkCounts> getLinkCounts(String owner, LocalDateTime after, BatWithId mirrorBat) {
        throw new IllegalStateException("Shouldn't get here.");
    }

    @Override
    public String toString() {
        return "FileContentAddressedStorage " + root;
    }

    public static void main(String[] a) throws Exception {
        // run this within the .peergos dir
        Path root = Paths.get(".ipfs/blocks");
        Path targetDir = Paths.get("protobuf-blocks");
        if (! targetDir.toFile().mkdir())
            throw new IllegalStateException("Couldn't create target dir!");
        moveProtobufBlocks(root, targetDir);
    }
    public static void moveProtobufBlocks(Path root, Path targetDir) {
        getFilesRecursive(root, cid -> {
            if (cid.codec == Cid.Codec.DagProtobuf) {
                // move block
                String filename = DirectS3BlockStore.hashToKey(cid) + ".data";
                Path path = getFilePath(cid);
                try {
                    Files.move(root.resolve(path), targetDir.resolve(filename), StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        });
    }
}
