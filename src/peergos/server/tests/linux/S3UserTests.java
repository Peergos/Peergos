package peergos.server.tests.linux;

import org.junit.*;
import peergos.server.*;
import peergos.server.storage.*;
import peergos.server.tests.*;
import peergos.server.tests.util.*;
import peergos.server.util.*;
import peergos.shared.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.Cid;
import peergos.shared.storage.*;
import peergos.shared.storage.auth.*;
import peergos.shared.user.*;
import peergos.shared.util.*;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;

public class S3UserTests extends UserTests {

    private static Random random = new Random(1);

    private static final String S3_BUCKET = "local-s3";
    private static final String S3_ACCESS_KEY = "test";
    private static final String S3_SECRET_KEY = "testdslocal";
    private static LocalS3Server localS3;
    private static int s3Port;

    private static Args pkiArgs = buildArgs()
            .with("useIPFS", "true")
            .with("async-bootstrap", "true")
            .removeArg(IpfsWrapper.IPFS_BOOTSTRAP_NODES); // no bootstrapping

    private static Args withS3(Args in) {
        S3Config cfg = LocalS3Server.getConfig(S3_BUCKET, S3_ACCESS_KEY, S3_SECRET_KEY, s3Port);
        return in.with("s3.bucket", cfg.bucket)
                .with("s3.region", cfg.region)
                .with("s3.region.endpoint", cfg.regionEndpoint)
                .with("direct-s3-writes", "true")
                .with("authed-s3-reads", "true")
                .with("s3.accessKey", cfg.accessKey)
                .with("s3.secretKey", cfg.secretKey)
                .with("allow-external-login", "true");
    }

    private static final List<Args> argsToCleanUp = new ArrayList<>();
    private static List<ServerProcesses> services = new ArrayList<>();

    public S3UserTests() {
        super(getNetwork(), services.get(1).localApi);
    }

    private static NetworkAccess getNetwork() {
        try {
            return Builder.buildJavaNetworkAccess(new URL("http://localhost:" + argsToCleanUp.get(argsToCleanUp.size() - 1).getInt("port")), false, Optional.empty(), Optional.empty()).join();
        } catch (MalformedURLException e) {
            throw new RuntimeException(e);
        }
    }

    @BeforeClass
    public static void init() throws Exception {
        // start local S3 server
        s3Port = TestPorts.getPort();
        Path s3Dir = Files.createTempDirectory("peergos-s3-test");
        localS3 = new LocalS3Server(s3Dir, S3_BUCKET, S3_ACCESS_KEY, S3_SECRET_KEY, s3Port);
        localS3.start();

        // start pki node
        ServerProcesses pki = Main.PKI_INIT.main(pkiArgs);
        PublicKeyHash peergosId = pki.localApi.coreNode.getPublicKeyHash("peergos").join().get();
        pkiArgs = pkiArgs.setArg("peergos.identity.hash", peergosId.toString());
        NetworkAccess toPki = buildApi(pkiArgs);
        Cid pkiNodeId = toPki.dhtClient.id().get();
        int bootstrapSwarmPort = pkiArgs.getInt("ipfs-swarm-port");
        services.add(pki);
        UserContext peergos = UserContext.signIn("peergos", "testpassword", m -> Futures.errored(new IllegalStateException("No MFA")),
                toPki, crypto).join();
        BatWithId mirrorBat = peergos.getMirrorBat().join().get();

        // start ipfs S3 node
        int ipfsApiPort = TestPorts.getPort();
        int ipfsGatewayPort = TestPorts.getPort();
        int ipfsSwarmPort = TestPorts.getPort();
        int proxyTargetPort = TestPorts.getPort();
        int allowPort = TestPorts.getPort();
        Args ipfsArgs = withS3(buildArgs())
                .with("useIPFS", "true")
                .with("async-bootstrap", "true")
                .with("ipfs-api-address", "/ip4/127.0.0.1/tcp/" + ipfsApiPort)
                .with("ipfs-gateway-address", "/ip4/127.0.0.1/tcp/" + ipfsGatewayPort)
                .with("allow-target", "/ip4/127.0.0.1/tcp/" + allowPort)
                .with("ipfs-swarm-port", "" + ipfsSwarmPort)
                .with(IpfsWrapper.IPFS_BOOTSTRAP_NODES, "" + Main.getLocalBootstrapAddress(bootstrapSwarmPort, pkiNodeId))
                .with("proxy-target", Main.getLocalMultiAddress(proxyTargetPort).toString())
                .with("ipfs-api-address", Main.getLocalMultiAddress(ipfsApiPort).toString());
        IpfsWrapper ipfs = Main.IPFS.main(ipfsArgs);
        argsToCleanUp.add(ipfsArgs);

        // start direct S3 node
        int peergosPort = TestPorts.getPort();
        Cid ourId = new ContentAddressedStorage.HTTP(new JavaPoster(new URL("http://localhost:" + ipfsApiPort), false), false, crypto.hasher).id().get();
        Args peergosArgs = ipfsArgs
                .with("port", "" + peergosPort)
                .with("useIPFS", "false")
                .with("enable-gc", "false")
                .with("mirror.username", "peergos")
                .with("mirror.bat", mirrorBat.encode())
                .with("ipfs-api-address", "/ip4/127.0.0.1/tcp/" + ipfsApiPort)
                .with("ipfs-gateway-address", "/ip4/127.0.0.1/tcp/" + ipfsGatewayPort)
                .with("allow-target", "/ip4/127.0.0.1/tcp/" + allowPort)
                .with("proxy-target", Main.getLocalMultiAddress(proxyTargetPort).toString())
                .with("ipfs.id", ourId.toString())
                .with("pki-node-id", pkiNodeId.toString())
                .with("peergos.identity.hash", peergosId.toString());
        ServerProcesses peergosS3 = Main.PEERGOS.main(peergosArgs);
        argsToCleanUp.add(peergosArgs);
        services.add(peergosS3);
    }

    private static NetworkAccess buildApi(Args args) throws Exception {
        URL local = new URL("http://localhost:" + args.getInt("port"));
        return Builder.buildNonCachingJavaNetworkAccess(local, false, 1_000, Optional.empty(), Optional.empty(), Optional.empty()).get();
    }

    @Test
    public void grantWriteToFileAndDeleteParent() throws IOException {
        PeergosNetworkUtils.grantWriteToFileAndDeleteParent(network, new Random(1));
    }

    @AfterClass
    public static void cleanup() {
        try {Thread.sleep(2000);}catch (InterruptedException e) {}
        if (localS3 != null)
            localS3.stop();
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
