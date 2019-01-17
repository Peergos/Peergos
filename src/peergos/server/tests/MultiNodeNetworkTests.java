package peergos.server.tests;

import org.junit.*;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import peergos.server.*;
import peergos.server.storage.*;
import peergos.server.util.*;
import peergos.shared.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.crypto.symmetric.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.social.*;
import peergos.shared.user.*;
import peergos.shared.user.fs.*;

import java.net.*;
import java.util.*;
import java.util.stream.*;

import static org.junit.Assert.*;
import static peergos.server.tests.UserTests.randomString;
import static peergos.server.util.PeergosNetworkUtils.ensureSignedUp;
import static peergos.server.util.PeergosNetworkUtils.generateUsername;

@RunWith(Parameterized.class)
public class MultiNodeNetworkTests {
    private static Args args = UserTests.buildArgs()
            .with("useIPFS", "true")
            .with(IpfsWrapper.IPFS_BOOTSTRAP_NODES, ""); // no bootstrapping

    private static Random random = new Random(0);
    private static List<NetworkAccess> nodes = new ArrayList<>();

    private final Crypto crypto = Crypto.initJava();

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

    private NetworkAccess getNode(int i)  {
        return nodes.get(i);
    }

    @BeforeClass
    public static void init() throws Exception {
        // start pki node
        Main.PKI.main(args);
        NetworkAccess toPki = buildApi(args);
        Multihash pkiNodeId = toPki.dhtClient.id().get();
        PublicKeyHash peergosId = PublicKeyHash.fromString(args.getArg("peergos.identity.hash"));
        nodes.add(toPki);
        int bootstrapSwarmPort = args.getInt("ipfs-config-swarm-port");

        // create two other nodes that use the first as a PKI-node
        for (int i = 0; i < 2; i++) {
            int ipfsApiPort = 9000 + random.nextInt(8000);
            int ipfsGatewayPort = 9000 + random.nextInt(8000);
            int ipfsSwarmPort = 9000 + random.nextInt(8000);
            int peergosPort = 9000 + random.nextInt(8000);
            Args normalNode = UserTests.buildArgs()
                    .with("useIPFS", "true")
                    .with("port", "" + peergosPort)
                    .with("pki-node-id", pkiNodeId.toBase58())
                    .with("peergos.identity.hash", peergosId.toString())
                    .with("ipfs-config-api-port", "" + ipfsApiPort)
                    .with("ipfs-config-gateway-port", "" + ipfsGatewayPort)
                    .with("ipfs-config-swarm-port", "" + ipfsSwarmPort)
                    .with(IpfsWrapper.IPFS_BOOTSTRAP_NODES, "" + Main.getLocalBootstrapAddress(bootstrapSwarmPort, pkiNodeId))
                    .with("proxy-target", Main.getLocalMultiAddress(peergosPort).toString())
                    .with("ipfs-api-address", Main.getLocalMultiAddress(ipfsApiPort).toString())
                    .with("mutable-pointers-file", ":memory:")
                    .with("social-sql-file", ":memory:");
            Main.PEERGOS.main(normalNode);

            IPFS ipfs = new IPFS(Main.getLocalMultiAddress(ipfsApiPort));
            ipfs.swarm.connect(Main.getLocalBootstrapAddress(bootstrapSwarmPort, pkiNodeId).toString());
            nodes.add(buildApi(normalNode));
        }
    }

    private static NetworkAccess buildApi(Args args) throws Exception {
        return NetworkAccess.buildJava(new URL("http://localhost:" + args.getInt("port"))).get();
    }

    @Test
    public void signUp() throws Exception {
        UserContext context = ensureSignedUp(generateUsername(random), randomString(), getNode(iNode1), crypto);
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
        FileWrapper upload = u1.getUserRoot().get().uploadFile(filename,
                new AsyncReader.ArrayBacked(data), data.length, getNode(iNode1), crypto.random, x -> { }, u1.fragmenter).get();
        Optional<FileWrapper> file = u1.getByPath("/" + username1 + "/" + filename).get();
        Assert.assertTrue(file.isPresent());
    }

    @Test
    public void  shareAndUnshareFileForReadAccess() throws Exception {
        int shareeCount = 2;
        PeergosNetworkUtils.shareAndUnshareFileForReadAccess(getNode(iNode1), getNode(iNode2), shareeCount, random);
    }

    @Test
    public void shareAndUnshareFolderForReadAccess() throws Exception {
        int shareeCount = 2;
        PeergosNetworkUtils.shareAndUnshareFolderForReadAccess(getNode(iNode1), getNode(iNode2), shareeCount, random);
    }

    @Test
    public void publicLinkToFile() throws Exception {
        PeergosNetworkUtils.publicLinkToFile(random, getNode(iNode1), getNode(iNode2));
    }
}
