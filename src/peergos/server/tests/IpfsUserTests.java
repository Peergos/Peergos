package peergos.server.tests;

import org.junit.BeforeClass;
import org.junit.runner.*;
import org.junit.runners.*;

import java.util.*;

@RunWith(Parameterized.class)
public class IpfsUserTests extends UserTests {
    private static TestBuilder IPFS_USER_TEST_BUILDER = new TestBuilder(new Random(0), true);

    public IpfsUserTests(TestBuilder testBuilder) {
        super(testBuilder);
    }

    @Parameterized.Parameters()
    public static Collection<Object[]> parameters() {
        return Arrays.asList(new Object[][] {
                {IPFS_USER_TEST_BUILDER}
        });
    }

    @BeforeClass public static void init() {
        IPFS_USER_TEST_BUILDER.setup();
    }


}
