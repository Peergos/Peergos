package peergos.server.tests;

import org.junit.*;
import org.junit.runner.*;
import org.junit.runners.*;
import peergos.server.storage.*;

import java.util.*;

@RunWith(Parameterized.class)
public class RamUserTests extends UserTests {
    private static TestBuilder RAM_USER_TEST_BUILDER = new TestBuilder(new Random(1), false);

    public RamUserTests(TestBuilder testBuilder) {
        super(testBuilder);
    }

    @Parameterized.Parameters()
    public static Collection<Object[]> parameters() {
        return Arrays.asList(new Object[][] {
                {RAM_USER_TEST_BUILDER}
        });
    }

    @BeforeClass public static void init() {
        RAM_USER_TEST_BUILDER.setup();
    }
}
