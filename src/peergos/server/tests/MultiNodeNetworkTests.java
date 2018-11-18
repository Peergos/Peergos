package peergos.server.tests;

import org.junit.*;
import peergos.server.*;
import peergos.server.util.*;
import peergos.shared.*;
import peergos.shared.crypto.symmetric.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.user.*;
import peergos.shared.user.fs.*;
import peergos.shared.util.*;

import java.net.*;
import java.util.*;
import java.util.stream.*;

import static org.junit.Assert.*;

public class MultiNodeNetworkTests {
    private static Args args = UserTests.buildArgs().with("useIPFS", "true");

    private static Random random = new Random(0);
    private static List<NetworkAccess> nodes = new ArrayList<>();

    private final Crypto crypto = Crypto.initJava();

    @BeforeClass
    public static void init() throws Exception {
        // start pki node
        Main.LOCAL.main(args);
        NetworkAccess toPki = buildApi(args);
        Multihash pkiNodeId = toPki.dhtClient.id().get();
        nodes.add(toPki);

        // other nodes
        int ipfsApiPort = 9000 + random.nextInt(8000);
        int ipfsGatewayPort = 9000 + random.nextInt(8000);
        int ipfsSwarmPort = 9000 + random.nextInt(8000);
        int peergosPort = 9000 + random.nextInt(8000);
        Args normalNode = UserTests.buildArgs()
                .with("useIPFS", "true")
                .with("port", "" + peergosPort)
                .with("pki-node-id", pkiNodeId.toBase58())
                .with("ipfs-config-api-port", "" + ipfsApiPort)
                .with("ipfs-config-gateway-port", "" + ipfsGatewayPort)
                .with("ipfs-config-swarm-port", "" + ipfsSwarmPort)
                .with("proxy-target", Main.getLocalMultiAddress(peergosPort).toString())
                .with("ipfs-api-address", Main.getLocalMultiAddress(ipfsApiPort).toString())
                .with("mutable-pointers-file", ":memory:")
                .with("social-sql-file", ":memory:");

        Main.PEERGOS.main(normalNode);
        nodes.add(buildApi(normalNode));
    }

    private static NetworkAccess buildApi(Args args) throws Exception {
        return NetworkAccess.buildJava(new URL("http://localhost:" + args.getInt("port"))).get();
    }

    @Test
    public void signUpOnNormalNode() throws Exception {
        UserContext context = ensureSignedUp(generateUsername(), randomString(), nodes.get(1), crypto);
    }

    @Test
    public void internodeFriends() throws Exception {
        String username1 = generateUsername();
        String password1 = randomString();
        UserContext u1 = ensureSignedUp(username1, password1, nodes.get(1), crypto);
        String username2 = generateUsername();
        String password2 = randomString();
        UserContext u2 = ensureSignedUp(username2, password2, nodes.get(0), crypto);

        u2.sendFollowRequest(username1, SymmetricKey.random()).get();
        List<FollowRequest> u1Requests = u1.processFollowRequests().get();
        assertTrue("Receive a follow request", u1Requests.size() > 0);
        u1.sendReplyFollowRequest(u1Requests.get(0), true, true).get();
        List<FollowRequest> u2FollowRequests = u2.processFollowRequests().get();
        Optional<FileTreeNode> u1ToU2 = u2.getByPath("/" + u1.username).get();
        assertTrue("Friend root present after accepted follow request", u1ToU2.isPresent());

        Optional<FileTreeNode> u2ToU1 = u1.getByPath("/" + u2.username).get();
        assertTrue("Friend root present after accepted follow request", u2ToU1.isPresent());

        Set<String> u1Following = ensureSignedUp(username1, password1, nodes.get(1).clear(), crypto).getSocialState().get()
                .followingRoots.stream().map(f -> f.getName())
                .collect(Collectors.toSet());
        assertTrue("Following correct", u1Following.contains(u2.username));

        Set<String> u2Following = ensureSignedUp(username2, password2, nodes.get(0).clear(), crypto).getSocialState().get()
                .followingRoots.stream().map(f -> f.getName())
                .collect(Collectors.toSet());
        assertTrue("Following correct", u2Following.contains(u1.username));
    }

//    @Ignore // ipfs seems to drop the p2p stream before the receiver can write the response
    @Test
    public void writeViaUnrelatedNode() throws Exception {
        String username1 = generateUsername();
        String password1 = randomString();
        UserContext u1 = ensureSignedUp(username1, password1, nodes.get(1), crypto);

        byte[] data = "G'day mate!".getBytes();
        String filename = "hey.txt";
        FileTreeNode upload = u1.getUserRoot().get().uploadFile(filename,
                new AsyncReader.ArrayBacked(data), data.length, nodes.get(0), crypto.random, x -> { }, u1.fragmenter).get();
        Optional<FileTreeNode> file = u1.getByPath("/" + username1 + "/" + filename).get();
        Assert.assertTrue(file.isPresent());
    }

    private String generateUsername() {
        return "test" + Math.abs(random.nextInt() % 10000);
    }

    public static UserContext ensureSignedUp(String username, String password, NetworkAccess network, Crypto crypto) throws Exception {
        return UserContext.ensureSignedUp(username, password, network, crypto).get();
    }

    public static void checkFileContents(byte[] expected, FileTreeNode f, UserContext context) throws Exception {
        long size = f.getFileProperties().size;
        byte[] retrievedData = Serialize.readFully(f.getInputStream(context.network, context.crypto.random,
            size, l-> {}).get(), f.getSize()).get();
        assertEquals(expected.length, size);
        assertTrue("Correct contents", Arrays.equals(retrievedData, expected));
    }

    public static String randomString() {
        return UUID.randomUUID().toString();
    }

    public static byte[] randomData(int length) {
        byte[] data = new byte[length];
        random.nextBytes(data);
        return data;
    }
}
