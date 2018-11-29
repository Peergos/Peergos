package peergos.server.tests;

import org.junit.*;
import org.junit.runner.*;
import org.junit.runners.*;
import peergos.server.*;
import peergos.server.util.*;

import java.util.*;

@RunWith(Parameterized.class)
public class RamUserTests extends UserTests {
    private static Args args = buildArgs().with("useIPFS", "false");

    public RamUserTests(Args args) {
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
