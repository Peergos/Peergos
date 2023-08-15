package peergos.server.tests;

import org.junit.*;
import peergos.shared.io.ipfs.bases.*;
import peergos.shared.util.*;

import java.util.*;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

public class MultibaseTests {

    @Test
    public void base58Test() {
        List<String> examples = Arrays.asList(
                "zQmdM1TrjBJnYzzESATtrrMNPAtjJdqfcV2vF1kM39DY7cc",
                "zQmPZ9gcCEpqKTo6aq61g2nXGUhM4iCL3ewB6LDXZCtioEB",
                "zQmatmE9msSfkKxoffpHwNLNKgwZG8eT9Bud6YoPab52vpy",
                "z11");
        for (String example: examples) {
            byte[] output = Multibase.decode(example);
            String encoded = Multibase.encode(Multibase.Base.Base58BTC, output);
            assertEquals(example, encoded);
        }
    }

    @Test
    public void zeroBytesBase58() {
        for (int i=0; i < 32; i++) {
            String encoded = Multibase.encode(Multibase.Base.Base58BTC, new byte[i]);
            byte[] output = Multibase.decode(encoded);
            if (! Arrays.equals(output, new byte[i]))
                throw new IllegalStateException("Failed to round trip zero array of length " + i);
        }
    }

    @Test
    public void base16Test() {
        List<String> examples = Arrays.asList("f234abed8debede",
                "f87ad873defc2b288",
                "f",
                "f01",
                "f0123456789abcdef");
        for (String example: examples) {
            byte[] output = Multibase.decode(example);
            String encoded = Multibase.encode(Multibase.Base.Base16, output);
            assertEquals(example, encoded);
        }
    }

    @Test
    public void base32Test() {
        List<String> examples = Arrays.asList("G'day mate!", "How's it going?");
        for (String example: examples) {
            String encoded = Multibase.encode(Multibase.Base.Base32, example.getBytes());
            byte[] output = Multibase.decode(encoded);
            assertArrayEquals(example.getBytes(), output);
        }

        List<Pair<String, byte[]>> fullExamples = Arrays.asList(
                new Pair<>("baaaaaaaa", ArrayOps.hexToBytes("0000000000")),
                new Pair<>("bjv2wy5djmjqxgzjanfzsaylxmvzw63lfeeqfy3zp", ArrayOps.hexToBytes("4D756C74696261736520697320617765736F6D6521205C6F2F")),
                new Pair<>("birswgzloorzgc3djpjssazlwmvzhs5dinfxgoijbee", ArrayOps.hexToBytes("446563656e7472616c697a652065766572797468696e67212121")),
                new Pair<>("bafyreif3n3yb2jkftteahuegjtpeej6nfn3zszpldxzuvpvoyiwcb6sc5i", ArrayOps.hexToBytes("01711220bb6ef01d25459cc803d0864cde4227cd2b779965eb1df34abeaec22c20fa42ea"))
        );
        for (Pair<String, byte[]> fullExample : fullExamples) {
            byte[] output = Multibase.decode(fullExample.left);
            assertArrayEquals(output, fullExample.right);
            String encoded = Multibase.encode(Multibase.Base.Base32, fullExample.right);
            assertEquals(fullExample.left, encoded);
        }
    }

    @Test
    public void invalidBase16Test() {
        String example = "f012"; // hex string of odd length
        byte[] output = Multibase.decode(example);
        String encoded = Multibase.encode(Multibase.Base.Base16, output);
        assertNotEquals(example, encoded);

    }

    @Test (expected = NumberFormatException.class)
    public void invalidWithExceptionBase16Test() {
        String example = "f0g"; // g char is not allowed in hex
        Multibase.decode(example);
    }
}
