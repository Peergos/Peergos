package peergos.server.tests;

import org.junit.*;
import org.junit.runner.*;
import org.junit.runners.*;
import peergos.shared.cbor.*;
import peergos.shared.user.fs.*;
import peergos.shared.util.*;

import java.io.*;
import java.util.*;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(Parameterized.class)
public class FragmenterTest {
    private static Random random = new Random(666);

    private final Fragmenter fragmenter;

    public FragmenterTest(Fragmenter fragmenter) {
        this.fragmenter = fragmenter;
    }

    @Parameterized.Parameters(name = "{0}")
    public static Collection<Object[]> parameters() {
        return Arrays.asList(new Object[][]{
                {new SplitFragmenter()},
                {new ErasureFragmenter(ErasureFragmenter.ERASURE_ORIGINAL, ErasureFragmenter.ERASURE_ALLOWED_FAILURES)}
        });
    }

    @Test
    public void testSeries() throws IOException {
        for (int i = 1; i < 10; i++) {
            int length = random.nextInt(Chunk.MAX_SIZE);
            byte[] b = new byte[length];
            test(b);
        }
    }

    @Test
    public void testBoundary() throws IOException {
        List<Integer> sizes = Arrays.asList(Fragment.MAX_LENGTH, 2 * Fragment.MAX_LENGTH);
        for (Integer size : sizes) {
            byte[] b = new byte[size];
            test(b);
        }
    }

    private void test(byte[] input) throws IOException {
        random.nextBytes(input);


        byte[][] split = fragmenter.split(input);

        for (byte[] bytes : split) {
            int length = bytes.length;
            assertTrue(length > 0);
            assertTrue(length <= Fragment.MAX_LENGTH);
        }

        byte[] recombine = fragmenter.recombine(split, 0, input.length);

        assertTrue("recombine(split(input)) = input", Arrays.equals(input, recombine));
    }


    @Test
    public void serializationTest() throws IOException {
        byte[] raw = fragmenter.serialize();

        Fragmenter deserialize = Fragmenter.fromCbor(CborObject.fromByteArray(raw));

        assertEquals(fragmenter, deserialize);
    }
}
