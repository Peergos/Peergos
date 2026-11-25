package peergos.server.tests.linux;

import org.junit.*;
import peergos.server.*;
import peergos.server.corenode.*;
import peergos.server.space.*;
import peergos.server.storage.*;
import peergos.server.tests.*;
import peergos.server.tests.util.*;
import peergos.server.util.*;
import peergos.shared.*;
import peergos.shared.corenode.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.*;
import peergos.shared.storage.auth.*;
import peergos.shared.user.*;
import peergos.shared.user.fs.*;
import peergos.shared.util.*;

import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

/** A mirror of a user on an s3 backed server drops their data once they delete their account. */
public class S3MirrorDeletedAccountTests {
    private static final Crypto crypto = Main.initCrypto();
    private static final String ACCESS_KEY = "test";
    private static final String SECRET_KEY = "testdslocal";

    private final List<LocalS3Server> s3Servers = new ArrayList<>();
    private final List<ServerProcesses> services = new ArrayList<>();
    private final List<Args> nodeArgs = new ArrayList<>();
    private final List<Path> s3Dirs = new ArrayList<>();
    private final List<String> buckets = new ArrayList<>();

    @After
    public void cleanup() {
        for (ServerProcesses service : services) {
            try { service.localApi.stop(); } catch (Exception e) {}
            try { if (service.p2pApi != null) service.p2pApi.stop(); } catch (Exception e) {}
            try { if (service.ipfs != null) service.ipfs.stop(); } catch (Exception e) {}
        }
        for (LocalS3Server s3 : s3Servers)
            s3.stop();
        for (Args a : nodeArgs)
            UserTests.deleteFiles(a.fromPeergosDir("", "").toFile());
        for (Path dir : s3Dirs)
            UserTests.deleteFiles(dir.toFile());
    }

    private static NetworkAccess buildApi(Args args) throws Exception {
        URL local = new URL("http://localhost:" + args.getInt("port"));
        return Builder.buildNonCachingJavaNetworkAccess(local, false, 0, Optional.empty(), Optional.empty(), Optional.empty()).get();
    }

    /** @return the index of the node's bucket */
    private int startS3(String bucket) throws Exception {
        int port = TestPorts.getPort();
        Path dir = Files.createTempDirectory("peergos-s3-mirror-delete");
        LocalS3Server s3 = new LocalS3Server(dir, bucket, ACCESS_KEY, SECRET_KEY, port);
        s3.start();
        s3Servers.add(s3);
        s3Dirs.add(dir);
        buckets.add(bucket);
        return port;
    }

    private Args s3Node(int s3Port, String bucket, Multihash pkiNodeId, PublicKeyHash peergosId, String bootstrap) throws Exception {
        S3Config cfg = LocalS3Server.getConfig(bucket, ACCESS_KEY, SECRET_KEY, s3Port);
        int ipfsApiPort = TestPorts.getPort();
        int ipfsGatewayPort = TestPorts.getPort();
        int ipfsSwarmPort = TestPorts.getTcpAndUdpPort();
        return UserTests.buildArgs()
                .with(Main.PEERGOS_PATH, Files.createTempDirectory("peergos-s3-node-" + System.nanoTime()).toString())
                .with("useIPFS", "true")
                .with("async-bootstrap", "false")
                .with("enable-gc", "true")
                .with("allow-external-login", "true")
                .with("port", "" + TestPorts.getPort())
                .with("pki-node-id", pkiNodeId.toString())
                .with("peergos.identity.hash", peergosId.toString())
                .with("ipfs-api-address", Main.getLocalMultiAddress(ipfsApiPort).toString())
                .with("ipfs-gateway-address", "/ip4/127.0.0.1/tcp/" + ipfsGatewayPort)
                .with("ipfs-swarm-port", "" + ipfsSwarmPort)
                .with(IpfsWrapper.IPFS_BOOTSTRAP_NODES, bootstrap)
                .with("proxy-target", Main.getLocalMultiAddress(TestPorts.getPort()).toString())
                .with("s3.bucket", cfg.bucket)
                .with("s3.region", cfg.region)
                .with("s3.region.endpoint", cfg.regionEndpoint)
                .with("s3.accessKey", cfg.accessKey)
                .with("s3.secretKey", cfg.secretKey)
                .with("mutable-pointers-file", "mutable.sql")
                .with("transactions-sql-file", "transactions.sql")
                .with("direct-s3-writes", "true")
                .with("authed-s3-reads", "true");
    }

    private ServerProcesses start(Args a) {
        ServerProcesses service = Main.PEERGOS.main(a);
        service.localApi.gc.stop();
        services.add(service);
        nodeArgs.add(a);
        return service;
    }

    private long s3BlockCount(int s3Index, String username) throws Exception {
        Path userBlocks = s3Dirs.get(s3Index).resolve(buckets.get(s3Index)).resolve(username);
        if (! userBlocks.toFile().exists())
            return 0;
        try (Stream<Path> files = Files.walk(userBlocks)) {
            return files.filter(Files::isRegularFile).count();
        }
    }

    private static void forceUpdatePki(ServerProcesses service) {
        CoreNode target = ((SignUpFilter) ((CorenodeEventPropagator) service.localApi.coreNode).target).target;
        if (target instanceof MirrorCoreNode)
            ((MirrorCoreNode) target).forceUpdate();
    }

    private static void awaitMirrorCaughtUp(Args mirror, ServerProcesses home, PublicKeyHash owner) throws Exception {
        JdbcIpnsAndSocial mirrored = Builder.buildRawPointers(mirror, Builder.getDBConnector(mirror, "mutable-pointers-file"));
        for (int attempt = 0; attempt < 300; attempt++) {
            Optional<byte[]> atHome = home.localApi.mutable.getPointer(owner, owner).join();
            Optional<byte[]> atMirror = mirrored.getPointer(owner).join();
            if (atHome.isPresent() && atMirror.isPresent() && Arrays.equals(atHome.get(), atMirror.get()))
                return;
            Thread.sleep(1_000);
        }
        throw new IllegalStateException("Mirror didn't catch up with the home server's pointer in time");
    }

    private static void giveQuota(Args a, String username, long bytes) {
        Args withDb = a.with("quotas-sql-file", a.getArg("quotas-sql-file", "quotas.sql"));
        JdbcQuotas quotas = JdbcQuotas.build(Builder.getDBConnector(withDb, "quotas-sql-file"), Builder.getSqlCommands(withDb));
        if (! quotas.setQuota(username, bytes))
            throw new IllegalStateException("Could not give " + username + " quota");
    }

    @Test
    public void mirrorDropsDeletedAccount() throws Exception {
        Args pkiArgs = UserTests.buildArgs()
                .with("useIPFS", "true")
                .with("async-bootstrap", "true")
                .with("enable-gc", "true")
                .with("allow-external-login", "true")
                .removeArg(IpfsWrapper.IPFS_BOOTSTRAP_NODES);
        ServerProcesses pki = Main.PKI_INIT.main(pkiArgs);
        pki.localApi.gc.stop();
        services.add(pki);
        nodeArgs.add(pkiArgs);
        PublicKeyHash peergosId = pki.localApi.coreNode.getPublicKeyHash("peergos").join().get();
        Multihash pkiNodeId = pki.localApi.storage.id().join().bareMultihash();
        String bootstrap = Main.getLocalBootstrapAddress(pkiArgs.getInt("ipfs-swarm-port"), pkiNodeId).toString();

        int homeS3 = startS3("home-bucket");
        Args homeArgs = s3Node(homeS3, "home-bucket", pkiNodeId, peergosId, bootstrap);
        ServerProcesses home = start(homeArgs);
        bootstrap += "," + Main.getLocalBootstrapAddress(homeArgs.getInt("ipfs-swarm-port"),
                home.localApi.storage.id().join().bareMultihash());

        String username = PeergosNetworkUtils.generateUsername(new Random());
        String password = UserTests.randomString();
        UserContext user = PeergosNetworkUtils.ensureSignedUp(username, password, buildApi(homeArgs), crypto);
        byte[] data = new byte[1024 * 1024];
        new Random(43).nextBytes(data);
        user.getUserRoot().join().uploadOrReplaceFile("somedata.bin", AsyncReader.build(data),
                data.length, user.network, crypto, () -> false, x -> {}).join();
        PublicKeyHash owner = user.signer.publicKeyHash;

        WriterData userData = WriterData.fromCbor(UserContext.getWriterDataCbor(user.network, username).join().right);
        SigningKeyPair loginKeys = UserUtil.generateUser(username, password, crypto,
                userData.generationAlgorithm.get()).join().getUser();
        BatWithId mirrorBat = user.getMirrorBat().join().get();

        int mirrorS3Port = startS3("mirror-bucket");
        Args mirrorArgs = s3Node(mirrorS3Port, "mirror-bucket", pkiNodeId, peergosId, bootstrap)
                .with("mirror.username", username)
                .with("mirror.bat", mirrorBat.encode())
                .with("login-keypair", loginKeys.toString());
        giveQuota(mirrorArgs, username, 1024L * 1024 * 1024);
        ServerProcesses mirror = start(mirrorArgs);
        forceUpdatePki(mirror);

        awaitMirrorCaughtUp(mirrorArgs, home, owner);
        long before = s3BlockCount(1, username);
        Assert.assertTrue("mirror holds the user's blocks", before > 0);

        user.deleteAccount(password, UserTests::noMfa).join();
        awaitMirrorCaughtUp(mirrorArgs, home, owner);
        MultiNodeNetworkTests.expireTransactions(mirrorArgs, owner);
        MultiNodeNetworkTests.expireTransactions(homeArgs, owner);
        mirror.localApi.gc.collect(s -> Futures.of(true));
        home.localApi.gc.collect(s -> Futures.of(true));

        Assert.assertEquals("home still holds blocks of a deleted account", 0, s3BlockCount(0, username));
        Assert.assertEquals("mirror still holds blocks of a deleted account", 0, s3BlockCount(1, username));
    }
}
