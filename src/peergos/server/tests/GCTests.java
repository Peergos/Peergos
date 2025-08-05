package peergos.server.tests;

import org.junit.*;
import org.peergos.*;
import peergos.server.JavaCrypto;
import peergos.server.corenode.JdbcIpnsAndSocial;
import peergos.server.space.JdbcUsageStore;
import peergos.server.sql.*;
import peergos.server.storage.*;
import peergos.server.storage.auth.Want;
import peergos.server.util.*;
import peergos.shared.Crypto;
import peergos.shared.MaybeMultihash;
import peergos.shared.cbor.*;
import peergos.shared.corenode.CoreNode;
import peergos.shared.crypto.SigningKeyPair;
import peergos.shared.crypto.asymmetric.PublicSigningKey;
import peergos.shared.crypto.hash.PublicKeyHash;
import peergos.shared.io.ipfs.*;
import peergos.shared.mutable.PointerUpdate;
import peergos.shared.storage.*;
import peergos.shared.storage.auth.BatWithId;
import peergos.shared.user.fs.EncryptedCapability;
import peergos.shared.user.fs.SecretLink;
import peergos.shared.util.EfficientHashMap;
import peergos.shared.util.Futures;
import peergos.shared.util.ProgressConsumer;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import java.util.stream.*;

public class GCTests {
    private static final Crypto crypto = JavaCrypto.init();

    private Supplier<Connection> getDb(Path file) throws Exception {
        File storeFile = file.toFile();
        String sqlFilePath = storeFile.getPath();
        Connection db = Sqlite.build(sqlFilePath);
        Connection instance = new Sqlite.UncloseableConnection(db);
        return () -> instance;
    }

    @Test
    public void linksInDb() throws Exception {
        Path dir = Files.createTempDirectory("peergos-gc-test");
        SqliteBlockReachability rdb = SqliteBlockReachability.createReachabilityDb(dir.resolve("reachability.sqlite"));
        Cid block = new Cid(1, Cid.Codec.DagCbor, Multihash.Type.sha2_256, crypto.random.randomBytes(32));
        List<Cid> links = new ArrayList<>();
        for (int i=0; i<10; i++)
            links.add(new Cid(1, Cid.Codec.DagCbor, Multihash.Type.sha2_256, crypto.random.randomBytes(32)));
        rdb.addBlocks(Stream.concat(Stream.of(block), links.stream()).map(c -> new BlockVersion(c, null, true)).toList());

        rdb.setLinks(block, links);

        Optional<List<Cid>> fromDb = rdb.getLinks(block);
        Assert.assertEquals(fromDb.get(), links);
    }

    @Test
    public void fullGC() throws Exception {
        Path dir = Files.createTempDirectory("peergos-gc-test");
        SqliteCommands cmds = new SqliteCommands();
        RequestCountingBlockMetadataStore metadb = new RequestCountingBlockMetadataStore(new JdbcBlockMetadataStore(getDb(dir.resolve("metadata.sqlite")), cmds));

        WriteOnlyStorage storage = new WriteOnlyStorage(metadb);
        JdbcIpnsAndSocial pointers = new JdbcIpnsAndSocial(getDb(dir.resolve("mutable.sqlite")), cmds);
        JdbcUsageStore usage = new JdbcUsageStore(getDb(dir.resolve("usage.sqlite")), cmds);

        GarbageCollector gc = new GarbageCollector(storage, pointers, usage, dir, (x, y) -> Futures.of(true), true);
        gc.collect(s -> Futures.of(true));

        verifyAllReachableBlocksArePresent(pointers, metadb, storage);

        // write a tree, gc and verify again
        SigningKeyPair signer = SigningKeyPair.random(crypto.random, crypto.signer);
        PublicKeyHash writer = ContentAddressedStorage.hashKey(signer.publicSigningKey);
        Cid root = generateTree(42, 1000, blocks -> blocks
                        .forEach(b -> storage.storage.put(b, true)),
                (b, kids) -> metadb.put(b, null, new BlockMetadata(10, kids, Collections.emptyList())));
        byte[] signedCas = signer.signMessage(new PointerUpdate(MaybeMultihash.empty(), MaybeMultihash.of(root), Optional.of(1L)).serialize()).join();
        pointers.setPointer(writer, Optional.empty(), signedCas).join();

        verifyAllReachableBlocksArePresent(pointers, metadb, storage);

        // test deleting a block fails to verify
        Cid toRemove = storage.storage.keySet().stream().findAny().get();
        storage.storage.remove(toRemove);
        try {
            verifyAllReachableBlocksArePresent(pointers, metadb, storage);
            throw new RuntimeException("Should not get here");
        } catch (IllegalStateException expected) {}
        storage.storage.put(toRemove, true);

        metadb.resetRequestCount();
        gc.collect(s -> Futures.of(true));
        long gcMetadbGets = metadb.getRequestCount();
        verifyAllReachableBlocksArePresent(pointers, metadb, storage);
        Assert.assertTrue(gcMetadbGets < 1000);

        Path dbFile = dir.resolve("reachability.sqlite");
        Assert.assertTrue(Files.exists(dbFile));
        SqliteBlockReachability rdb = SqliteBlockReachability.createReachabilityDb(dbFile);
        Optional<List<Cid>> links = rdb.getLinks(toRemove);
        Assert.assertEquals(1999, rdb.size());
        Assert.assertTrue(links.isPresent());

        metadb.resetRequestCount();
        gc.collect(s -> Futures.of(true));
        long gcMetadbGets2 = metadb.getRequestCount();
        verifyAllReachableBlocksArePresent(pointers, metadb, storage);
        Assert.assertTrue(gcMetadbGets2 == 0);

        // test size is stable afte repeated GCs
        long bigSize = Files.size(dbFile);
        gc.collect(s -> Futures.of(true));
        gc.collect(s -> Futures.of(true));
        Assert.assertEquals(bigSize, Files.size(dbFile));

        // Remove root so everything is GC'd and test db file size decreases
        boolean setPointer = pointers.setPointer(writer, Optional.of(signedCas), signer.signMessage(new PointerUpdate(MaybeMultihash.of(root), MaybeMultihash.empty(), Optional.of(2L)).serialize()).join()).join();
        gc.collect(s -> Futures.of(true));
        long emptySize = Files.size(dbFile);
        Assert.assertTrue(emptySize < 32*1024);
    }

    public void verifyAllReachableBlocksArePresent(JdbcIpnsAndSocial pointers,
                                                   BlockMetadataStore meta,
                                                   DeletableContentAddressedStorage storage) {
        Map<PublicKeyHash, byte[]> roots = pointers.getAllEntries();
        for (Map.Entry<PublicKeyHash, byte[]> e : roots.entrySet()) {
            PublicKeyHash writerHash = e.getKey();
            PublicSigningKey writer = storage.getSigningKey(null, writerHash).join().get();
            byte[] bothHashes = writer.unsignMessage(e.getValue()).join();
            PointerUpdate cas = PointerUpdate.fromCbor(CborObject.fromByteArray(bothHashes));
            Cid root = (Cid)cas.updated.get();
            verifySubtreePresent(root, meta, storage);
        }
    }

    public void verifySubtreePresent(Cid block,
                                     BlockMetadataStore meta,
                                     DeletableContentAddressedStorage storage) {
        if (! storage.hasBlock(block))
            throw new IllegalStateException("Absent block " + block);
        BlockMetadata m = meta.get(block).get();
        for (Cid link : m.links) {
            verifySubtreePresent(link, meta, storage);
        }
    }

    @Test
    public void correctMarkPhase() throws IOException, SQLException {
        Path dir = Files.createTempDirectory("peergos-block-metadata");
        File storeFile = dir.resolve("metadata.sql" + System.currentTimeMillis()).toFile();
        String sqlFilePath = storeFile.getPath();
        Connection db = Sqlite.build(sqlFilePath);
        Connection instance = new Sqlite.UncloseableConnection(db);
        BlockMetadataStore metadb = new JdbcBlockMetadataStore(() -> instance, new SqliteCommands());

        String filename = "temp.sql";
        Path file = Path.of(filename);
        SqliteBlockReachability reachability = SqliteBlockReachability.createReachabilityDb(file);

        int nUsers = 1;
        int nRawBlocks = 1 << 9;
        ForkJoinPool listPool = Threads.newPool(2, "GC-list-");
        List<ForkJoinTask<Cid>> futs = IntStream.range(0, nUsers)
                .mapToObj(i -> listPool.submit(() -> generateTree(i, nRawBlocks,
                        blocks ->  reachability.addBlocks(blocks.stream().map(c ->  new BlockVersion(c, null, true)).collect(Collectors.toList())),
                        (b, links) -> metadb.put(b, null, new BlockMetadata(0, links, Collections.emptyList()))
                        )))
                .collect(Collectors.toList());
        List<Cid> roots = futs.stream()
                .map(ForkJoinTask::join)
                .collect(Collectors.toList());

        long size = reachability.size();
        Assert.assertTrue(size > 0);

        int markParallelism = 10;
        ForkJoinPool markPool = Threads.newPool(markParallelism, "GC-mark-");
        AtomicLong totalReachable = new AtomicLong(0);
        List<ForkJoinTask<Boolean>> usageMarked = roots.stream()
                .map(r -> markPool.submit(() -> {
                    try {
                        return GarbageCollector.markReachable(null, r,
                                "user-" + r, reachability, metadb, totalReachable);
                    } catch (Exception e) {
                        e.printStackTrace();
                        throw new RuntimeException(e);
                    }
                }))
                .collect(Collectors.toList());
        usageMarked.forEach(ForkJoinTask::join);

        List<BlockVersion> garbage = new ArrayList<>();
        reachability.getUnreachable(garbage::addAll);
        Assert.assertTrue(garbage.isEmpty());
    }

    private Cid generateTree(int seed, int nRawBlocksLeft, Consumer<List<Cid>> listConsumer, BiConsumer<Cid, List<Cid>> linksConsumer) {
        Random r = new Random(seed);
        List<Cid> buffer = new ArrayList<>(1000);
        Cid root = generateTree(r, nRawBlocksLeft, buffer, listConsumer, linksConsumer);
        listConsumer.accept(List.of(root));
        listConsumer.accept(buffer);
        System.out.println("Generated tree " + seed);
        return root;
    }

    private Cid generateTree(Random r, int nRawBlocksLeft, List<Cid> buffer, Consumer<List<Cid>> listConsumer, BiConsumer<Cid, List<Cid>> linksConsumer) {
        if (nRawBlocksLeft == 1) {
            Cid leaf = randomRaw(r);
            linksConsumer.accept(leaf, Collections.emptyList());
            return leaf;
        }
        int nLeft = nRawBlocksLeft / 2;
        Cid left = generateTree(r, nLeft, buffer, listConsumer, linksConsumer);
        Cid right = generateTree(r, nRawBlocksLeft - nLeft, buffer, listConsumer, linksConsumer);
        byte[] raw = new CborObject.CborList(List.of(
                new CborObject.CborMerkleLink(left),
                new CborObject.CborMerkleLink(right)
        )).serialize();
        Cid root = new Cid(1, Cid.Codec.DagCbor, Multihash.Type.sha2_256, Hash.sha256(raw));
        linksConsumer.accept(root, List.of(left, right));
        buffer.add(left);
        buffer.add(right);
        if (buffer.size() > 1000) {
            listConsumer.accept(buffer);
            buffer.clear();
        }
        return root;
    }

    private Cid randomRaw(Random r) {
        byte[] hash = new byte[32];
        r.nextBytes(hash);
        return new Cid(1, Cid.Codec.Raw, Multihash.Type.sha2_256, hash);
    }

    public static class WriteOnlyStorage implements DeletableContentAddressedStorage {
        private Map<Cid, Boolean> storage = new EfficientHashMap<>();
        private final BlockMetadataStore metadb;

        public WriteOnlyStorage(BlockMetadataStore metadb) {
            this.metadb = metadb;
        }

        public Optional<BlockMetadataStore> getBlockMetadataStore() {
            return Optional.of(metadb);
        }

        @Override
        public Stream<Cid> getAllBlockHashes(boolean useBlockstore) {
            return storage.keySet().stream();
        }

        @Override
        public void getAllBlockHashVersions(Consumer<List<BlockVersion>> res) {
            List<BlockVersion> batch = new ArrayList<>();
            for (Cid cid : storage.keySet()) {
                batch.add(new BlockVersion(cid, "hey", true));
                if (batch.size() == 1000) {
                    res.accept(batch);
                    batch.clear();
                }
            }
            res.accept(batch);
        }

        @Override
        public List<Cid> getOpenTransactionBlocks() {
            return List.of();
        }

        @Override
        public void clearOldTransactions(long cutoffMillis) {

        }

        @Override
        public boolean hasBlock(Cid hash) {
            return storage.containsKey(hash);
        }

        @Override
        public void delete(Cid block) {
            storage.remove(block);
        }

        @Override
        public void setPki(CoreNode pki) {

        }

        @Override
        public CompletableFuture<Optional<CborObject>> get(List<Multihash> peerIds, Cid hash, String auth, boolean persistBlock) {
            throw new IllegalStateException("Not implemented!");
        }

        @Override
        public CompletableFuture<Optional<byte[]>> getRaw(List<Multihash> peerIds, Cid hash, String auth, boolean persistBlock) {
            throw new IllegalStateException("Not implemented!");
        }

        @Override
        public List<List<Cid>> bulkGetLinks(List<Multihash> peerIds, List<Want> wants) {
            throw new IllegalStateException("Not implemented!");
        }

        @Override
        public CompletableFuture<String> linkHost(PublicKeyHash owner) {
            throw new IllegalStateException("Not implemented!");
        }

        @Override
        public ContentAddressedStorage directToOrigin() {
            return this;
        }

        @Override
        public Optional<BlockCache> getBlockCache() {
            throw new IllegalStateException("Not implemented!");
        }

        @Override
        public CompletableFuture<Cid> id() {
            return Futures.of(new Cid(1, Cid.Codec.DagCbor, Multihash.Type.sha2_256, new byte[32]));
        }

        @Override
        public CompletableFuture<List<Cid>> ids() {
            throw new IllegalStateException("Not implemented!");
        }

        @Override
        public CompletableFuture<TransactionId> startTransaction(PublicKeyHash owner) {
            throw new IllegalStateException("Not implemented!");
        }

        @Override
        public CompletableFuture<Boolean> closeTransaction(PublicKeyHash owner, TransactionId tid) {
            throw new IllegalStateException("Not implemented!");
        }

        @Override
        public CompletableFuture<List<Cid>> put(PublicKeyHash owner, PublicKeyHash writer, List<byte[]> signedHashes, List<byte[]> blocks, TransactionId tid) {
            return Futures.of(blocks.stream().map(b -> hashToCid(b, false)).toList());
        }

        @Override
        public CompletableFuture<Optional<CborObject>> get(PublicKeyHash owner, Cid hash, Optional<BatWithId> bat) {
            throw new IllegalStateException("Not implemented!");
        }

        @Override
        public CompletableFuture<List<Cid>> putRaw(PublicKeyHash owner, PublicKeyHash writer, List<byte[]> signedHashes, List<byte[]> blocks, TransactionId tid, ProgressConsumer<Long> progressCounter) {
            return Futures.of(blocks.stream().map(b -> hashToCid(b, true)).toList());
        }

        @Override
        public CompletableFuture<Optional<byte[]>> getRaw(PublicKeyHash owner, Cid hash, Optional<BatWithId> bat) {
            throw new IllegalStateException("Not implemented!");
        }

        @Override
        public CompletableFuture<List<byte[]>> getChampLookup(PublicKeyHash owner, Cid root, List<ChunkMirrorCap> caps, Optional<Cid> committedRoot) {
            throw new IllegalStateException("Not implemented!");
        }

        @Override
        public CompletableFuture<Optional<Integer>> getSize(Multihash block) {
            throw new IllegalStateException("Not implemented!");
        }

        @Override
        public CompletableFuture<IpnsEntry> getIpnsEntry(Multihash signer) {
            throw new IllegalStateException("Not implemented!");
        }

        @Override
        public CompletableFuture<EncryptedCapability> getSecretLink(SecretLink link) {
            throw new IllegalStateException("Not implemented!");
        }

        @Override
        public CompletableFuture<LinkCounts> getLinkCounts(String owner, LocalDateTime after, BatWithId mirrorBat) {
            throw new IllegalStateException("Not implemented!");
        }

        public static Cid hashToCid(byte[] input, boolean isRaw) {
            byte[] hash = hash(input);
            return new Cid(1, isRaw ? Cid.Codec.Raw : Cid.Codec.DagCbor, Multihash.Type.sha2_256, hash);
        }

        public static byte[] hash(byte[] input)
        {
            try {
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                md.update(input);
                return md.digest();
            } catch (NoSuchAlgorithmException e)
            {
                throw new IllegalStateException("couldn't find hash algorithm");
            }
        }
    }

    static class RequestCountingBlockMetadataStore implements BlockMetadataStore {
        private final BlockMetadataStore target;
        private final AtomicLong count = new AtomicLong(0);

        public RequestCountingBlockMetadataStore(BlockMetadataStore target) {
            this.target = target;
        }

        public long getRequestCount() {
            return count.get();
        }

        public void resetRequestCount() {
            count.set(0);
        }

        @Override
        public Optional<BlockMetadata> get(Cid block) {
            count.incrementAndGet();
            return target.get(block);
        }

        @Override
        public void put(Cid block, String version, BlockMetadata meta) {
            target.put(block, version, meta);
        }

        @Override
        public void remove(Cid block) {
            target.remove(block);
        }

        @Override
        public long size() {
            return target.size();
        }

        @Override
        public void applyToAll(Consumer<Cid> consumer) {
            target.applyToAll(consumer);
        }

        @Override
        public Stream<BlockVersion> list() {
            return target.list();
        }

        @Override
        public void listCbor(Consumer<List<BlockVersion>> res) {
            target.listCbor(res);
        }

        @Override
        public void compact() {
            target.compact();
        }
    }
}