package peergos.server.tests.linux;

import org.junit.*;
import peergos.server.*;
import peergos.server.space.*;
import peergos.server.storage.admin.*;
import peergos.server.sql.*;
import peergos.server.storage.*;
import peergos.server.storage.auth.*;
import peergos.server.tests.util.*;
import peergos.server.util.*;
import peergos.shared.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.*;
import peergos.shared.storage.*;
import peergos.shared.storage.auth.*;
import peergos.shared.util.*;

import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.function.*;

/** A presigned write into one owner's space must not be had with a writer of another user. The space check took a
 *  writer's signature and quota as enough, without checking the writer belonged to the owner named.
 */
public class S3WriterOwnershipTests {
    private static final Crypto crypto = Main.initCrypto();
    private static final Hasher hasher = crypto.hasher;
    private static final String BUCKET = "ownershipbucket";
    private static final String ACCESS_KEY = "ownershipaccesskey";
    private static final String SECRET_KEY = "ownershipsecretkey";

    private LocalS3Server server;
    private S3BlockStorage s3;
    private JdbcUsageStore usage;
    private SpaceCheckingKeyFilter checker;
    private ContentAddressedStorage filtered;
    private final Map<PublicKeyHash, String> usernames = new HashMap<>();
    private final Random random = new Random();

    private static Supplier<Connection> db() throws Exception {
        Connection conn = new Sqlite.UncloseableConnection(Sqlite.build(":memory:"));
        return () -> conn;
    }

    @Before
    public void start() throws Exception {
        int port = TestPorts.getPort();
        Path dir = Files.createTempDirectory("s3-writer-ownership");
        server = new LocalS3Server(dir, BUCKET, ACCESS_KEY, SECRET_KEY, port);
        server.start();
        S3Config config = LocalS3Server.getConfig(BUCKET, ACCESS_KEY, SECRET_KEY, port);
        SqlSupplier cmds = new SqliteCommands();
        Cid ourId = Cid.buildCidV1(Cid.Codec.LibP2pKey, Multihash.Type.sha2_256, new byte[32]);
        usage = new JdbcUsageStore(db(), cmds);
        JdbcBatCave bats = new JdbcBatCave(db(), cmds);
        s3 = new S3BlockStorage(config, List.of(ourId),
                BlockStoreProperties.empty(), "localhost:8000",
                JdbcTransactionStore.build(db(), cmds),
                Builder.blockAuthoriser(Args.parse(new String[0]), bats, hasher), bats,
                new JdbcBlockMetadataStore(db(), cmds),
                usage,
                new RamBlockCache(1024, 100),
                new FileBlockBuffer(Files.createTempDirectory("s3-writer-ownership-buffer"), usage),
                Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE,
                false, dir, PartitionStatus.DONE, hasher,
                new RAMStorage(hasher), null);
        // every owner's blocks are on this node
        peergos.shared.corenode.CoreNode core = proxy(peergos.shared.corenode.CoreNode.class, (name, args) -> {
            if (name.equals("getStorageProviders"))
                return List.of(ourId.bareMultihash());
            if (name.equals("getUsername"))
                return Futures.of(usernames.get((PublicKeyHash) args[0]));
            throw new UnsupportedOperationException(name);
        });
        s3.setPki(core);
        peergos.shared.mutable.MutablePointers mutable = proxy(peergos.shared.mutable.MutablePointers.class, (name, args) -> {
            if (name.equals("getPointer"))
                return Futures.of(Optional.empty());
            throw new UnsupportedOperationException(name);
        });
        peergos.server.storage.admin.QuotaAdmin quotas = proxy(peergos.server.storage.admin.QuotaAdmin.class, (name, args) -> {
            if (name.equals("getQuota") && args.length == 1 && args[0] instanceof String)
                return 1024L * 1024 * 1024;
            if (name.equals("getLocalUsernames"))
                return new ArrayList<>(usernames.values());
            throw new UnsupportedOperationException(name);
        });
        checker = new SpaceCheckingKeyFilter(core, mutable, s3, hasher, quotas, usage, 3600);
        // as the server has it: every write goes through the space check
        filtered = new WriteFilter(s3, checker::allowWrite);
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, java.util.function.BiFunction<String, Object[], Object> handler) {
        return (T) java.lang.reflect.Proxy.newProxyInstance(type.getClassLoader(), new Class[]{type},
                (p, method, args) -> {
                    Object[] a = args == null ? new Object[0] : args;
                    try {
                        return handler.apply(method.getName(), a);
                    } catch (UnsupportedOperationException e) {
                        if (method.isDefault())
                            return java.lang.reflect.InvocationHandler.invokeDefault(p, method, args);
                        throw e;
                    }
                });
    }

    @After
    public void stop() {
        server.stop();
    }

    /** An identity whose key is stored where a lookup for it as owner finds it */
    private SigningPrivateKeyAndPublicHash user(String name) {
        SigningKeyPair pair = SigningKeyPair.random(crypto.random, crypto.signer);
        PublicKeyHash hash = ContentAddressedStorage.hashKey(pair.publicSigningKey);
        usage.addUserIfAbsent(name);
        usage.addWriter(name, hash);
        TransactionId tid = s3.startTransaction(hash).join();
        s3.put(pair.publicSigningKey.serialize(), false, tid, hash, false);
        s3.closeTransaction(hash, tid).join();
        usernames.put(hash, name);
        return new SigningPrivateKeyAndPublicHash(hash, pair.secretSigningKey);
    }

    private BlockWriteAuth authFor(PublicKeyHash owner, SigningPrivateKeyAndPublicHash signer, Cid block, int size) {
        List<Cid> hashes = List.of(block);
        byte[] payload = BlockWriteAuth.payload(owner, hashes, hasher).join();
        byte[] signature = signer.secret.signMessage(payload).join();
        return new BlockWriteAuth(hashes, List.of((long) size), List.of(Collections.emptyList()), signature);
    }

    /** As the server is wired: through the space check. Anything issued is uploaded to see where it lands. */
    @Test
    public void throughTheSpaceCheckAWriterOfAnotherOwnerGetsNoWrite() throws Exception {
        SigningPrivateKeyAndPublicHash victim = user("victim");
        SigningPrivateKeyAndPublicHash attacker = user("attacker");
        byte[] data = new byte[1000];
        random.nextBytes(data);
        Cid block = hasher.hash(data, true).join();

        TransactionId tid = s3.startTransaction(victim.publicKeyHash).join();
        List<PresignedUrl> urls;
        try {
            urls = filtered.authWrites(victim.publicKeyHash, attacker.publicKeyHash,
                    authFor(victim.publicKeyHash, attacker, block, data.length), true, tid).join();
        } catch (Exception refused) {
            Assert.assertFalse("the attacker's key counts as owned by the victim",
                    checker.isOwnedWriter(victim.publicKeyHash, attacker.publicKeyHash));
            return;
        }
        HttpUtil.putWithVersion(urls.get(0), data);
        Assert.fail("A presigned write into the victim's space was issued for the attacker's key"
                + (s3.getRaw(victim.publicKeyHash, block, Optional.empty()).join().isPresent() ? ", and the block is stored as the victim's" : ""));
    }

    @Test
    public void ownWritesStillWork() throws Exception {
        SigningPrivateKeyAndPublicHash user = user("owner");
        byte[] data = new byte[1000];
        random.nextBytes(data);
        Cid block = hasher.hash(data, true).join();
        TransactionId tid = s3.startTransaction(user.publicKeyHash).join();
        List<PresignedUrl> urls = filtered.authWrites(user.publicKeyHash, user.publicKeyHash,
                authFor(user.publicKeyHash, user, block, data.length), true, tid).join();
        HttpUtil.putWithVersion(urls.get(0), data);
        Assert.assertArrayEquals(data, s3.getRaw(user.publicKeyHash, block, Optional.empty()).join().get());
        Assert.assertTrue(checker.isOwnedWriter(user.publicKeyHash, user.publicKeyHash));
    }
}
