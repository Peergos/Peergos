package peergos.server.tests;

import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import peergos.server.*;
import peergos.server.storage.*;
import peergos.server.util.*;
import peergos.shared.*;
import peergos.shared.io.ipfs.MultiAddress;
import peergos.shared.io.ipfs.Multihash;
import peergos.shared.user.UserContext;
import peergos.shared.user.fs.*;

import java.net.URL;
import java.util.*;

import static peergos.server.Main.IPFS;
import static peergos.server.tests.UserTests.randomString;

public class P2pStreamNetworkTests {
    private static Args args = UserTests
            .buildArgs()
            .with("useIPFS", "true")
            .with(IpfsWrapper.IPFS_BOOTSTRAP_NODES, ""); // no bootstrapping

    private static Random random = new Random(0);
    private static List<NetworkAccess> nodes = new ArrayList<>();

    private final Crypto crypto = Main.initCrypto();

    @BeforeClass
    public static void init() throws Exception {
        // start pki node
        Main.PKI_INIT.main(args);
        NetworkAccess toPki = buildApi(args);
        Multihash pkiNodeId = toPki.dhtClient.id().get();
        nodes.add(toPki);
        int bootstrapSwarmPort = args.getInt("ipfs-swarm-port");

        // other nodes
        int ipfsApiPort = 9000 + random.nextInt(8000);
        int ipfsGatewayPort = 9000 + random.nextInt(8000);
        int ipfsSwarmPort = 9000 + random.nextInt(8000);
        int allowPort = 9000 + random.nextInt(8000);
        Args normalNode = UserTests.buildArgs()
                .with("ipfs-api-address", "/ip4/127.0.0.1/tcp/" + ipfsApiPort)
                .with("ipfs-gateway-address", "/ip4/127.0.0.1/tcp/" + ipfsGatewayPort)
                .with("allow-target", "/ip4/127.0.0.1/tcp/" + allowPort)
                .with("ipfs-swarm-port", "" + ipfsSwarmPort)
                .with(IpfsWrapper.IPFS_BOOTSTRAP_NODES, "" + Main.getLocalBootstrapAddress(bootstrapSwarmPort, pkiNodeId))
                .with("proxy-target", new MultiAddress(args.getArg("ipfs-gateway-address")).toString())
                .with("ipfs-api-address", Main.getLocalMultiAddress(ipfsApiPort).toString());

        IPFS.main(normalNode);

//        IPFS node2 = new IPFS(Main.getLocalMultiAddress(ipfsApiPort));
//        node2.swarm.connect(Main.getLocalBootstrapAddress(bootstrapSwarmPort, pkiNodeId).toString());

        nodes.add(buildProxiedApi(ipfsApiPort, ipfsGatewayPort, pkiNodeId));
    }

    private static NetworkAccess buildApi(Args args) throws Exception {
        return Builder.buildJavaNetworkAccess(new URL("http://localhost:" + args.getInt("port")), false).get();
    }

    private static NetworkAccess buildProxiedApi(int ipfsApiPort, int ipfsGatewayPort, Multihash pkinodeId) throws Exception {
        return Builder.buildJavaGatewayAccess(new URL("http://localhost:" + ipfsApiPort), new URL("http://localhost:" + ipfsGatewayPort), pkinodeId.toString()).get();
    }

    @Test
    public void writeViaUnrelatedNode() throws Exception {
        String username1 = generateUsername();
        String password1 = randomString();
        UserContext u1 = ensureSignedUp(username1, password1, nodes.get(0), crypto);

        byte[] data = "G'day mate!".getBytes();
        String filename = "hey.txt";
        FileWrapper root = u1.getUserRoot().get();
        FileWrapper upload = root.uploadOrReplaceFile(filename, new AsyncReader.ArrayBacked(data), data.length,
                nodes.get(1), crypto, x -> {}).get();
        Thread.sleep(7000);
        Optional<FileWrapper> file = ensureSignedUp(username1, password1, nodes.get(0), crypto)
                .getByPath("/" + username1 + "/" + filename).get();
        Assert.assertTrue(file.isPresent());
    }

    private String generateUsername() {
        return "test" + Math.abs(random.nextInt() % 10000);
    }

    public static UserContext ensureSignedUp(String username, String password, NetworkAccess network, Crypto crypto) throws Exception {
        return PeergosNetworkUtils.ensureSignedUp(username, password, network, crypto);
    }
}
