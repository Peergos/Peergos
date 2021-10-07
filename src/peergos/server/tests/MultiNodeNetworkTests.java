package peergos.server.tests;

import org.junit.*;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import peergos.server.*;
import peergos.server.storage.*;
import peergos.server.util.*;
import peergos.shared.*;
import peergos.shared.cbor.*;
import peergos.shared.corenode.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.crypto.symmetric.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.social.*;
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
            .with(IpfsWrapper.IPFS_BOOTSTRAP_NODES, ""); // no bootstrapping

    private static Random random = new Random(0);
    private static List<NetworkAccess> nodes = new ArrayList<>();
    private static List<UserService> services = new ArrayList<>();

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

    private static final List<Args> argsToCleanUp = new ArrayList<>();

    @AfterClass
    public static void cleanup() {
        try {Thread.sleep(2000);}catch (InterruptedException e) {}
        argsToCleanUp.add(args);
        for (Args toClean : argsToCleanUp) {
            Path peergosDir = toClean.fromPeergosDir("", "");
            System.out.println("Deleting " + peergosDir);
            deleteFiles(peergosDir.toFile());
        }
    }

    private NetworkAccess getNode(int i)  {
        return nodes.get(i);
    }

    private UserService getService(int i)  {
        return services.get(i);
    }

    @BeforeClass
    public static void init() throws Exception {
        // start pki node
        UserService pki = Main.PKI_INIT.main(args);
        PublicKeyHash peergosId = pki.coreNode.getPublicKeyHash("peergos").join().get();
        args = args.setArg("peergos.identity.hash", peergosId.toString());
        NetworkAccess toPki = buildApi(args);
        Multihash pkiNodeId = toPki.dhtClient.id().get();
        nodes.add(toPki);
        services.add(pki);
        int bootstrapSwarmPort = args.getInt("ipfs-swarm-port");

        // create two other nodes that use the first as a PKI-node
        for (int i = 0; i < 2; i++) {
            int ipfsApiPort = 9000 + random.nextInt(8000);
            int ipfsGatewayPort = 9000 + random.nextInt(8000);
            int ipfsSwarmPort = 9000 + random.nextInt(8000);
            int peergosPort = 9000 + random.nextInt(8000);
            Args normalNode = UserTests.buildArgs()
                    .with("useIPFS", "true")
                    .with("port", "" + peergosPort)
                    .with("pki-node-id", pkiNodeId.toString())
                    .with("peergos.identity.hash", peergosId.toString())
                    .with("ipfs-api-address", "/ip4/127.0.0.1/tcp/" + ipfsApiPort)
                    .with("ipfs-gateway-address", "/ip4/127.0.0.1/tcp/" + ipfsGatewayPort)
                    .with("ipfs-swarm-port", "" + ipfsSwarmPort)
                    .with(IpfsWrapper.IPFS_BOOTSTRAP_NODES, "" + Main.getLocalBootstrapAddress(bootstrapSwarmPort, pkiNodeId))
                    .with("proxy-target", Main.getLocalMultiAddress(peergosPort).toString())
                    .with("ipfs-api-address", Main.getLocalMultiAddress(ipfsApiPort).toString());
            argsToCleanUp.add(normalNode);
            UserService service = Main.PEERGOS.main(normalNode);
            services.add(service);

            IPFS ipfs = new IPFS(Main.getLocalMultiAddress(ipfsApiPort));
            ipfs.swarm.connect(Main.getLocalBootstrapAddress(bootstrapSwarmPort, pkiNodeId).toString());
            nodes.add(buildApi(normalNode));
        }
    }

    private static NetworkAccess buildApi(Args args) throws Exception {
        URL local = new URL("http://localhost:" + args.getInt("port"));
        return Builder.buildNonCachingJavaNetworkAccess(local, false, Optional.empty()).get();
    }

    @Before
    public void gc() {
        for (UserService service : services) {
            service.gc.collect(s -> Futures.of(true));
        }
    }

    @Test
    public void signUp() {
        UserContext context = ensureSignedUp(generateUsername(random), randomString(), getNode(iNode1), crypto);

        for (NetworkAccess node: nodes) {
            long usage = node.spaceUsage.getUsage(context.signer.publicKeyHash).join();
            byte[] signedTime = TimeLimitedClient.signNow(context.signer.secret);
            long quota = node.spaceUsage.getQuota(context.signer.publicKeyHash, signedTime).join();
            Assert.assertTrue(usage >0 && quota > 0);
        }
    }

    @Test
    public void migrate() {
        migrate(0);
        migrate(1);
        migrate(2);
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
            user = ensureSignedUp(username, password, node2, crypto).changePassword(password, newPassword).join();
            password = newPassword;
        }
        UserContext friend = ensureSignedUp(generateUsername(random), password, node1, crypto);
        friend.sendInitialFollowRequest(username).join();
        long usageVia1 = user.getSpaceUsage().join();

        // migrate to node2
        List<UserPublicKeyLink> existing = user.network.coreNode.getChain(username).join();
        List<UserPublicKeyLink> newChain = Migrate.buildMigrationChain(existing, newStorageNodeId, user.signer.secret);
        UserContext userViaNewServer = ensureSignedUp(username, password, node2, crypto);
        userViaNewServer.network.coreNode.migrateUser(username, newChain, originalNodeId).join();

        List<UserPublicKeyLink> chain = userViaNewServer.network.coreNode.getChain(username).join();
        Multihash storageNode = chain.get(chain.size() - 1).claim.storageProviders.stream().findFirst().get();
        Assert.assertTrue(storageNode.equals(newStorageNodeId));

        // test a fresh login on the new storage node
        UserContext postMigration = ensureSignedUp(username, password, node2.clear(), crypto);
        long usageVia2 = postMigration.getSpaceUsage().join();
        // Note we currently don't remove the old pointer after changing password,
        // so there is a 5kib reduction after migration per password change
        Assert.assertTrue(usageVia2 == usageVia1 || (nPasswordChanges > 0 && usageVia2 < usageVia1));

        // check pending followRequest was transferred
        List<FollowRequestWithCipherText> followRequests = postMigration.processFollowRequests().join();
        Assert.assertTrue(followRequests.size() == 1);

        // check a reverse migration can't be triggered by anyone else
        try {
            node1.coreNode.migrateUser(username, existing, newStorageNodeId).join();
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
                LocalDate.now().plusMonths(2), Arrays.asList(newStorageNodeId));
        UserPublicKeyLink evilUpdate = evilLast.withClaim(newClaim);
        List<UserPublicKeyLink> newChain = Arrays.asList(evilUpdate);
        UserContext userViaNewServer = ensureSignedUp(username, password, getNode(iNode2), crypto);
        try {
            userViaNewServer.network.coreNode.migrateUser(username, newChain, originalNodeId).join();
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
                getNode(iNode1), crypto, x -> {}, crypto.random.randomBytes(32)).get();
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
}
