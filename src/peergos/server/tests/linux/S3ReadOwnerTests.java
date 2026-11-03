package peergos.server.tests.linux;

import org.junit.*;
import peergos.server.*;
import peergos.server.space.*;
import peergos.server.sql.*;
import peergos.server.storage.*;
import peergos.server.storage.auth.*;
import peergos.server.tests.util.*;
import peergos.server.util.*;
import peergos.shared.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.*;
import peergos.shared.storage.*;
import peergos.shared.storage.auth.*;
import peergos.shared.util.*;

import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.function.*;

/** The bats in a block's metadata row come from whoever wrote the row, so a row recorded under one owner must not
 *  authorise a read of another owner's block, including when the owner's own row is missing.
 */
public class S3ReadOwnerTests {
    private static final Crypto crypto = Main.initCrypto();
    private static final Hasher hasher = crypto.hasher;
    private static final String BUCKET = "readownerbucket";
    private static final String ACCESS_KEY = "readowneraccesskey";
    private static final String SECRET_KEY = "readownersecretkey";

    private LocalS3Server server;
    private S3BlockStorage s3;
    private BlockMetadataStore metadata;
    private final Random random = new Random();

    private static Supplier<Connection> db() throws Exception {
        Connection conn = new Sqlite.UncloseableConnection(Sqlite.build(":memory:"));
        return () -> conn;
    }

    @Before
    public void start() throws Exception {
        int port = TestPorts.getPort();
        Path dir = Files.createTempDirectory("s3-read-owner");
        server = new LocalS3Server(dir, BUCKET, ACCESS_KEY, SECRET_KEY, port);
        server.start();
        S3Config config = LocalS3Server.getConfig(BUCKET, ACCESS_KEY, SECRET_KEY, port);
        SqlSupplier cmds = new SqliteCommands();
        Cid ourId = Cid.buildCidV1(Cid.Codec.LibP2pKey, Multihash.Type.sha2_256, new byte[32]);
        JdbcUsageStore usage = new JdbcUsageStore(db(), cmds);
        JdbcBatCave bats = new JdbcBatCave(db(), cmds);
        metadata = new JdbcBlockMetadataStore(db(), cmds);
        s3 = new S3BlockStorage(config, List.of(ourId),
                BlockStoreProperties.empty(), "localhost:8000",
                JdbcTransactionStore.build(db(), cmds),
                Builder.blockAuthoriser(Args.parse(new String[0]), bats, hasher), bats,
                metadata,
                usage,
                new RamBlockCache(1024, 100),
                new FileBlockBuffer(Files.createTempDirectory("s3-read-owner-buffer"), usage),
                Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE,
                false, dir, PartitionStatus.DONE, hasher,
                new RAMStorage(hasher), null);
    }

    @After
    public void stop() {
        server.stop();
    }

    private static PublicKeyHash randomOwner() {
        byte[] hash = new byte[32];
        new Random().nextBytes(hash);
        return new PublicKeyHash(new Cid(1, Cid.Codec.DagCbor, Multihash.Type.sha2_256, hash));
    }

    private byte[] rawBlock(Bat bat) {
        byte[] prefix = Bat.createRawBlockPrefix(bat, Optional.empty());
        byte[] data = new byte[prefix.length + 1000];
        System.arraycopy(prefix, 0, data, 0, prefix.length);
        byte[] body = new byte[1000];
        random.nextBytes(body);
        System.arraycopy(body, 0, data, prefix.length, body.length);
        return data;
    }

    private static BlockMirrorCap readCap(Cid block, Bat bat) {
        return new BlockMirrorCap(block, Optional.of(new BatWithId(bat, BatId.sha256(bat, hasher).join().id)));
    }

    /** The victim's block is stored under the victim, but the only row for it is one the attacker wrote first */
    private Cid victimBlockWithAttackersRow(PublicKeyHash victim, Bat victimBat,
                                           PublicKeyHash attacker, Bat attackerBat) {
        byte[] data = rawBlock(victimBat);
        Cid block = hasher.hash(data, true).join();
        metadata.put(attacker, block, "attacker", new BlockMetadata(data.length, Collections.emptyList(),
                List.of(BatId.inline(attackerBat))));
        s3.put(victim, block, data, false);
        return block;
    }

    private static boolean causedByUnauthorised(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause())
            if ("Unauthorised!".equals(c.getMessage()))
                return true;
        return false;
    }

    @Test
    public void anotherOwnersRowCannotAuthoriseARead() {
        PublicKeyHash victim = randomOwner(), attacker = randomOwner();
        Bat victimBat = Bat.random(crypto.random), attackerBat = Bat.random(crypto.random);
        Cid block = victimBlockWithAttackersRow(victim, victimBat, attacker, attackerBat);
        try {
            s3.authReads(victim, List.of(readCap(block, attackerBat))).join();
        } catch (Exception e) {
            Assert.assertTrue(e.toString(), causedByUnauthorised(e));
            return;
        }
        Assert.fail("A read of the victim's block was authorised by the bat in the attacker's row");
    }

    @Test
    public void theOwnerCanReadTheirBlockWithoutTheirOwnRow() throws Exception {
        PublicKeyHash victim = randomOwner(), attacker = randomOwner();
        Bat victimBat = Bat.random(crypto.random), attackerBat = Bat.random(crypto.random);
        Cid block = victimBlockWithAttackersRow(victim, victimBat, attacker, attackerBat);

        List<PresignedUrl> urls = s3.authReads(victim, List.of(readCap(block, victimBat))).join();
        Assert.assertEquals(block, hasher.hash(HttpUtil.get(urls.get(0)), true).join());
    }

    @Test
    public void aReadHasToNameItsOwner() {
        Bat bat = Bat.random(crypto.random);
        byte[] data = rawBlock(bat);
        Cid block = hasher.hash(data, true).join();
        s3.put(randomOwner(), block, data, false);
        try {
            s3.authReads(null, List.of(readCap(block, bat))).join();
            Assert.fail("A read naming no owner was authorised");
        } catch (Exception expected) {}
    }
}
