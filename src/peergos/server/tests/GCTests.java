package peergos.server.tests;

import org.junit.*;
import org.peergos.*;
import peergos.server.JavaCrypto;
import peergos.server.corenode.JdbcIpnsAndSocial;
import peergos.server.space.JdbcUsageStore;
import peergos.server.sql.*;
import peergos.server.storage.*;
import peergos.server.util.*;
import peergos.shared.Crypto;
import peergos.shared.MaybeMultihash;
import peergos.shared.cbor.*;
import peergos.shared.crypto.SigningKeyPair;
import peergos.shared.crypto.asymmetric.PublicSigningKey;
import peergos.shared.crypto.hash.PublicKeyHash;
import peergos.shared.io.ipfs.*;
import peergos.shared.mutable.PointerUpdate;
import peergos.shared.storage.*;
import peergos.shared.util.Futures;

import java.io.*;
import java.nio.file.*;
import java.sql.*;
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
    public void versionedGC() throws Exception {
        Path dir = Files.createTempDirectory("peergos-gc-test");
        SqliteCommands cmds = new SqliteCommands();
        RequestCountingBlockMetadataStore metadb = new RequestCountingBlockMetadataStore(new JdbcBlockMetadataStore(getDb(dir.resolve("metadata.sqlite")), cmds));

        VersionedWriteOnlyStorage storage = new VersionedWriteOnlyStorage(metadb);
        JdbcIpnsAndSocial pointers = new JdbcIpnsAndSocial(getDb(dir.resolve("mutable.sqlite")), cmds);
        JdbcUsageStore usage = new JdbcUsageStore(getDb(dir.resolve("usage.sqlite")), cmds);

        GarbageCollector gc = new GarbageCollector(storage, pointers, usage, dir, (x, y) -> Futures.of(true), true);
        gc.collect(s -> Futures.of(true));
        Path dbFile = dir.resolve("reachability.sqlite");
        Assert.assertTrue(Files.exists(dbFile));
        SqliteBlockReachability rdb = SqliteBlockReachability.createReachabilityDb(dbFile);

        verifyAllReachableBlocksArePresent(pointers, metadb, storage);

        // write a 2 block tree, gc and verify again
        SigningKeyPair signer = SigningKeyPair.random(crypto.random, crypto.signer);
        PublicKeyHash writer = ContentAddressedStorage.hashKey(signer.publicSigningKey);
        Random r = new Random(42);
        Cid leaf = randomRaw(r);
        byte[] raw = new CborObject.CborList(Stream.of(leaf)
                .map(CborObject.CborMerkleLink::new).toList()
        ).serialize();
        Cid root = new Cid(1, Cid.Codec.DagCbor, Multihash.Type.sha2_256, Hash.sha256(raw));
        storage.add(leaf);
        BlockVersion rootV1 = storage.add(root);
        metadb.put(root, rootV1.version, raw);
        byte[] signedCas = signer.signMessage(new PointerUpdate(MaybeMultihash.empty(), MaybeMultihash.of(root), Optional.of(1L)).serialize()).join();
        pointers.setPointer(writer, Optional.empty(), signedCas).join();

        verifyAllReachableBlocksArePresent(pointers, metadb, storage);
        Assert.assertEquals(2, storage.storage.size());
        List<BlockVersion> versions1 = new ArrayList<>();
        storage.getAllBlockHashVersions(versions1::addAll);
        Assert.assertEquals(2, versions1.size());
        gc.collect(s -> Futures.of(true));
        Optional<List<Cid>> links = rdb.getLinks(root);
        Assert.assertTrue(links.isPresent());
        Assert.assertTrue(links.get().contains(leaf));

        // add a new version of the leaf block, and check it is kept and the original is deleted
        BlockVersion leafV2 = storage.add(leaf);
        gc.collect(s -> Futures.of(true));
        Assert.assertEquals(2, storage.storage.size());
        List<BlockVersion> versions2 = new ArrayList<>();
        storage.getAllBlockHashVersions(versions2::addAll);
        Assert.assertEquals(2, versions2.size());
        Assert.assertTrue(versions2.contains(leafV2));
        Optional<List<Cid>> links2 = rdb.getLinks(root);
        Assert.assertTrue(links2.isPresent());
        Assert.assertTrue(links2.get().contains(leaf));

        // now add a new version of the root
        BlockVersion rootV2 = storage.add(root);
        gc.collect(s -> Futures.of(true));
        Assert.assertEquals(2, storage.storage.size());
        List<BlockVersion> versions3 = new ArrayList<>();
        storage.getAllBlockHashVersions(versions3::addAll);
        Assert.assertEquals(2, versions3.size());
        Assert.assertTrue(versions3.contains(rootV2));
        Optional<List<Cid>> links3 = rdb.getLinks(root);
        Assert.assertTrue(links3.isPresent());
        Assert.assertTrue(links3.get().contains(leaf));

        gc.collect(s -> Futures.of(true));
        Assert.assertEquals(2, storage.storage.size());
        List<BlockVersion> versions4 = new ArrayList<>();
        storage.getAllBlockHashVersions(versions4::addAll);
        Assert.assertEquals(2, versions4.size());
        Assert.assertTrue(versions4.contains(rootV2));
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
        Assert.assertTrue(gcMetadbGets < 2000);

        Path dbFile = dir.resolve("reachability.sqlite");
        Assert.assertTrue(Files.exists(dbFile));
        SqliteBlockReachability rdb = SqliteBlockReachability.createReachabilityDb(dbFile);
        Optional<List<Cid>> links = rdb.getLinks(root);
        Assert.assertTrue(rdb.size() < 2000);
        Assert.assertTrue(links.isPresent());

        metadb.resetRequestCount();
        gc.collect(s -> Futures.of(true));
        long gcMetadbGets2 = metadb.getRequestCount();
        verifyAllReachableBlocksArePresent(pointers, metadb, storage);
        Assert.assertTrue(gcMetadbGets2 == 0);

        // test size is stable after repeated GCs
        long bigSize = Files.size(dbFile);
        gc.collect(s -> Futures.of(true));
        gc.collect(s -> Futures.of(true));
        Assert.assertEquals(bigSize, Files.size(dbFile));

        // Remove root so everything is GC'd and test db file size decreases
        boolean setPointer = pointers.setPointer(writer, Optional.of(signedCas), signer.signMessage(new PointerUpdate(MaybeMultihash.of(root), MaybeMultihash.empty(), Optional.of(2L)).serialize()).join()).join();
        gc.collect(s -> Futures.of(true));
        long emptySize = Files.size(dbFile);
        Assert.assertTrue(emptySize < 32*1024);

        // Add a new tree
        SigningKeyPair signer2 = SigningKeyPair.random(crypto.random, crypto.signer);
        PublicKeyHash writer2 = ContentAddressedStorage.hashKey(signer2.publicSigningKey);
        Cid root2 = generateTree(42, 1000, blocks -> blocks
                        .forEach(b -> storage.storage.put(b, true)),
                (b, kids) -> metadb.put(b, null, new BlockMetadata(10, kids, Collections.emptyList())));
        byte[] signedCas2 = signer2.signMessage(new PointerUpdate(MaybeMultihash.empty(), MaybeMultihash.of(root2), Optional.of(1L)).serialize()).join();
        pointers.setPointer(writer2, Optional.empty(), signedCas2).join();

        gc.collect(s -> Futures.of(true));
        verifyAllReachableBlocksArePresent(pointers, metadb, storage);
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
            if (cas.updated.isPresent()) {
                Cid root = (Cid) cas.updated.get();
                verifySubtreePresent(root, meta, storage);
            }
        }
    }

    public void verifySubtreePresent(Cid block,
                                     BlockMetadataStore meta,
                                     DeletableContentAddressedStorage storage) {
        if (! storage.hasBlock(block))
            throw new IllegalStateException("Absent block " + block);
        if (block.isRaw())
            return;
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

    private Cid generateTree(int seed, int nLeafBlocksLeft, Consumer<List<Cid>> listConsumer, BiConsumer<Cid, List<Cid>> linksConsumer) {
        Random r = new Random(seed);
        List<Cid> buffer = new ArrayList<>(1000);
        Cid root = generateTree(r, nLeafBlocksLeft, buffer, listConsumer, linksConsumer);
        listConsumer.accept(List.of(root));
        listConsumer.accept(buffer);
        System.out.println("Generated tree " + seed);
        return root;
    }

    private Cid generateTree(Random r, int nLeafBlocksLeft, List<Cid> buffer, Consumer<List<Cid>> listConsumer, BiConsumer<Cid, List<Cid>> linksConsumer) {
        if (nLeafBlocksLeft <= 5) {
            List<Cid> leaves = IntStream.range(0, nLeafBlocksLeft)
                    .mapToObj(i -> Math.random() < 0.5 ?
                            randomRaw(r) :
                            randomCbor(r))
                    .toList();
            for (Cid leaf : leaves) {
                linksConsumer.accept(leaf, Collections.emptyList());
            }
            byte[] raw = new CborObject.CborList(leaves.stream()
                    .map(CborObject.CborMerkleLink::new).toList()
            ).serialize();
            Cid parent = new Cid(1, Cid.Codec.DagCbor, Multihash.Type.sha2_256, Hash.sha256(raw));

            linksConsumer.accept(parent, leaves);
            buffer.addAll(leaves);
            buffer.add(parent);
            return parent;
        }
        int nLeft = nLeafBlocksLeft / 2;
        Cid left = generateTree(r, nLeft, buffer, listConsumer, linksConsumer);
        Cid right = generateTree(r, nLeafBlocksLeft - nLeft, buffer, listConsumer, linksConsumer);
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

    private Cid randomCbor(Random r) {
        byte[] hash = new byte[32];
        r.nextBytes(hash);
        return new Cid(1, Cid.Codec.DagCbor, Multihash.Type.sha2_256, hash);
    }
}