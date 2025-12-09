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

/** A local directory implementation of ContentAddressedStorage.
 *
 */
public class FileContentAddressedStorage implements DeletableContentAddressedStorage {
    private static final Logger LOG = Logging.LOG();
    private static final int CID_V1 = 1;
    private final Path root;
    private final TransactionStore transactions;
    private final BlockRequestAuthoriser authoriser;
    private final Hasher hasher;
    private final Cid ourId;
    private CoreNode pki;

    public FileContentAddressedStorage(Path root,
                                       Cid ourId,
                                       TransactionStore transactions,
                                       BlockRequestAuthoriser authoriser,
                                       Hasher hasher) {
        this.root = root;
        this.ourId = ourId;
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
    public void setPki(CoreNode pki) {
        this.pki = pki;
    }

    @Override
    public ContentAddressedStorage directToOrigin() {
        return this;
    }

    @Override
    public CompletableFuture<Cid> id() {
        return CompletableFuture.completedFuture(ourId);
    }

    @Override
    public CompletableFuture<List<Cid>> ids() {
        return CompletableFuture.completedFuture(List.of(ourId));
    }

    @Override
    public CompletableFuture<String> linkHost(PublicKeyHash owner) {
        return Futures.of("localhost:8000");
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
    public CompletableFuture<List<byte[]>> getChampLookup(PublicKeyHash owner, Cid root, List<ChunkMirrorCap> caps, Optional<Cid> committedRoot) {
        if (! hasBlock(owner, root))
            return Futures.errored(new IllegalStateException("Champ root not present locally: " + root));
        return getChampLookup(owner, root, caps, committedRoot, hasher);
    }

    @Override
    public List<Cid> getOpenTransactionBlocks(PublicKeyHash owner) {
        return transactions.getOpenTransactionBlocks(owner);
    }

    @Override
    public void clearOldTransactions(PublicKeyHash owner, long cutoffMillis) {
        transactions.clearOldTransactions(owner, cutoffMillis);
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

    private Path getFilePath(PublicKeyHash owner, Cid h) {
        String key = DirectS3BlockStore.hashToKey(h);

        Path path = PathUtil.get("")
                .resolve(pki.getUsername(owner).join())
                .resolve(key.substring(key.length() - 3, key.length() - 1))
                .resolve(key + ".data");
        return path;
    }

    private static Path getLegacyFilePath(Cid h) {
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
    public CompletableFuture<Optional<CborObject>> get(List<Multihash> peerIds, PublicKeyHash owner, Cid hash, String auth, boolean persistBlock) {
        if (hash.codec == Cid.Codec.Raw)
            throw new IllegalStateException("Need to call getRaw if cid is not cbor!");
        return getRaw(Collections.emptyList(), owner, hash, auth, persistBlock).thenApply(opt -> opt.map(CborObject::fromByteArray));
    }

    @Override
    public CompletableFuture<Optional<CborObject>> get(PublicKeyHash owner, Cid hash, Optional<BatWithId> bat) {
        return get(Collections.emptyList(), owner, hash, bat, id().join(), hasher, false);
    }

    @Override
    public CompletableFuture<Optional<byte[]>> getRaw(List<Multihash> peerIds,
                                                      PublicKeyHash owner,
                                                      Cid hash,
                                                      Optional<BatWithId> bat,
                                                      Cid ourId,
                                                      Hasher h,
                                                      boolean doAuth,
                                                      boolean persistBlock) {
        if (hash.isIdentity())
            return Futures.of(Optional.of(hash.getHash()));
        Path path = getFilePath(owner, hash);
        File file = root.resolve(path).toFile();
        if (!file.exists()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        try (DataInputStream din = new DataInputStream(new BufferedInputStream(new FileInputStream(file)))) {
            byte[] block = Serialize.readFully(din);

            String auth = bat.isEmpty() ? "" :
                    bat.get().bat.generateAuth(hash, ourId, 300, S3Request.currentDatetime(), bat.get().id, h)
                    .thenApply(BlockAuth::encode).join();
            if (! authoriser.allowRead(hash, block, id().join(), auth).join())
                return Futures.errored(new IllegalStateException("Unauthorised!"));
            return CompletableFuture.completedFuture(Optional.of(block));
        } catch (IOException e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    @Override
    public CompletableFuture<Optional<byte[]>> getRaw(PublicKeyHash owner, Cid hash, Optional<BatWithId> bat) {
        try {
            if (hash.isIdentity())
                return Futures.of(Optional.of(hash.getHash()));
            Path path = getFilePath(owner, hash);
            File file = root.resolve(path).toFile();
            if (! file.exists()){
                return CompletableFuture.completedFuture(Optional.empty());
            }
            try (DataInputStream din = new DataInputStream(new BufferedInputStream(new FileInputStream(file)))) {
                byte[] block = Serialize.readFully(din);

                String auth = bat.isEmpty() ? "" :
                        bat.get().bat.generateAuth(hash, ourId, 300, S3Request.currentDatetime(), bat.get().id, hasher)
                                .thenApply(BlockAuth::encode).join();
                if (! authoriser.allowRead(hash, block, id().join(), auth).join())
                    return Futures.errored(new IllegalStateException("Unauthorised!"));
                return CompletableFuture.completedFuture(Optional.of(block));
            }
        } catch (IOException e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    @Override
    public CompletableFuture<Optional<byte[]>> getRaw(List<Multihash> peerIds, PublicKeyHash owner, Cid hash, String auth, boolean persistBlock) {
        return getRaw(peerIds, owner, hash, auth, true, persistBlock);
    }

    @Override
    public CompletableFuture<Optional<byte[]>> getRaw(List<Multihash> peerIds, PublicKeyHash owner, Cid hash, String auth, boolean doAuth, boolean persistBlock) {
        try {
            if (hash.isIdentity())
                return Futures.of(Optional.of(hash.getHash()));
            Path path = getFilePath(owner, hash);
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
    public boolean hasBlock(PublicKeyHash owner, Cid hash) {
        Path path = getFilePath(owner, hash);
        File file = root.resolve(path).toFile();
        return file.exists();
    }

    @Override
    public CompletableFuture<BlockMetadata> getBlockMetadata(PublicKeyHash owner, Cid block) {
        return getRaw(Arrays.asList(id().join()), owner, block, Optional.empty(), ourId, hasher, true)
                .thenApply(rawOpt -> BlockMetadataStore.extractMetadata(block, rawOpt.get()));
    }

    @Override
    public CompletableFuture<List<Cid>> getLinks(PublicKeyHash owner, Cid root, List<Multihash> peerids) {
        if (root.codec == Cid.Codec.Raw)
            return CompletableFuture.completedFuture(Collections.emptyList());
        return getRaw(peerids, owner, root, Optional.empty(), ourId, hasher, false, false)
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
            Path filePath = getFilePath(owner, cid);
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

    protected List<Pair<PublicKeyHash, Cid>> getFiles(Optional<PublicKeyHash> owner) {
        List<Pair<PublicKeyHash, Cid>> existing = new ArrayList<>();
        getFilesRecursive(owner.map(o -> root.resolve(o.toString())).orElse(root), (o, c) -> existing.add(new Pair<>(o, c)), root);
        return existing;
    }

    @Override
    public CompletableFuture<Optional<Integer>> getSize(PublicKeyHash owner, Multihash h) {
        Path path = getFilePath(owner, (Cid)h);
        File file = root.resolve(path).toFile();
        return CompletableFuture.completedFuture(file.exists() ? Optional.of((int) file.length()) : Optional.empty());
    }

    @Override
    public CompletableFuture<IpnsEntry> getIpnsEntry(Multihash signer) {
        throw new IllegalStateException("Unimplemented!");
    }

    @Override
    public Stream<Pair<PublicKeyHash, Cid>> getAllBlockHashes(boolean useBlockstore) {
        return getFiles(Optional.empty()).stream();
    }

    @Override
    public Stream<Pair<PublicKeyHash, Cid>> getAllBlockHashes(PublicKeyHash owner, boolean useBlockstore) {
        return getFiles(Optional.of(owner)).stream();
    }

    @Override
    public void getAllBlockHashVersions(PublicKeyHash owner, Consumer<List<BlockVersion>> res) {
        res.accept(getAllBlockHashes(owner, false)
                .map(p -> new BlockVersion(p.right, null, true))
                .collect(Collectors.toList()));
    }

    @Override
    public void delete(PublicKeyHash owner, Cid h) {
        Path path = getFilePath(owner, h);
        File file = root.resolve(path).toFile();
        if (file.exists())
            file.delete();
    }

    public void applyToAll(BiConsumer<PublicKeyHash, Cid> processor) {
        getFilesRecursive(root, processor, root);
    }

    public static void getFilesRecursive(Path path, BiConsumer<PublicKeyHash, Cid> accumulator, Path root) {
        File pathFile = path.toFile();
        if (pathFile.isFile()) {
            if (pathFile.getName().endsWith(".data")) {
                String name = pathFile.getName();
                Path fromRoot = path.relativize(root);
                int nameCount = fromRoot.getNameCount();
                PublicKeyHash owner = nameCount > 2 ?
                        PublicKeyHash.fromString(fromRoot.getName(0).toString()) :
                        null;
                accumulator.accept(owner, DirectS3BlockStore.keyToHash(name.substring(0, name.length() - 5)));
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
                getFilesRecursive(child, accumulator, root);
            } else if (filename.endsWith(".data")) {
                try {
                    String name = child.toFile().getName();
                    Path fromRoot = path.relativize(root);
                    int nameCount = fromRoot.getNameCount();
                    PublicKeyHash owner = nameCount > 2 ?
                            PublicKeyHash.fromString(fromRoot.getName(0).toString()) :
                            null;
                    accumulator.accept(owner, DirectS3BlockStore.keyToHash(name.substring(0, name.length() - 5)));
                } catch (IllegalStateException e) {
                    // ignore files who's name isn't a valid multihash
                    LOG.info("Ignoring file "+ child +" since name is not of form $cid.data");
                }
            }
        }
    }

    public void migrateToOwnerPartitionedStore() {

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
    public Optional<BlockCache> getBlockCache() {
        return Optional.empty();
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
        getFilesRecursive(root,
                (Owner, cid) -> {
                    if (cid.codec == Cid.Codec.DagProtobuf) {
                        // move block
                        String filename = DirectS3BlockStore.hashToKey(cid) + ".data";
                        Path path = getLegacyFilePath(cid);
                        try {
                            Files.move(root.resolve(path), targetDir.resolve(filename), StandardCopyOption.REPLACE_EXISTING);
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    }
                },
                root);
    }
}
