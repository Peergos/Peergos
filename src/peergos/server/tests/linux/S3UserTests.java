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

/** To run these tests download minio + mc to /usr/local/bin as in the github action. Then run with
 *  export MINIO_ROOT_USER=test
 *  export MINIO_ROOT_PASSWORD=testdslocal
 *  minio server ~/minio
 *  mc alias set minio 'http://local-s3.localhost:9000' 'test' 'testdslocal'
 *  mc mb ~/minio/local-s3
 */
public class S3UserTests extends UserTests {

    private static Random random = new Random(1);

    private static Args pkiArgs = buildArgs()
            .with("useIPFS", "true")
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

    private static final List<Args> argsToCleanUp = new ArrayList<>();
    private static List<ServerProcesses> services = new ArrayList<>();

    public S3UserTests() {
        super(getNetwork(), services.get(1).localApi);
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
        return Builder.buildNonCachingJavaNetworkAccess(local, false, 1_000, Optional.empty()).get();
    }

    @Test
    public void grantWriteToFileAndDeleteParent() throws IOException {
        PeergosNetworkUtils.grantWriteToFileAndDeleteParent(network, new Random(1));
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
