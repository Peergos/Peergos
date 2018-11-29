package peergos.server.tests;

import org.junit.BeforeClass;
import org.junit.runner.*;
import org.junit.runners.*;
import peergos.server.*;
import peergos.server.storage.IpfsWrapper;
import peergos.server.util.*;

import java.util.*;

@RunWith(Parameterized.class)
public class IpfsUserTests extends UserTests {

    private static Args args = buildArgs()
            .with("useIPFS", "true")
            .with(IpfsWrapper.IPFS_BOOTSTRAP_NODES, ""); // no bootstrapping

    public IpfsUserTests(Args args) {
        super(args);
    }

    @Parameterized.Parameters()
    public static Collection<Object[]> parameters() {
        return Arrays.asList(new Object[][] {
                {args}
        });
    }

    @BeforeClass
    public static void init() {
        Main.LOCAL.main(args);
    }
}
