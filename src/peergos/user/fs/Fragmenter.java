package peergos.user.fs;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.ByteArrayOutputStream;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public interface Fragmenter {

    public byte[][] split(byte[] input);

    public byte[] recombine(byte[][] encoded, int inputLength);

    public void serialize(DataOutput dout) throws IOException;

    public static Fragmenter deserialize(DataInput din) throws IOException {
        int val = din.readInt();
        Type type  = Type.ofVal(val);
        switch (type) {
            case SIMPLE:
                return new peergos.user.fs.SplitFragmenter();
            case ERASURE_CODING:
                int nOriginalFragments = din.readInt();
                int nAllowedFailures = din.readInt();
                return new peergos.user.fs.ErasureFragmenter(nOriginalFragments, nAllowedFailures);
            default:
                throw new IllegalStateException();
        }
    }

    enum Type  {
        SIMPLE(0),
        ERASURE_CODING(1);

        public final int val;

        Type(int val) {
            this.val = val;
        }

        private static Map<Integer, Type> MAP = Stream.of(values())
                .collect(
                        Collectors.toMap(
                                e -> e.val,
                                e -> e));
        public static Type ofVal(int val) {
            Type type = MAP.get(val);
            if (type == null)
                throw new IllegalStateException("No type for value "+ val);
            return type;
        }
    }

    @RunWith(Parameterized.class)
    public static class FragmenterTest  {
        private static Random random = new Random(666);

        private final Fragmenter  fragmenter;

        public  FragmenterTest(Fragmenter fragmenter) {
            this.fragmenter = fragmenter;
        }

        @Parameterized.Parameters(name = "{0}")
        public static Collection<Object[]> parameters() {
            return Arrays.asList(new Object[][]{
//                    {new SplitFragmenter()},
                    {new peergos.user.fs.ErasureFragmenter(EncryptedChunk.ERASURE_ORIGINAL, EncryptedChunk.ERASURE_ALLOWED_FAILURES)}
            });
        }

        @Test
        public void testSeries() throws IOException {
            for (int i = 0; i < 10; i++) {
                int length = random.nextInt(Chunk.MAX_SIZE * i);
                byte[] b = new byte[length];
                test(b);
            }
        }
        @Test public void testBoundary()  throws IOException {
            List<Integer> sizes = Arrays.asList(Chunk.MAX_SIZE, 2 * Chunk.MAX_SIZE);
            for (Integer size : sizes) {
                byte[] b = new byte[size];
                test(b);
            }
        }

        private void test(byte[] input)  throws IOException {
            random.nextBytes(input);


            byte[][] split = fragmenter.split(input);

//            int nChunk  = input.length / Chunk.MAX_SIZE;
//            if (input.length % Chunk.MAX_SIZE > 0)
//                nChunk++;
//
//            assertEquals(split.length, nChunk);

            for (byte[] bytes : split) {
                int length = bytes.length;
                assertTrue(length > 0);
                assertTrue(length <= Chunk.MAX_SIZE);
            }

            byte[] recombine = fragmenter.recombine(split, input.length);

            assertTrue("recombine(split(input)) = input", Arrays.equals(input, recombine));
        }
    }
}
