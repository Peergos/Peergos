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
            .with(IpfsWrapper.IPFS_BOOTSTRAP_NODES, ""); // no bootstrapping

    public IpfsUserTests(NetworkAccess network) {
        super(network);
    }

    @Parameterized.Parameters()
    public static Collection<Object[]> parameters() throws Exception {
        Main.PKI_INIT.main(args);
        return Arrays.asList(new Object[][] {
                {NetworkAccess.buildJava(new URL("http://localhost:" + args.getInt("port"))).get()}
        });
    }

    @AfterClass
    public static void cleanup() {
        Path peergosDir = args.fromPeergosDir("", "");
        System.out.println("Deleting " + peergosDir);
        deleteFiles(peergosDir.toFile());
    }
}
