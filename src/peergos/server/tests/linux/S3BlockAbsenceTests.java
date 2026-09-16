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
import peergos.shared.mutable.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.Cid;
import peergos.shared.io.ipfs.Multihash;
import peergos.shared.storage.*;
import peergos.shared.util.*;

import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.function.Supplier;

/** A block that s3 says isn't there is not the same as a block s3 failed to serve.
 *
 *  The distinction is what keeps garbage collection both safe and unexploitable: a user can publish a
 *  pointer to a block they never uploaded, which is a 404 and must not be able to stop their
 *  collection, whereas any other error leaves reachability unknown and nothing may be deleted on it.
 */
public class S3BlockAbsenceTests {
    private static final Crypto crypto = Main.initCrypto();
    private static final Hasher hasher = crypto.hasher;
    private static final String BUCKET = "testbucket";
    private static final String ACCESS_KEY = "testaccesskey";
    private static final String SECRET_KEY = "testsecretkey";

    private LocalS3Server server;
    private S3BlockStorage s3;
    private JdbcBlockMetadataStore meta;
    private BlockCache cborCache;
    private JdbcUsageStore usage;
    private JdbcIpnsAndSocial pointers;
    private SigningKeyPair signer;
    private Path dir;
    private PublicKeyHash owner;

    private static Supplier<Connection> db() throws Exception {
        Connection conn = new Sqlite.UncloseableConnection(Sqlite.build(":memory:"));
        return () -> conn;
    }

    @Before
    public void start() throws Exception {
        int port = TestPorts.getPort();
        dir = Files.createTempDirectory("s3-absence-test");
        server = new LocalS3Server(dir, BUCKET, ACCESS_KEY, SECRET_KEY, port);
        server.start();

        S3Config config = LocalS3Server.getConfig(BUCKET, ACCESS_KEY, SECRET_KEY, port);
        SqlSupplier cmds = new SqliteCommands();
        Cid ourId = Cid.buildCidV1(Cid.Codec.LibP2pKey, Multihash.Type.sha2_256, new byte[32]);
        meta = new JdbcBlockMetadataStore(db(), cmds);
        cborCache = new RamBlockCache(1024, 100);
        usage = new JdbcUsageStore(db(), cmds);
        pointers = new JdbcIpnsAndSocial(db(), cmds);
        signer = SigningKeyPair.random(crypto.random, crypto.signer);
        owner = ContentAddressedStorage.hashKey(signer.publicSigningKey);
        usage.addUserIfAbsent("user");
        usage.addWriter("user", owner);
        usage.confirmUsage("user", owner, 10*1024*1024, false);
        s3 = new S3BlockStorage(config, List.of(ourId),
                BlockStoreProperties.empty(), "localhost:8000",
                JdbcTransactionStore.build(db(), cmds),
                (c, b, sid, auth) -> Futures.of(true), null,
                meta,
                usage,
                cborCache,
                new FileBlockBuffer(Files.createTempDirectory("s3-buffer"), usage),
                Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE,
                false, dir, PartitionStatus.DONE, hasher,
                new RAMStorage(hasher), null);
    }

    @After
    public void stop() {
        server.stop();
    }

    /** Write straight through to s3, bypassing the block buffer, so the object really is in the bucket.
     *  The transaction is closed, otherwise the block is gc protected as an in flight write.
     */
    private Cid putRawBlock(byte[] data) {
        return put(data, true);
    }

    private Cid putCborBlock(byte[] data) {
        return put(data, false);
    }

    private Cid put(byte[] data, boolean isRaw) {
        TransactionId tid = s3.startTransaction(owner).join();
        Cid block = s3.put(data, isRaw, tid, owner, false);
        s3.closeTransaction(owner, tid).join();
        return block;
    }

    /** A 404 has to be reported as absence, which is what stops the sweep being blocked by a user. */
    @Test
    public void aBlockS3NeverHadReportsAbsence() {
        Cid neverUploaded = new Cid(1, Cid.Codec.Raw, Multihash.Type.sha2_256, Hash.sha256("nope".getBytes()));
        try {
            s3.getBlockMetadata(owner, neverUploaded).join();
            Assert.fail("a block that was never uploaded should not have resolved");
        } catch (Exception e) {
            Assert.assertTrue("a 404 must report absence, not a read failure, or a user can stop their own gc: "
                    + rootOf(e), rootOf(e) instanceof BlockAbsentException);
        }
    }

    /** Callers outside this repo match on the wording of these messages, so changing the exception
     *  type must not change the text. Both paths kept "Missing block" for that reason.
     */
    @Test
    public void bothPathsKeepTheirOriginalWording() throws Exception {
        Cid neverUploaded = new Cid(1, Cid.Codec.Raw, Multihash.Type.sha2_256, Hash.sha256("gone".getBytes()));
        try {
            s3.getBlockMetadata(owner, neverUploaded).join();
            Assert.fail("should not have resolved");
        } catch (Exception e) {
            Assert.assertTrue("absence keeps the original wording: " + rootOf(e).getMessage(),
                    rootOf(e).getMessage().contains("Missing block"));
        }

        byte[] data = "another real block".getBytes();
        Cid present = putRawBlock(data);
        Assert.assertEquals(data.length, s3.getBlockMetadata(owner, present).join().size);
        meta.remove(present);
        cborCache.clear().join();
        server.refuseRequestsFor(s3Key(present));
        try {
            s3.getBlockMetadata(owner, present).join();
            Assert.fail("should not have resolved");
        } catch (Exception e) {
            Assert.assertTrue("a read failure keeps the original wording: " + rootOf(e).getMessage(),
                    rootOf(e).getMessage().contains("Missing block"));
        }
    }

    /** The real failure case: s3 is there but refusing, so reachability is genuinely unknown. */
    @Test
    public void aBlockS3FailsToServeIsNotAbsence() throws Exception {
        byte[] data = "a real block".getBytes();
        Cid present = putRawBlock(data);
        Assert.assertEquals("readable before the fault", data.length, s3.getBlockMetadata(owner, present).join().size);

        // the read only reaches s3 when neither the metadata store nor the cbor cache has the block
        meta.remove(present);
        cborCache.clear().join();
        server.refuseRequestsFor(s3Key(present));

        try {
            s3.getBlockMetadata(owner, present).join();
            Assert.fail("a failing read should not have resolved");
        } catch (Exception e) {
            Assert.assertFalse("a read failure must not be reported as absence, or live data is collected: "
                    + rootOf(e), rootOf(e) instanceof BlockAbsentException);
        }
    }

    /** A live tree in s3: a cbor root linking one raw leaf, with the pointer published at the root. */
    private Cid publishLiveTree() {
        Cid leaf = putRawBlock("live leaf".getBytes());
        Cid root = putCborBlock(new CborObject.CborList(List.of(new CborObject.CborMerkleLink(leaf))).serialize());
        setPointerTo(MaybeMultihash.of(root));
        usage.updateWriterUsageAtomically(owner, MaybeMultihash.empty(), MaybeMultihash.of(root),
                Collections.emptySet(), Collections.emptySet(), 1024, 0, false);
        return root;
    }

    private void setPointerTo(MaybeMultihash target) {
        byte[] signedCas = signer.signMessage(new PointerUpdate(MaybeMultihash.empty(), target, Optional.of(1L))
                .serialize()).join();
        pointers.setPointer(owner, Optional.empty(), signedCas).join();
    }

    private GarbageCollector gc() {
        return new GarbageCollector(s3, pointers, usage, new RamPki(), dir,
                (x, y, z) -> Futures.of(true), u -> Futures.of(true), true);
    }

    /** Ask s3 itself, rather than hasBlock, which consults caches and the write buffer. */
    private boolean inS3(Cid block) {
        return s3.contains(owner, block);
    }

    /** Control: without any failure the sweep does collect, so the assertions below are not vacuous. */
    @Test
    public void gcCollectsGarbageOnS3() {
        Cid root = publishLiveTree();
        Cid garbage = putRawBlock("unreferenced".getBytes());

        gc().collect(x -> Futures.of(true));

        Assert.assertFalse("unreferenced block should be collected", inS3(garbage));
        Assert.assertTrue("the live root must survive", inS3(root));
    }

    /** The malicious case, end to end on s3: publishing a pointer to a block that was never uploaded
     *  is free, and must not buy the user immunity from collection.
     */
    @Test
    public void aDanglingPointerDoesNotStopCollection() {
        Cid garbage = putRawBlock("unreferenced".getBytes());
        Cid neverUploaded = new Cid(1, Cid.Codec.DagCbor, Multihash.Type.sha2_256, Hash.sha256("ghost".getBytes()));
        setPointerTo(MaybeMultihash.of(neverUploaded));

        gc().collect(x -> Futures.of(true));

        Assert.assertFalse("a 404 on the pointer target must not stop the sweep", inS3(garbage));
    }

    /** The data loss case, end to end on s3: a live block s3 refuses to serve leaves reachability
     *  unknown, so nothing may be deleted on the strength of that walk.
     */
    @Test
    public void s3FailingToServeALiveBlockStopsCollection() {
        Cid root = publishLiveTree();
        Cid garbage = putRawBlock("unreferenced".getBytes());

        // the gc only reaches s3 when neither the metadata store nor the cbor cache has the block,
        // which is the normal case on a store larger than the cache
        meta.remove(root);
        cborCache.clear().join();
        server.refuseRequestsFor(s3Key(root));

        gc().collect(x -> Futures.of(true));

        Assert.assertTrue("nothing may be swept when a live block could not be read", inS3(garbage));
    }

    /** Blocks are stored under the cid encoded as uppercase base32, not its base58 string. */
    private static String s3Key(Cid block) {
        return DirectS3BlockStore.hashToKey(block);
    }

    private static Throwable rootOf(Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null && cause.getCause() != cause)
            cause = cause.getCause();
        return cause;
    }
}
