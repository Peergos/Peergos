package peergos.server.tests;

import org.junit.runner.*;
import org.junit.runners.*;

import java.util.*;

@RunWith(Parameterized.class)
public class IpfsUserTests extends UserTests {

    public IpfsUserTests(Random rnd) throws Exception {
        super("IPFS", rnd);
    }

    @Parameterized.Parameters()
    public static Collection<Object[]> parameters() {
        return Arrays.asList(new Object[][] {
                {new Random(0)}
        });
    }
}
