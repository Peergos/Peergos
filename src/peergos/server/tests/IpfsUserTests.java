package peergos.server.tests;

import org.junit.*;
import org.junit.runner.*;
import org.junit.runners.*;
import peergos.server.*;
import peergos.server.storage.IpfsWrapper;
import peergos.server.util.*;
import peergos.shared.*;

import java.net.*;
import java.nio.file.*;
import java.util.*;

@RunWith(Parameterized.class)
public class IpfsUserTests extends UserTests {

    private static Args args = buildArgs()
            .with("useIPFS", "true")
//            .with("enable-gc", "true")
//            .with("gc.period.millis", "10000")
            .with("allow-target", "/ip4/127.0.0.1/tcp/8002")
            .with("collect-metrics", "true")
            .with("metrics.address", "localhost")
            .with("ipfs.metrics.port", "9000")
            .with(IpfsWrapper.IPFS_BOOTSTRAP_NODES, ""); // no bootstrapping

    public IpfsUserTests(NetworkAccess network, UserService service) {
        super(network, service);
    }

    @Parameterized.Parameters()
    public static Collection<Object[]> parameters() throws Exception {
        UserService service = Main.PKI_INIT.main(args);
        return Arrays.asList(new Object[][] {
                {
                        Builder.buildJavaNetworkAccess(new URL("http://localhost:" + args.getInt("port")), false).join(),
                        service
                }
        });
    }

    @AfterClass
    public static void cleanup() {
        try {Thread.sleep(2000);}catch (InterruptedException e) {}
        Path peergosDir = args.fromPeergosDir("", "");
        System.out.println("Deleting " + peergosDir);
        deleteFiles(peergosDir.toFile());
    }

    @Override
    public Args getArgs() {
        return args;
    }
}
