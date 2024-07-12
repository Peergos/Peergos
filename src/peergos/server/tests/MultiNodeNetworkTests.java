package peergos.server.tests;

import org.junit.*;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import peergos.server.*;
import peergos.server.storage.*;
import peergos.server.tests.util.*;
import peergos.server.util.*;
import peergos.shared.*;
import peergos.shared.cbor.*;
import peergos.shared.corenode.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.crypto.symmetric.*;
import peergos.shared.io.ipfs.Cid;
import peergos.shared.io.ipfs.Multihash;
import peergos.shared.social.*;
import peergos.shared.storage.auth.*;
import peergos.shared.user.*;
import peergos.shared.user.fs.*;
import peergos.shared.util.*;

import java.net.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

import static org.junit.Assert.*;
import static peergos.server.tests.UserTests.deleteFiles;
import static peergos.server.tests.UserTests.randomString;
import static peergos.server.tests.PeergosNetworkUtils.ensureSignedUp;
import static peergos.server.tests.PeergosNetworkUtils.generateUsername;
import static peergos.server.tests.PeergosNetworkUtils.*;

@RunWith(Parameterized.class)
public class MultiNodeNetworkTests {
    private static Args args = UserTests.buildArgs()
            .with("useIPFS", "true")
            .with("enable-gc", "true")
            .with(IpfsWrapper.IPFS_BOOTSTRAP_NODES, ""); // no bootstrapping
    private static Random random = new Random(0);
    private static List<NetworkAccess> nodes = new ArrayList<>();
    private static List<ServerProcesses> services = new ArrayList<>();
    private static final List<Args> argsToCleanUp = new ArrayList<>();
    private final Crypto crypto = Main.initCrypto();

    private final int iNode1, iNode2;

//    @Parameterized.Parameters()
    private final String node1Name;

//    @Parameterized.Parameter()
    private final String node2Name;


    public MultiNodeNetworkTests(int iNode1, int iNode2) {
        this.iNode1 = iNode1;
        this.iNode2 = iNode2;
        this.node1Name = iNode1 == 0 ? "PKI-node" : String.format("normal-node %d", iNode1);
        this.node2Name = iNode2 == 0 ? "PKI-node" : String.format("normal-node %d", iNode2);
    }


    @Parameterized.Parameters(name="nodes: {0}, {1} (0 == PKI, > 0 normal)")
    public static Collection<Object[]> parameters() {
        return Arrays.asList(new Object[][] {
                {0, 1}, // PKI, normal-1
                {1, 0}, // normal-2, PKI
                {2, 1}  // normal-1, normal-2
        });
    }

    @AfterClass
    public static void cleanup() {
        try {Thread.sleep(2000);}catch (InterruptedException e) {}
        for (Args toClean : argsToCleanUp) {
            Path peergosDir = toClean.fromPeergosDir("", "");
            System.out.println("Deleting " + peergosDir);
            deleteFiles(peergosDir.toFile());
        }
    }

    private NetworkAccess getNode(int i)  {
        return nodes.get(i);
    }

    private void stopServer(int i)  {
        ServerProcesses server = services.get(i);
        server.localApi.stop();
        server.p2pApi.stop();
        if (server.ipfs != null)
            server.ipfs.stop();
    }

    private void startServer(int i) throws Exception  {
        if (i == 0)
            throw new IllegalStateException("Restarting PKI not yet supported in test");
        Args startArgs = argsToCleanUp.get(i).with(IpfsWrapper.IPFS_BOOTSTRAP_NODES, buildBootstrapList(i));
        ServerProcesses service = Main.PEERGOS.main(startArgs);
        service.localApi.gc.stop();
        services.set(i, service);
        nodes.set(i, buildApi(startArgs));
    }

    private static String buildBootstrapList(int exclude) {
        StringBuilder res = new StringBuilder();
        for (int i=0; i < 3; i++) {
            if (i != exclude && services.size() > i) {
                res.append(","+Main.getLocalBootstrapAddress(
                        argsToCleanUp.get(i).getInt("ipfs-swarm-port"),
                        services.get(i).localApi.storage.id().join().bareMultihash()));
            }
        }
        return res.toString().substring(1);
    }

    private void rotateServerIdentity(int i)  {
        if (i == 0)
            throw new IllegalStateException("Rotating PKI identity not yet supported in test");
        Optional<Path> config = Optional.of(argsToCleanUp.get(i).getPeergosDirChild("config"));
        Args withPrivKey = Args.parse(new String[0], config, false);
        ServerIdentity.ROTATE.main(withPrivKey);
        Args withNewPrivKey = Args.parse(new String[0], config, false);
        String bootstrapList = Main.getLocalBootstrapAddress(argsToCleanUp.get(0).getInt("ipfs-swarm-port"), services.get(0).localApi.storage.id().join().bareMultihash()).toString();
        for (int n = 1; n < 3; n++)
            if (n != i)
                bootstrapList += "," + Main.getLocalBootstrapAddress(argsToCleanUp.get(n).getInt("ipfs-swarm-port"), services.get(n).localApi.storage.id().join().bareMultihash());
        argsToCleanUp.set(i, withNewPrivKey.with(IpfsWrapper.IPFS_BOOTSTRAP_NODES, bootstrapList));
    }

    private UserService getService(int i)  {
        return services.get(i).localApi;
    }

    @BeforeClass
    public static void init() throws Exception {
        System.getProperties().setProperty("io.netty.eventLoopThreads", "1");
        // start pki node
        ServerProcesses pki = Main.PKI_INIT.main(args);
        PublicKeyHash peergosId = pki.localApi.coreNode.getPublicKeyHash("peergos").join().get();
        args = args.setArg("peergos.identity.hash", peergosId.toString());
        NetworkAccess toPki = buildApi(args);
        Multihash pkiNodeId = toPki.dhtClient.id().get();
        nodes.add(toPki);
        services.add(pki);
        pki.localApi.gc.stop();
        argsToCleanUp.add(args);
        int bootstrapSwarmPort = args.getInt("ipfs-swarm-port");
        String bootstrapList = Main.getLocalBootstrapAddress(bootstrapSwarmPort, pkiNodeId).toString();

        // create two other nodes that use the first as a PKI-node
        for (int i = 0; i < 2; i++) {
            int ipfsApiPort = TestPorts.getPort();System.out.println("node" + (i+1) + " base port: " + ipfsApiPort);
            int ipfsGatewayPort = TestPorts.getPort();
            int ipfsSwarmPort = TestPorts.getPort();
            int peergosPort = TestPorts.getPort();
            int proxyTargetPort = TestPorts.getPort();
            Args normalNode = UserTests.buildArgs()
                    .with("useIPFS", "true")
                    .with("enable-gc", "true")
                    .with("port", "" + peergosPort)
                    .with("pki-node-id", pkiNodeId.toString())
                    .with("peergos.identity.hash", peergosId.toString())
                    .with("ipfs-api-address", "/ip4/127.0.0.1/tcp/" + ipfsApiPort)
                    .with("ipfs-gateway-address", "/ip4/127.0.0.1/tcp/" + ipfsGatewayPort)
                    .with("ipfs-swarm-port", "" + ipfsSwarmPort)
                    .with(IpfsWrapper.IPFS_BOOTSTRAP_NODES, bootstrapList)
                    .with("proxy-target", Main.getLocalMultiAddress(proxyTargetPort).toString())
                    .with("ipfs-api-address", Main.getLocalMultiAddress(ipfsApiPort).toString());
            argsToCleanUp.add(normalNode);
            ServerProcesses service = Main.PEERGOS.main(normalNode);
            services.add(service);

            service.localApi.gc.stop();
            Multihash ourId = service.localApi.storage.id().get();
            bootstrapList += "," + Main.getLocalBootstrapAddress(ipfsSwarmPort, ourId);

            nodes.add(buildApi(normalNode));
        }
    }

    private static NetworkAccess buildApi(Args args) throws Exception {
        URL local = new URL("http://localhost:" + args.getInt("port"));
        return Builder.buildNonCachingJavaNetworkAccess(local, false, 1_000, Optional.empty()).get();
    }

    @Before
    public void gc() {
        for (ServerProcesses service : services) {
            service.localApi.gc.collect(s -> Futures.of(true));
        }
    }

    @Test
    public void signUp() {
        UserContext context = ensureSignedUp(generateUsername(random), randomString(), getNode(iNode1), crypto);

        for (NetworkAccess node: nodes) {
            long usage = node.spaceUsage.getUsage(context.signer.publicKeyHash,
                    TimeLimitedClient.signNow(context.signer.secret).join()).join();
            byte[] signedTime = TimeLimitedClient.signNow(context.signer.secret).join();
            long quota = node.spaceUsage.getQuota(context.signer.publicKeyHash, signedTime).join();
            Assert.assertTrue(usage >0 && quota > 0);
        }
    }

    @Test
    public void migrateWithZeroPwdChanges() {
        migrate(0);
    }

    @Test
    public void migrateWith1PwdChanges() {
        migrate(1);
    }

    public void migrate(int nPasswordChanges) {
        if (iNode1 == 0 || iNode2 == 0)
            return; // Don't test migration to/from pki node
        String username = generateUsername(random);
        String password = randomString();
        NetworkAccess node1 = getNode(iNode1);
        Multihash originalNodeId = node1.dhtClient.id().join();
        NetworkAccess node2 = getNode(iNode2);
        Multihash newStorageNodeId = node2.dhtClient.id().join();

        UserContext user = ensureSignedUp(username, password, node1, crypto);
        for (int i=0; i < nPasswordChanges; i++) {
            String newPassword = randomString();
            user = ensureSignedUp(username, password, node2, crypto).changePassword(password, newPassword, UserTests::noMfa).join();
            password = newPassword;
        }
        // make sure we have some raw fragments
        String filename = "somedata.bin";
        user.getUserRoot().join().uploadOrReplaceFile(filename, AsyncReader.build(new byte[10*1024*1024]),
                10*1024*1024, user.network, crypto, x -> {}).join();

        // check retrieval of cryptree node or data both fail without bat
        FileWrapper file = user.getByPath("/" + username + "/" + filename).join().get();
        WritableAbsoluteCapability cap = file.writableFilePointer();
        WritableAbsoluteCapability badCap = cap.withMapKey(cap.getMapKey(), Optional.empty());
        Assert.assertTrue(node1.clear().getFile(badCap, username).join().isEmpty());

        Multihash fragment = file.getPointer().fileAccess.toCbor().links().get(0);
        CompletableFuture<Optional<byte[]>> raw = node1.clear().dhtClient.getRaw(user.signer.publicKeyHash, (Cid) fragment, Optional.empty());
        Assert.assertTrue(raw.isCompletedExceptionally() || raw.join().isEmpty());

        UserContext friend = ensureSignedUp(generateUsername(random), password, node1, crypto);
        friend.sendInitialFollowRequest(username).join();

        // migrate to node2
        List<UserPublicKeyLink> existing = user.network.coreNode.getChain(username).join();
        List<UserPublicKeyLink> newChain = Migrate.buildMigrationChain(existing, newStorageNodeId, user.signer.secret);
        UserContext userViaNewServer = ensureSignedUp(username, password, node2, crypto);

        List<BatWithId> bats = node1.batCave.getUserBats(username, userViaNewServer.signer).join();
        List<BatWithId> batsViaNewNode = node2.batCave.getUserBats(username, userViaNewServer.signer).join();
        Assert.assertTrue(bats.equals(batsViaNewNode));
        Optional<BatWithId> mirrorBat = Optional.of(bats.get(bats.size() - 1));
        long usageVia1 = user.getSpaceUsage().join();
        userViaNewServer.network.coreNode.migrateUser(username, newChain, originalNodeId, mirrorBat, LocalDateTime.now(), usageVia1).join();

        List<UserPublicKeyLink> chain = userViaNewServer.network.coreNode.getChain(username).join();
        Multihash storageNode = chain.get(chain.size() - 1).claim.storageProviders.stream().findFirst().get();
        Assert.assertTrue(storageNode.equals(newStorageNodeId));

        // test a fresh login on the new storage node
        UserContext postMigration = ensureSignedUp(username, password, node2.clear(), crypto);
        long usageVia2 = postMigration.getSpaceUsage().join();
        // Note we currently don't remove the old pointer after changing password,
        // so there is a 5kib reduction after migration per password change
        Assert.assertTrue("Usage after migrate: " + usageVia2 + ", usage before: " + usageVia1,
                usageVia2 == usageVia1 || (nPasswordChanges > 0 && usageVia2 < usageVia1));

        // check pending followRequest was transferred
        List<FollowRequestWithCipherText> followRequests = postMigration.processFollowRequests().join();
        Assert.assertTrue(followRequests.size() == 1);

        // check bats were transferred
        List<BatWithId> postBats = postMigration.network.batCave.getUserBats(username, postMigration.signer).join();
        Assert.assertTrue("mirror bats transferred", postBats.equals(bats));

        // check a reverse migration can't be triggered by anyone else
        try {
            node1.coreNode.migrateUser(username, existing, newStorageNodeId, mirrorBat, LocalDateTime.now(), usageVia2).join();
            throw new RuntimeException("Shouldn't get here!");
        } catch (CompletionException e) {
            if (! e.getCause().getMessage().startsWith("Migration+claim+has+earlier+expiry+than+current+one"))
                throw new RuntimeException(e.getCause());
        }

        try { // check a direct update call with old chain also fails
            ProofOfWork work = crypto.hasher.generateProofOfWork(ProofOfWork.DEFAULT_DIFFICULTY,
                    new CborObject.CborList(existing).serialize()).join();
            node1.coreNode.updateChain(username, existing, work, "").join();
            throw new RuntimeException("Shouldn't get here!");
        } catch (CompletionException e) {
            if (! e.getCause().getMessage().startsWith("New%2Bclaim%2Bchain%2Bexpiry%2Bbefore%2Bexisting"))
                throw new RuntimeException(e.getCause());
        }
    }

    @Test
    public void invalidMigrate() {
        if (iNode1 == 0 || iNode2 == 0)
            return; // Don't test migration to/from pki node
        String username = generateUsername(random);
        String password = randomString();
        NetworkAccess node1 = getNode(iNode1);
        Multihash originalNodeId = node1.dhtClient.id().join();
        UserContext user = ensureSignedUp(username, password, node1, crypto);
        String evilusername = randomUsername("evil", new Random());
        UserContext evil = ensureSignedUp(evilusername, password, node1, crypto);

        // try to migrate with an invalid claim chain
        UserService node2 = getService(iNode2);
        List<UserPublicKeyLink> existing = user.network.coreNode.getChain(username).join();
        Multihash newStorageNodeId = node2.storage.id().join();

        List<UserPublicKeyLink> evilChain = evil.network.coreNode.getChain(evilusername).join();
        UserPublicKeyLink evilLast = evilChain.get(0);
        UserPublicKeyLink.Claim newClaim = UserPublicKeyLink.Claim.build(username, evil.signer.secret,
                LocalDate.now().plusMonths(2), Arrays.asList(newStorageNodeId)).join();
        UserPublicKeyLink evilUpdate = evilLast.withClaim(newClaim);
        List<UserPublicKeyLink> newChain = Arrays.asList(evilUpdate);
        UserContext userViaNewServer = ensureSignedUp(username, password, getNode(iNode2), crypto);
        List<BatWithId> bats = user.network.batCave.getUserBats(username, userViaNewServer.signer).join();
        Optional<BatWithId> mirrorBat = Optional.of(bats.get(bats.size() - 1));
        try {
            userViaNewServer.network.coreNode.migrateUser(username, newChain, originalNodeId, mirrorBat, LocalDateTime.now(), 1_000_000).join();
            throw new RuntimeException("Shouldn't get here!");
        } catch (CompletionException e) {}

        List<UserPublicKeyLink> chain = userViaNewServer.network.coreNode.getChain(username).join();
        Multihash storageNode = chain.get(chain.size() - 1).claim.storageProviders.stream().findFirst().get();
        Assert.assertTrue(storageNode.equals(originalNodeId));
    }

    @Test
    public void internodeFriends() throws Exception {
        String username1 = generateUsername(random);
        String password1 = randomString();
        UserContext u1 = ensureSignedUp(username1, password1, getNode(iNode2), crypto);
        String username2 = generateUsername(random);
        String password2 = randomString();
        UserContext u2 = ensureSignedUp(username2, password2, getNode(iNode1), crypto);

        u2.sendFollowRequest(username1, SymmetricKey.random()).get();
        List<FollowRequestWithCipherText> u1Requests = u1.processFollowRequests().get();
        assertTrue("Receive a follow request", u1Requests.size() > 0);
        u1.sendReplyFollowRequest(u1Requests.get(0), true, true).get();
        List<FollowRequestWithCipherText> u2FollowRequests = u2.processFollowRequests().get();
        Optional<FileWrapper> u1ToU2 = u2.getByPath("/" + u1.username).get();
        assertTrue("Friend root present after accepted follow request", u1ToU2.isPresent());

        Optional<FileWrapper> u2ToU1 = u1.getByPath("/" + u2.username).get();
        assertTrue("Friend root present after accepted follow request", u2ToU1.isPresent());

        Set<String> u1Following = ensureSignedUp(username1, password1, getNode(iNode2).clear(), crypto).getSocialState().get()
                .followingRoots.stream().map(f -> f.getName())
                .collect(Collectors.toSet());
        assertTrue("Following correct", u1Following.contains(u2.username));

        Set<String> u2Following = ensureSignedUp(username2, password2, getNode(iNode1).clear(), crypto).getSocialState().get()
                .followingRoots.stream().map(f -> f.getName())
                .collect(Collectors.toSet());
        assertTrue("Following correct", u2Following.contains(u1.username));
    }

    @Test
    public void writeViaUnrelatedNode() throws Exception {
        String username1 = generateUsername(random);
        String password1 = randomString();
        UserContext u1 = ensureSignedUp(username1, password1, getNode(iNode2), crypto);

        byte[] data = "G'day mate!".getBytes();
        String filename = "hey.txt";
        FileWrapper root = u1.getUserRoot().get();
        FileWrapper upload = root.uploadOrReplaceFile(filename, new AsyncReader.ArrayBacked(data), data.length,
                getNode(iNode1), crypto, x -> {}).get();
        Thread.sleep(10_000); // make sure pointer cache is invalidated
        Optional<FileWrapper> file = u1.getByPath("/" + username1 + "/" + filename).get();
        Assert.assertTrue(file.isPresent());
    }

    @Test
    public void grantAndRevokeFileReadAccess() throws Exception {
        int shareeCount = 2;
        PeergosNetworkUtils.grantAndRevokeFileReadAccess(getNode(iNode1), getNode(iNode2), shareeCount, random);
    }

    @Test
    public void grantAndRevokeDirReadAccess() throws Exception {
        int shareeCount = 2;
        PeergosNetworkUtils.grantAndRevokeDirReadAccess(getNode(iNode1), getNode(iNode2), shareeCount, random);
    }

    @Test
    public void publicLinkToFile() throws Exception {
        PeergosNetworkUtils.publicLinkToFile(random, getNode(iNode1), getNode(iNode2));
    }

    @Test
    public void serverIdentityRotation() throws Exception {
        if (iNode1 == 0 || iNode2 == 0)
            return; // Don't test migration to/from pki node

        String password = randomString();
        String username = generateUsername(random);
        UserContext context = ensureSignedUp(username, password, getNode(iNode1), crypto);
        byte[] fileData = new byte[6*1024*1024];
        new Random(28).nextBytes(fileData);
        String filename = "somefile.bin";
        context.getUserRoot().join().uploadOrReplaceFile(filename, AsyncReader.build(fileData),
                fileData.length, context.network, crypto,  x -> {}).join();
        FileWrapper file = context.getByPath(PathUtil.get(context.username + "/" + filename)).join().get();
        AbsoluteCapability cap = file.getPointer().capability.readOnly();

        context.getUserRoot().join().uploadOrReplaceFile(filename+"2", AsyncReader.build(fileData),
                fileData.length, context.network, crypto,  x -> {}).join();
        FileWrapper file2 = context.getByPath(PathUtil.get(context.username + "/" + filename + "2")).join().get();
        AbsoluteCapability cap2 = file2.getPointer().capability.readOnly();

        Multihash originalHost = context.network.coreNode.getHomeServer(context.username).join().get();

        // rotate server identity, and check file cap works from other server
        stopServer(iNode1);
        rotateServerIdentity(iNode1);
        startServer(iNode1);
        Thread.sleep(60_000); // wait one DNS cycle

        // login through other server
        ensureSignedUp(username, password, getNode(iNode2), crypto);

        FileWrapper fromOtherServer = getNode(iNode2).getFile(cap, context.username).join().get();

        // update owner host and check cap still works from other server
        context = ensureSignedUp(username, password, getNode(iNode1), crypto);
        context.ensureCurrentHost().join();
        Multihash updatedHost = context.network.coreNode.getHomeServer(context.username).join().get();
        Assert.assertTrue(! updatedHost.equals(originalHost));

        // login again through other server
        ensureSignedUp(username, password, getNode(iNode2), crypto);
        FileWrapper afterRotation = getNode(iNode2).getFile(cap2, context.username).join().get();
    }
}
