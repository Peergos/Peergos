package peergos.server.tests;

import org.junit.runner.*;
import org.junit.runners.*;

import java.util.*;

@RunWith(Parameterized.class)
public class RamUserTests extends UserTests {

    public RamUserTests(Random rnd) throws Exception {
        super("RAM", rnd);
    }

    @Parameterized.Parameters()
    public static Collection<Object[]> parameters() {
        return Arrays.asList(new Object[][] {
                {new Random(1)}
        });
    }
}
