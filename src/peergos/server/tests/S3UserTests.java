package peergos.server.tests;

import org.junit.*;
import org.junit.runner.*;
import org.junit.runners.*;
import peergos.server.*;
import peergos.server.storage.*;
import peergos.server.util.*;
import peergos.shared.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.cid.*;
import peergos.shared.storage.*;

import java.net.*;
import java.nio.file.*;
import java.util.*;

public class S3UserTests extends UserTests {

    private static Args pkiArgs = buildArgs()
            .with("useIPFS", "true")
            .with("allow-target", "/ip4/127.0.0.1/tcp/8002")
            .with(IpfsWrapper.IPFS_BOOTSTRAP_NODES, ""); // no bootstrapping

    private static Args withS3(Args in) {
        return in.with("s3.bucket", "local-s3")
                .with("s3.region", "local")
                .with("s3.region.endpoint", "localhost:9000")
                .with("direct-s3-writes", "true")
                .with("authed-s3-reads", "true")
                .with("s3.accessKey", "test")
                .with("s3.secretKey", "testdslocal");
    }

    private static Random random = new Random(1);
    private static final List<Args> argsToCleanUp = new ArrayList<>();
    private static List<UserService> services = new ArrayList<>();

    public S3UserTests() {
        super(getNetwork(), services.get(1));
    }

    private static NetworkAccess getNetwork() {
        try {
            return Builder.buildJavaNetworkAccess(new URL("http://localhost:" + argsToCleanUp.get(argsToCleanUp.size() - 1).getInt("port")), false).join();
        } catch (MalformedURLException e) {
            throw new RuntimeException(e);
        }
    }

    @BeforeClass
    public static void init() throws Exception {
        // start pki node
        UserService pki = Main.PKI_INIT.main(pkiArgs);
        PublicKeyHash peergosId = pki.coreNode.getPublicKeyHash("peergos").join().get();
        pkiArgs = pkiArgs.setArg("peergos.identity.hash", peergosId.toString());
        NetworkAccess toPki = buildApi(pkiArgs);
        Cid pkiNodeId = toPki.dhtClient.id().get();
        int bootstrapSwarmPort = pkiArgs.getInt("ipfs-swarm-port");
        services.add(pki);

        // start ipfs S3 node
        int ipfsApiPort = 9000 + random.nextInt(8000);
        int ipfsGatewayPort = 9000 + random.nextInt(8000);
        int ipfsSwarmPort = 9000 + random.nextInt(8000);
        int proxyTargetPort = 9000 + random.nextInt(8000);
        int allowPort = 9000 + random.nextInt(8000);
        Args ipfsArgs = withS3(buildArgs())
                .with("useIPFS", "true")
                .with("ipfs-api-address", "/ip4/127.0.0.1/tcp/" + ipfsApiPort)
                .with("ipfs-gateway-address", "/ip4/127.0.0.1/tcp/" + ipfsGatewayPort)
                .with("allow-target", "/ip4/127.0.0.1/tcp/" + allowPort)
                .with("ipfs-swarm-port", "" + ipfsSwarmPort)
                .with(IpfsWrapper.IPFS_BOOTSTRAP_NODES, "" + Main.getLocalBootstrapAddress(bootstrapSwarmPort, pkiNodeId))
                .with("proxy-target", Main.getLocalMultiAddress(proxyTargetPort).toString())
                .with("ipfs-api-address", Main.getLocalMultiAddress(ipfsApiPort).toString());
        IpfsWrapper ipfs = Main.INSTALL_AND_RUN_IPFS.main(ipfsArgs);
        argsToCleanUp.add(ipfsArgs);

        // start direct S3 node
        int peergosPort = 9000 + random.nextInt(8000);
        Cid ourId = new ContentAddressedStorage.HTTP(new JavaPoster(new URL("http://localhost:" + ipfsApiPort), false), false, crypto.hasher).id().get();
        Args peergosArgs = withS3(buildArgs())
                .with("port", "" + peergosPort)
                .with("useIPFS", "false")
                .with("enable-gc", "false")
                .with("ipfs-api-address", "/ip4/127.0.0.1/tcp/" + ipfsApiPort)
                .with("ipfs-gateway-address", "/ip4/127.0.0.1/tcp/" + ipfsGatewayPort)
                .with("allow-target", "/ip4/127.0.0.1/tcp/" + allowPort)
                .with("proxy-target", Main.getLocalMultiAddress(proxyTargetPort).toString())
                .with("ipfs.id", ourId.toString())
                .with("pki-node-id", pkiNodeId.toString())
                .with("peergos.identity.hash", peergosId.toString());
        UserService peergosS3 = Main.PEERGOS.main(peergosArgs);
        argsToCleanUp.add(peergosArgs);
        services.add(peergosS3);
    }

    private static NetworkAccess buildApi(Args args) throws Exception {
        URL local = new URL("http://localhost:" + args.getInt("port"));
        return Builder.buildNonCachingJavaNetworkAccess(local, false, Optional.empty()).get();
    }

    @AfterClass
    public static void cleanup() {
        try {Thread.sleep(2000);}catch (InterruptedException e) {}
        argsToCleanUp.add(pkiArgs);
        for (Args toClean : argsToCleanUp) {
            Path peergosDir = toClean.fromPeergosDir("", "");
            System.out.println("Deleting " + peergosDir);
            deleteFiles(peergosDir.toFile());
        }
    }

    @Override
    public Args getArgs() {
        return pkiArgs;
    }
}
