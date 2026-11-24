package peergos.server.tests.linux;

import org.junit.*;
import peergos.server.*;
import peergos.server.corenode.*;
import peergos.server.space.*;
import peergos.server.sql.*;
import peergos.server.storage.*;
import peergos.server.tests.*;
import peergos.server.tests.util.*;
import peergos.server.util.*;
import peergos.shared.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.asymmetric.SigningDomain;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.Cid;
import peergos.shared.io.ipfs.Multihash;
import peergos.shared.mutable.*;
import peergos.shared.storage.*;
import peergos.shared.util.*;

import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** A mirror running concurrently with gc on s3, with each interleaving forced deterministically. */
public class S3MirrorGcTests {
    private static final Crypto crypto = Main.initCrypto();
    private static final Hasher hasher = crypto.hasher;
    private static final String BUCKET = "testbucket";
    private static final String ACCESS_KEY = "testaccesskey";
    private static final String SECRET_KEY = "testsecretkey";
    private static final String USER = "user";

    private LocalS3Server server;
    private S3BlockStorage s3;
    private TransactionStore transactions;
    private JdbcUsageStore usage;
    private JdbcIpnsAndSocial pointers;
    private SigningKeyPair signer;
    private Path dir;
    private PublicKeyHash owner;
    private Cid ourId;
    private final Cid sourceId = Cid.buildCidV1(Cid.Codec.LibP2pKey, Multihash.Type.sha2_256, Hash.sha256("source".getBytes()));
    private final Map<Cid, byte[]> source = new ConcurrentHashMap<>();

    private static Supplier<Connection> db() throws Exception {
        Connection conn = new Sqlite.UncloseableConnection(Sqlite.build(":memory:"));
        return () -> conn;
    }

    @Before
    public void start() throws Exception {
        int port = TestPorts.getPort();
        dir = Files.createTempDirectory("s3-mirror-gc-test");
        server = new LocalS3Server(dir, BUCKET, ACCESS_KEY, SECRET_KEY, port);
        server.start();

        S3Config config = LocalS3Server.getConfig(BUCKET, ACCESS_KEY, SECRET_KEY, port);
        SqlSupplier cmds = new SqliteCommands();
        ourId = Cid.buildCidV1(Cid.Codec.LibP2pKey, Multihash.Type.sha2_256, new byte[32]);
        usage = new JdbcUsageStore(db(), cmds);
        pointers = new JdbcIpnsAndSocial(db(), cmds);
        transactions = JdbcTransactionStore.build(db(), cmds);
        signer = SigningKeyPair.random(crypto.random, crypto.signer);
        owner = ContentAddressedStorage.hashKey(signer.publicSigningKey);
        usage.addUserIfAbsent(USER);
        usage.addWriter(USER, owner);
        usage.confirmUsage(USER, owner, 10*1024*1024, false);
        ContentAddressedStorageProxy remote = (ContentAddressedStorageProxy) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class[]{ContentAddressedStorageProxy.class}, (p, m, args) -> {
                    if (m.getName().equals("getRaw"))
                        return Futures.of(Optional.ofNullable(source.get((Cid) args[2])));
                    throw new IllegalStateException("Unexpected remote call " + m.getName());
                });
        s3 = new S3BlockStorage(config, List.of(ourId),
                BlockStoreProperties.empty(), "localhost:8000",
                transactions,
                (c, b, sid, auth) -> Futures.of(true), null,
                new JdbcBlockMetadataStore(db(), cmds),
                usage,
                new RamBlockCache(1024, 100),
                new FileBlockBuffer(Files.createTempDirectory("s3-buffer"), usage),
                Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE,
                false, dir, PartitionStatus.DONE, hasher,
                new RAMStorage(hasher), remote);
    }

    @After
    public void stop() {
        server.stop();
    }

    private Cid addToSource(byte[] data, boolean isRaw) {
        Cid cid = hasher.hash(data, isRaw).join();
        source.put(cid, data);
        return cid;
    }

    private Cid cborNode(Cid... links) {
        return addToSource(new CborObject.CborList(Arrays.stream(links).map(CborObject.CborMerkleLink::new).toList()).serialize(), false);
    }

    private Cid putLocally(byte[] data, boolean isRaw) {
        TransactionId tid = s3.startTransaction(owner).join();
        Cid block = s3.put(data, isRaw, tid, owner, false);
        s3.closeTransaction(owner, tid).join();
        return block;
    }

    private TransactionId mirrorBlocks(Cid root) {
        TransactionId tid = transactions.startTransaction(owner);
        s3.mirror(USER, owner, owner, List.of(sourceId), Optional.empty(), Optional.of(root), Optional.empty(),
                ourId, (w, bs, size) -> {}, tid, hasher).join();
        return tid;
    }

    private void commit(Cid root, TransactionId tid) {
        byte[] signedCas = signer.signMessage(new PointerUpdate(MaybeMultihash.empty(), MaybeMultihash.of(root), Optional.of(1L))
                .serialize(), SigningDomain.POINTER).join();
        pointers.setPointer(owner, Optional.empty(), signedCas).join();
        transactions.closeTransaction(owner, tid);
    }

    private void gc(GarbageCollector.DeletionConfirmer beforeDelete) {
        new GarbageCollector(s3, pointers, usage, new RamPki(), dir, beforeDelete, u -> Futures.of(true), true)
                .collect(x -> Futures.of(true));
    }

    private boolean inS3(Cid block) {
        return s3.contains(owner, block);
    }

    /** The mirror has written the tree but not yet committed the pointer when gc runs start to finish. */
    @Test
    public void treeMirroredBeforePointerCommitSurvivesGc() {
        Cid l1 = addToSource("leaf 1".getBytes(), true);
        Cid l2 = addToSource("leaf 2".getBytes(), true);
        Cid l3 = addToSource("leaf 3".getBytes(), true);
        Cid inner = cborNode(l1, l2);
        Cid root = cborNode(inner, l3);
        Cid garbage = putLocally("garbage".getBytes(), true);

        TransactionId tid = mirrorBlocks(root);
        gc((c, r, t, u) -> Futures.of(true));
        commit(root, tid);

        Assert.assertFalse("gc should still collect real garbage", inS3(garbage));
        for (Cid b : List.of(inner, l1, l2, l3))
            Assert.assertTrue("mirrored block deleted before its pointer was committed: " + b, inS3(b));
    }

    /** The mirror reuses an orphan gc already listed, and commits and closes its transaction before gc deletes.
     *  Only re-reading the pointers protects the orphan.
     */
    @Test
    public void reusedOrphanSurvivesMirrorCommittedDuringGc() {
        Cid orphan = putLocally("orphan leaf".getBytes(), true);
        source.put(orphan, "orphan leaf".getBytes());
        Cid fresh = addToSource("fresh leaf".getBytes(), true);
        Cid root = cborNode(orphan, fresh);
        Cid garbage = putLocally("garbage".getBytes(), true);

        gc((c, r, t, u) -> {
            commit(root, mirrorBlocks(root));
            return Futures.of(true);
        });

        Assert.assertFalse("gc should still collect real garbage", inS3(garbage));
        Assert.assertTrue("reused orphan deleted after the mirror committed to it", inS3(orphan));
        Assert.assertTrue(inS3(fresh));
    }

    /** A pointer changed during gc to a block that was never uploaded must not stop collection, or a user
     *  could deny their own gc for free.
     */
    @Test
    public void danglingPointerSetDuringGcDoesNotStopCollection() {
        Cid garbage = putLocally("garbage".getBytes(), true);
        Cid neverUploaded = new Cid(1, Cid.Codec.DagCbor, Multihash.Type.sha2_256, Hash.sha256("ghost".getBytes()));

        gc((c, r, t, u) -> {
            commit(neverUploaded, transactions.startTransaction(owner));
            return Futures.of(true);
        });

        Assert.assertFalse("a 404 on a re-read pointer target must not stop the sweep", inS3(garbage));
    }

    /** The mirror reuses an orphan gc already listed, and is still uncommitted when gc deletes.
     *  Only re-reading the pending blocks protects the orphan.
     */
    @Test
    public void reusedOrphanSurvivesMirrorStillOpenDuringGc() {
        Cid orphan = putLocally("orphan leaf".getBytes(), true);
        source.put(orphan, "orphan leaf".getBytes());
        Cid fresh = addToSource("fresh leaf".getBytes(), true);
        Cid root = cborNode(orphan, fresh);
        Cid garbage = putLocally("garbage".getBytes(), true);

        TransactionId[] tid = new TransactionId[1];
        gc((c, r, t, u) -> {
            tid[0] = mirrorBlocks(root);
            return Futures.of(true);
        });
        commit(root, tid[0]);

        Assert.assertFalse("gc should still collect real garbage", inS3(garbage));
        Assert.assertTrue("reused orphan deleted while the mirror's transaction was open", inS3(orphan));
        Assert.assertTrue(inS3(fresh));
    }
}
