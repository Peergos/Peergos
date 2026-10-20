package peergos.server.tests.linux;

import org.junit.*;
import peergos.server.*;
import peergos.server.space.*;
import peergos.server.sql.*;
import peergos.server.storage.*;
import peergos.server.tests.util.*;
import peergos.server.util.*;
import peergos.shared.*;
import peergos.shared.cbor.*;
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

/** Every cbor block in an S3 blockstore can be mirrored to a second bucket, which serves reads first. */
public class S3CborMirrorTests {
    private static final Crypto crypto = Main.initCrypto();
    private static final Hasher hasher = crypto.hasher;
    private static final String BUCKET = "mainbucket";
    private static final String MIRROR_BUCKET = "cborbucket";
    private static final String ACCESS_KEY = "testaccesskey";
    private static final String SECRET_KEY = "testsecretkey";

    private LocalS3Server server, mirrorServer;
    private S3Config config;
    private S3CborMirror mirror;
    private FileBlockBuffer retryBuffer;
    private JdbcUsageStore usage;
    private Cid ourId;
    private Path dir;
    private PublicKeyHash owner;

    private static Supplier<Connection> db() throws Exception {
        Connection conn = new Sqlite.UncloseableConnection(Sqlite.build(":memory:"));
        return () -> conn;
    }

    @Before
    public void start() throws Exception {
        int port = TestPorts.getPort();
        dir = Files.createTempDirectory("s3-cbor-mirror-test");
        server = new LocalS3Server(dir.resolve("main"), BUCKET, ACCESS_KEY, SECRET_KEY, port);
        server.start();
        int mirrorPort = TestPorts.getPort();
        mirrorServer = new LocalS3Server(dir.resolve("mirror"), MIRROR_BUCKET, ACCESS_KEY, SECRET_KEY, mirrorPort);
        mirrorServer.start();

        config = LocalS3Server.getConfig(BUCKET, ACCESS_KEY, SECRET_KEY, port);
        ourId = Cid.buildCidV1(Cid.Codec.LibP2pKey, Multihash.Type.sha2_256, new byte[32]);
        usage = new JdbcUsageStore(db(), new SqliteCommands());
        SigningKeyPair signer = SigningKeyPair.random(crypto.random, crypto.signer);
        owner = ContentAddressedStorage.hashKey(signer.publicSigningKey);
        usage.addUserIfAbsent("user");
        usage.addWriter("user", owner);
        usage.confirmUsage("user", owner, 10*1024*1024, false);
        retryBuffer = new FileBlockBuffer(dir.resolve("mirror-retries"), usage);
        mirror = new S3CborMirror(LocalS3Server.getConfig(MIRROR_BUCKET, ACCESS_KEY, SECRET_KEY, mirrorPort), retryBuffer, hasher);
    }

    @After
    public void stop() {
        server.stop();
        mirrorServer.stop();
    }

    private S3BlockStorage build(Optional<S3CborMirror> cborMirror) throws Exception {
        SqlSupplier cmds = new SqliteCommands();
        return new S3BlockStorage(config, List.of(ourId),
                BlockStoreProperties.empty(), "localhost:8000",
                JdbcTransactionStore.build(db(), cmds),
                (c, b, sid, auth) -> Futures.of(true), null,
                new JdbcBlockMetadataStore(db(), cmds),
                usage,
                new RamBlockCache(1024, 100),
                new FileBlockBuffer(Files.createTempDirectory("s3-buffer"), usage),
                Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE,
                false, dir, PartitionStatus.DONE, hasher,
                new RAMStorage(hasher), null, cborMirror, false);
    }

    private Cid put(S3BlockStorage s3, byte[] data, boolean isRaw) {
        TransactionId tid = s3.startTransaction(owner).join();
        Cid block = s3.put(data, isRaw, tid, owner, false);
        s3.closeTransaction(owner, tid).join();
        return block;
    }

    private static byte[] cbor(String s) {
        return new CborObject.CborString(s).serialize();
    }

    private Optional<byte[]> read(S3BlockStorage s3, Cid block) {
        return s3.getRaw(List.of(ourId.bareMultihash()), owner, block, Optional.empty(), ourId, hasher, false).join();
    }

    private String key(Cid block) {
        return "user/" + DirectS3BlockStore.hashToKey(block);
    }

    @Test
    public void onlyCborBlocksAreWrittenToTheMirror() throws Exception {
        S3BlockStorage s3 = build(Optional.of(mirror));
        Cid cbor = put(s3, cbor("mirrored"), false);
        Cid raw = put(s3, "not mirrored".getBytes(), true);

        Assert.assertTrue(s3.contains(owner, cbor));
        Assert.assertTrue(mirror.has(key(cbor)));
        Assert.assertTrue(s3.contains(owner, raw));
        Assert.assertFalse(mirror.has(key(raw)));
    }

    @Test
    public void cborReadsAreServedByTheMirror() throws Exception {
        S3BlockStorage s3 = build(Optional.of(mirror));
        byte[] data = cbor("served by the mirror");
        Cid cbor = put(s3, data, false);
        server.refuseRequestsFor(DirectS3BlockStore.hashToKey(cbor));

        Assert.assertArrayEquals(data, read(s3, cbor).get());
    }

    @Test
    public void aMirrorMissFallsBackAndRepairsTheMirror() throws Exception {
        byte[] data = cbor("written before the mirror existed");
        Cid cbor = put(build(Optional.empty()), data, false);
        Assert.assertFalse(mirror.has(key(cbor)));

        Assert.assertArrayEquals(data, read(build(Optional.of(mirror)), cbor).get());
        long deadline = System.currentTimeMillis() + 10_000;
        while (! mirror.has(key(cbor)) && System.currentTimeMillis() < deadline)
            Thread.sleep(50);
        Assert.assertTrue("a block read from the main store should be copied into the mirror", mirror.has(key(cbor)));
    }

    @Test
    public void backfillCopiesEveryCborBlock() throws Exception {
        S3BlockStorage unmirrored = build(Optional.empty());
        List<Cid> cbor = new ArrayList<>();
        for (int i = 0; i < 50; i++)
            cbor.add(put(unmirrored, cbor("block " + i), false));
        Cid raw = put(unmirrored, "raw".getBytes(), true);

        S3BlockStorage s3 = build(Optional.of(mirror));
        Cid alreadyMirrored = put(s3, cbor("already there"), false);
        s3.backfillCborMirror();

        for (Cid c : cbor)
            Assert.assertTrue("backfill missed " + c, mirror.has(key(c)));
        Assert.assertTrue(mirror.has(key(alreadyMirrored)));
        Assert.assertFalse(mirror.has(key(raw)));
    }

    private void awaitInMirror(Cid block) throws Exception {
        long deadline = System.currentTimeMillis() + 15_000;
        while (! mirror.has(key(block)) && System.currentTimeMillis() < deadline)
            Thread.sleep(50);
        Assert.assertTrue("a queued write should reach the mirror once it recovers", mirror.has(key(block)));
        deadline = System.currentTimeMillis() + 5_000;
        while (retryBuffer.hasBlock(owner, block) && System.currentTimeMillis() < deadline)
            Thread.sleep(50);
        Assert.assertFalse("a written block should leave the retry buffer", retryBuffer.hasBlock(owner, block));
    }

    @Test
    public void aRefusingMirrorDoesNotFailWrites() throws Exception {
        S3BlockStorage s3 = build(Optional.of(mirror));
        byte[] data = cbor("written while the mirror refuses");
        Cid cid = hasher.hash(data, false).join();
        mirrorServer.refuseRequestsFor(DirectS3BlockStore.hashToKey(cid));

        Assert.assertEquals(cid, put(s3, data, false));
        Assert.assertTrue(s3.contains(owner, cid));
        Assert.assertTrue("a failed mirror write should wait on disk", retryBuffer.hasBlock(owner, cid));

        mirrorServer.serveRequestsFor(DirectS3BlockStore.hashToKey(cid));
        awaitInMirror(cid);
    }

    @Test
    public void bufferedWritesAreRetriedAfterARestart() throws Exception {
        byte[] data = cbor("buffered before a restart");
        Cid cid = put(build(Optional.empty()), data, false);
        retryBuffer.put(owner, cid, data).join();

        build(Optional.of(mirror));
        awaitInMirror(cid);
    }

    @Test
    public void aThrottlingMirrorDoesNotFailWrites() throws Exception {
        S3BlockStorage s3 = build(Optional.of(mirror));
        byte[] data = cbor("written while the mirror throttles");
        Cid cid = hasher.hash(data, false).join();
        mirrorServer.throttleRequestsFor(DirectS3BlockStore.hashToKey(cid));

        Assert.assertEquals(cid, put(s3, data, false));
        Assert.assertTrue(s3.contains(owner, cid));
        Assert.assertTrue("a throttled write should back the mirror off", mirror.isThrottled());

        mirrorServer.serveRequestsFor(DirectS3BlockStore.hashToKey(cid));
        awaitInMirror(cid);
    }

    @Test
    public void aBulkDeleteToleratesAbsentKeys() throws Exception {
        S3BlockStorage s3 = build(Optional.of(mirror));
        Cid present = put(s3, cbor("present"), false);
        Cid absent = hasher.hash(cbor("never written"), false).join();

        s3.bulkDelete(owner, List.of(new BlockVersion(absent, null, true), new BlockVersion(present, null, true)));

        Assert.assertFalse(s3.contains(owner, present));
        Assert.assertFalse(mirror.has(key(present)));
    }

    @Test
    public void deletesAreMirrored() throws Exception {
        S3BlockStorage s3 = build(Optional.of(mirror));
        Cid single = put(s3, cbor("deleted alone"), false);
        Cid bulk1 = put(s3, cbor("deleted in bulk 1"), false);
        Cid bulk2 = put(s3, cbor("deleted in bulk 2"), false);
        Cid kept = put(s3, cbor("kept"), false);

        s3.delete(owner, single);
        s3.bulkDelete(owner, List.of(new BlockVersion(bulk1, null, true), new BlockVersion(bulk2, null, true)));

        for (Cid gone : List.of(single, bulk1, bulk2)) {
            Assert.assertFalse(s3.contains(owner, gone));
            Assert.assertFalse(mirror.has(key(gone)));
        }
        Assert.assertTrue(mirror.has(key(kept)));
    }
}
