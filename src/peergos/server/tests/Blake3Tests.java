package peergos.server.tests;

import org.junit.Assert;
import org.junit.Test;
import peergos.server.Main;
import peergos.shared.Crypto;
import peergos.shared.crypto.hash.Blake3;
import peergos.shared.crypto.hash.Hasher;
import peergos.shared.user.fs.AsyncReader;
import peergos.shared.util.ArrayOps;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class Blake3Tests {

    private static final int MiB = 1024 * 1024;
    /** What a file is hashed in: 4 MiB is 4096 BLAKE3 chunks, so it is a whole subtree. */
    private static final int CHUNK = 4 * MiB;

    /** The input the official test vectors use: a repeating 251 byte pattern. */
    private static byte[] vectorInput(int length) {
        byte[] out = new byte[length];
        for (int i = 0; i < length; i++)
            out[i] = (byte) (i % 251);
        return out;
    }

    @Test
    public void correct() {
        Blake3 b3 = Blake3.initHash();
        byte[] data = new byte[4096];
        new Random(42).nextBytes(data);
        b3.update(data);
        byte[] hash = b3.doFinalize(32);
        Assert.assertEquals(ArrayOps.bytesToHex(hash), "3393625f68437730188ea2f582ac38f9ec6ead68ea6351caf36030d4a7b94ac5");
    }

    /** The official BLAKE3 test vectors, so this agrees with b3sum and every other implementation. */
    @Test
    public void officialVectors() {
        // length, then the first 32 bytes of the official extended output
        String[][] vectors = {
                {"0", "af1349b9f5f9a1a6a0404dea36dcc9499bcb25c9adc112b7cc9a93cae41f3262"},
                {"1", "2d3adedff11b61f14c886e35afa036736dcd87a74d27b5c1510225d0f592e213"},
                {"2", "7b7015bb92cf0b318037702a6cdd81dee41224f734684c2c122cd6359cb1ee63"},
                {"3", "e1be4d7a8ab5560aa4199eea339849ba8e293d55ca0a81006726d184519e647f"},
                {"4", "f30f5ab28fe047904037f77b6da4fea1e27241c5d132638d8bedce9d40494f32"},
                {"5", "b40b44dfd97e7a84a996a91af8b85188c66c126940ba7aad2e7ae6b385402aa2"},
                {"6", "06c4e8ffb6872fad96f9aaca5eee1553eb62aed0ad7198cef42e87f6a616c844"},
                {"7", "3f8770f387faad08faa9d8414e9f449ac68e6ff0417f673f602a646a891419fe"},
                {"8", "2351207d04fc16ade43ccab08600939c7c1fa70a5c0aaca76063d04c3228eaeb"},
                {"63", "e9bc37a594daad83be9470df7f7b3798297c3d834ce80ba85d6e207627b7db7b"},
                {"64", "4eed7141ea4a5cd4b788606bd23f46e212af9cacebacdc7d1f4c6dc7f2511b98"},
                {"65", "de1e5fa0be70df6d2be8fffd0e99ceaa8eb6e8c93a63f2d8d1c30ecb6b263dee"},
                {"127", "d81293fda863f008c09e92fc382a81f5a0b4a1251cba1634016a0f86a6bd640d"},
                {"128", "f17e570564b26578c33bb7f44643f539624b05df1a76c81f30acd548c44b45ef"},
                {"129", "683aaae9f3c5ba37eaaf072aed0f9e30bac0865137bae68b1fde4ca2aebdcb12"},
                {"1023", "10108970eeda3eb932baac1428c7a2163b0e924c9a9e25b35bba72b28f70bd11"},
                {"1024", "42214739f095a406f3fc83deb889744ac00df831c10daa55189b5d121c855af7"},
                {"1025", "d00278ae47eb27b34faecf67b4fe263f82d5412916c1ffd97c8cb7fb814b8444"},
                {"2048", "e776b6028c7cd22a4d0ba182a8bf62205d2ef576467e838ed6f2529b85fba24a"},
                {"2049", "5f4d72f40d7a5f82b15ca2b2e44b1de3c2ef86c426c95c1af0b6879522563030"},
                {"3072", "b98cb0ff3623be03326b373de6b9095218513e64f1ee2edd2525c7ad1e5cffd2"},
                {"3073", "7124b49501012f81cc7f11ca069ec9226cecb8a2c850cfe644e327d22d3e1cd3"},
                {"4096", "015094013f57a5277b59d8475c0501042c0b642e531b0a1c8f58d2163229e969"},
                {"4097", "9b4052b38f1c5fc8b1f9ff7ac7b27cd242487b3d890d15c96a1c25b8aa0fb995"},
                {"5120", "9cadc15fed8b5d854562b26a9536d9707cadeda9b143978f319ab34230535833"},
                {"5121", "628bd2cb2004694adaab7bbd778a25df25c47b9d4155a55f8fbd79f2fe154cff"},
                {"6144", "3e2e5b74e048f3add6d21faab3f83aa44d3b2278afb83b80b3c35164ebeca205"},
                {"6145", "f1323a8631446cc50536a9f705ee5cb619424d46887f3c376c695b70e0f0507f"},
                {"7168", "61da957ec2499a95d6b8023e2b0e604ec7f6b50e80a9678b89d2628e99ada77a"},
                {"7169", "a003fc7a51754a9b3c7fae0367ab3d782dccf28855a03d435f8cfe74605e7817"},
                {"8192", "aae792484c8efe4f19e2ca7d371d8c467ffb10748d8a5a1ae579948f718a2a63"},
                {"8193", "bab6c09cb8ce8cf459261398d2e7aef35700bf488116ceb94a36d0f5f1b7bc3b"},
                {"16384", "f875d6646de28985646f34ee13be9a576fd515f76b5b0a26bb324735041ddde4"},
                {"31744", "62b6960e1a44bcc1eb1a611a8d6235b6b4b78f32e7abc4fb4c6cdcce94895c47"},
                {"102400", "bc3e3d41a1146b069abffad3c0d44860cf664390afce4d9661f7902e7943e085"},
        };
        for (String[] vector : vectors) {
            int length = Integer.parseInt(vector[0]);
            byte[] hash = Blake3.hash(vectorInput(length));
            Assert.assertEquals("vector for length " + length, vector[1], ArrayOps.bytesToHex(hash));
        }
    }

    /** A chaining value is not a hash: finalising when we should not would silently produce one. */
    @Test
    public void chainingValueIsNotAHash() {
        byte[] oneChunk = vectorInput(1024);
        Assert.assertNotEquals(ArrayOps.bytesToHex(Blake3.hash(oneChunk)),
                ArrayOps.bytesToHex(Blake3.subtreeChainingValue(oneChunk, 0, oneChunk.length, 0)));
    }

    /**
     * The property everything downstream depends on: hashing an input in aligned subtrees and
     * merging the chaining values gives the hash of the whole input.
     */
    @Test
    public void subtreesRebuildTheWholeHash() {
        for (int[] shape : new int[][]{{2, 1}, {4, 1}, {4, 2}, {8, 2}, {8, 4}, {16, 4}, {64, 16}, {1024, 256}}) {
            int totalChunks = shape[0], perSubtree = shape[1];
            byte[] input = vectorInput(totalChunks * 1024);
            List<byte[]> cvs = new ArrayList<>();
            for (int i = 0; i < totalChunks; i += perSubtree)
                cvs.add(Blake3.subtreeChainingValue(input, i * 1024, perSubtree * 1024, i));
            Assert.assertEquals("subtrees of " + perSubtree + " chunks of a " + totalChunks + " chunk input",
                    ArrayOps.bytesToHex(Blake3.hash(input)), ArrayOps.bytesToHex(Blake3.mergeAsRoot(cvs)));
        }
    }

    /**
     * The real shape: a file hashed in 4 MiB pieces. Lengths either side of the chunk boundary,
     * including partial last chunks, which a file of arbitrary length always has.
     */
    @Test
    public void fourMiBChunksOfAFile() {
        int[] lengths = {
                CHUNK, CHUNK + 1, CHUNK - 1, 2 * CHUNK, 2 * CHUNK + 1, 2 * CHUNK - 1,
                3 * CHUNK, 3 * CHUNK + 4096, 4 * CHUNK, 4 * CHUNK + 1023,
                CHUNK + 1024, CHUNK + 1025, 5 * CHUNK + 12345,
        };
        for (int length : lengths) {
            byte[] input = vectorInput(length);
            Assert.assertEquals("file of " + length + " bytes hashed in 4MiB chunks",
                    ArrayOps.bytesToHex(Blake3.hash(input)),
                    ArrayOps.bytesToHex(hashInChunks(input, CHUNK)));
        }
    }

    /** Smaller chunk sizes, so the spread covers many tree shapes cheaply. */
    @Test
    public void everyLengthAroundAChunkBoundary() {
        int chunkSize = 4096; // 4 BLAKE3 chunks, the same arithmetic at 1/1024 the size
        for (int length = 1; length <= 6 * chunkSize + 3; length++) {
            if (length > 3 * chunkSize && length % 7 != 0)
                continue; // the interesting lengths are near the boundaries; thin out the rest
            byte[] input = vectorInput(length);
            Assert.assertEquals("length " + length,
                    ArrayOps.bytesToHex(Blake3.hash(input)),
                    ArrayOps.bytesToHex(hashInChunks(input, chunkSize)));
        }
    }

    @Test
    public void subtreesMustBeWholeAlignedPowersOfTwo() {
        byte[] input = vectorInput(8 * 1024);
        assertRejected("a partial chunk", () -> Blake3.subtreeChainingValue(input, 0, 1025, 0));
        assertRejected("three chunks", () -> Blake3.subtreeChainingValue(input, 0, 3 * 1024, 0));
        assertRejected("a misaligned start", () -> Blake3.subtreeChainingValue(input, 0, 2 * 1024, 1));
        assertRejected("a short chaining value", () -> Blake3.mergeRoot(new byte[31], new byte[32]));
    }

    private static void assertRejected(String what, Runnable call) {
        try {
            call.run();
            Assert.fail("should have rejected " + what);
        } catch (IllegalArgumentException expected) {
        }
    }

    /**
     * Hash a whole input the way an upload would: each full chunk as a subtree, and whatever is
     * left over at the end - which is not a subtree - hashed by splitting it the same way the
     * tree does, largest aligned subtree first.
     */
    private static byte[] hashInChunks(byte[] input, int chunkSize) {
        List<byte[]> cvs = new ArrayList<>();
        int offset = 0;
        while (input.length - offset >= chunkSize) {
            cvs.add(Blake3.subtreeChainingValue(input, offset, chunkSize, offset / 1024));
            offset += chunkSize;
        }
        if (offset < input.length)
            cvs.add(Blake3.tailChainingValue(input, offset, input.length - offset, offset / 1024));
        if (cvs.size() == 1)
            return Blake3.hash(input); // a single chunk is not merged, so there is no tree to fold
        return Blake3.mergeAsRoot(cvs);
    }

    /**
     * The Hasher api, which is how the rest of the code reaches this: hashing a file section by
     * section through a stream must give what hashing the bytes in one go gives.
     */
    @Test
    public void hasherApi() {
        Hasher hasher = Main.initCrypto().hasher;
        int chunkSize = 4096;
        for (int length : new int[]{1, 1023, 1024, chunkSize, chunkSize + 1, 3 * chunkSize, 3 * chunkSize + 77}) {
            byte[] input = vectorInput(length);
            Assert.assertEquals("whole input of " + length,
                    ArrayOps.bytesToHex(Blake3.hash(input)),
                    ArrayOps.bytesToHex(hasher.blake3(input).join()));
            Assert.assertEquals("section api over the whole of " + length,
                    ArrayOps.bytesToHex(Blake3.hash(input)),
                    ArrayOps.bytesToHex(hasher.blake3Section(AsyncReader.build(input), 0, length).join()));

            // and section by section, merged, as an upload would
            List<byte[]> cvs = new ArrayList<>();
            for (long start = 0; start < length; start += chunkSize) {
                long end = Math.min(start + chunkSize, length);
                cvs.add(hasher.blake3SectionChainingValue(AsyncReader.build(input), start, end, start / 1024).join());
            }
            String viaSections = cvs.size() == 1 ? ArrayOps.bytesToHex(Blake3.hash(input))
                    : ArrayOps.bytesToHex(Blake3.mergeAsRoot(cvs));
            Assert.assertEquals("sections of " + length + " merged", ArrayOps.bytesToHex(Blake3.hash(input)), viaSections);
        }
    }

}
