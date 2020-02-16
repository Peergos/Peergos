package peergos.server.tests;

import org.junit.*;
import peergos.server.*;
import peergos.server.storage.*;
import peergos.server.util.*;

import java.nio.file.*;
import java.util.*;

public class S3IpfsPluginTest {

    @Test
    public void loadPlugin() throws Exception {

        Path peergosDir = Files.createTempDirectory("peergos");
        Random r = new Random();
        int ipfsApiPort = 9000 + r.nextInt(50_000);
        int ipfsGatewayPort = 9000 + r.nextInt(50_000);
        int ipfsSwarmPort = 9000 + r.nextInt(50_000);
        Args args = Args.parse(new String[]{
                "-ipfs-api-address", "/ip4/127.0.0.1/tcp/" + ipfsApiPort,
                "-ipfs-gateway-address", "/ip4/127.0.0.1/tcp/" + ipfsGatewayPort,
                "-ipfs-swarm-port", Integer.toString(ipfsSwarmPort),
                "-logToConsole", "true",
                Main.PEERGOS_PATH, peergosDir.toString(),
                "-proxy-target", "/ip4/127.0.0.1/tcp/80",
                "-ipfs-plugins", "go-ds-s3",
                "-s3.bucket", "atest",
                "-s3.accessKey", "fake-key",
                "-s3.secretKey", "fake-key",
                "-s3.region.endpoint", "us-east-1.linodeobjects.com",
                "-s3.region", "us-east-1",
        });
        Main.ENSURE_IPFS_INSTALLED.main(args);
        IpfsWrapper ipfs = IpfsWrapper.build(args);
        ipfs.configure();
    }
}