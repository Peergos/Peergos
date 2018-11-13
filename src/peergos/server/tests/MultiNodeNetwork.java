package peergos.server.tests;

import org.junit.*;
import peergos.server.*;
import peergos.server.util.*;
import peergos.shared.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.user.*;
import peergos.shared.user.fs.*;
import peergos.shared.util.*;

import java.net.*;
import java.util.*;

import static org.junit.Assert.*;

public class MultiNodeNetwork {
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
        int peergosPort = 9000 + random.nextInt(8000);
        Args normalNode = UserTests.buildArgs()
                .with("useIPFS", "true")
                .with("port", "" + peergosPort)
                .with("pki-node-id", pkiNodeId.toBase58())
                .with("ipfs-config-api-port", "" + ipfsApiPort)
                .with("ipfs-config-gateway-port", "" + ipfsGatewayPort)
                .with("proxy-target", Main.getLocalMultiAddress(peergosPort).toString())
                .with("ipfs-api-address", Main.getLocalMultiAddress(ipfsApiPort).toString())
                .with("mutable-pointers-file", ":memory:")
                .with("social-sql-file", ":memory:");

        Main.ENSURE_IPFS_INSTALLED.main(normalNode);
        Main.IPFS.main(normalNode);
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
