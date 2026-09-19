package peergos.server.tests;

import org.junit.Assert;
import org.junit.Test;
import peergos.server.Main;
import peergos.shared.Crypto;
import peergos.shared.crypto.hash.Blake3;
import peergos.shared.crypto.hash.Hasher;
import peergos.shared.user.fs.AsyncReader;
import peergos.shared.user.fs.Chunk;
import peergos.shared.user.fs.ChunkHashList;
import peergos.shared.user.fs.HashTree;
import peergos.shared.util.ArrayOps;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * The tree a file's hash is stored in, for files whose root is a real BLAKE3 hash.
 *
 * The requirement being tested is the whole point of the change: b3sum on the file must print
 * what we store. So every case here compares the tree's root against {@link Blake3#hash} of the
 * same bytes, which is checked against the official vectors in {@link Blake3Tests}.
 *
 * Most cases use a small chunk size rather than the real one. The maths that can go wrong is the
 * shape of the fold and the 1024 per blob grouping, and both need more than 1024 chunks to
 * exercise - 4 GiB at the real chunk size, a few MB at this one. A chunk size is a power of two
 * multiple of a BLAKE3 chunk either way, which is the only property the maths depends on.
 */
public class Blake3HashTreeTests {

    private static final Crypto crypto = Main.initCrypto();
    private static final Hasher hasher = crypto.hasher;
    /** 4 BLAKE3 chunks, so a blob of 1024 covers 4096 of them, as 4 MiB blobs cover 4M of them. */
    private static final int SMALL_CHUNK = 4096;

    private static byte[] random(int length) {
        byte[] out = new byte[length];
        new Random(length).nextBytes(out);
        return out;
    }

    private static HashTree treeOf(byte[] data, int chunkSize) {
        return HashTree.build(AsyncReader.build(data), (int) ((long) data.length >>> 32), data.length,
                chunkSize, hasher).join();
    }

    /**
     * Sizes either side of every kind of boundary: empty, part of a chunk, whole chunks, chunk
     * counts that are not powers of two, and past 1024 chunks so level 2 is built at all.
     */
    private static List<Integer> interestingSizes(int chunkSize) {
        List<Integer> sizes = new ArrayList<>(Arrays.asList(0, 1, 2, chunkSize - 1, chunkSize, chunkSize + 1));
        // 3, 5, 6, 7 and 9 chunks are the counts a pairwise fold gets wrong; powers of two agree
        for (int chunks : new int[]{2, 3, 4, 5, 6, 7, 8, 9, 15, 16, 17, 100}) {
            sizes.add(chunks * chunkSize);
            sizes.add(chunks * chunkSize - 1);
            sizes.add(chunks * chunkSize + chunkSize / 2);
        }
        // and past a whole blob of 1024 chunk values, so there is a level 2 to get wrong
        for (int chunks : new int[]{1023, 1024, 1025, 1536, 2048, 2049}) {
            sizes.add(chunks * chunkSize);
            sizes.add(chunks * chunkSize + chunkSize / 3);
        }
        return sizes;
    }

    /** The root of the tree is the BLAKE3 hash of the file. Nothing else matters as much. */
    @Test
    public void rootIsTheFilesBlake3Hash() {
        for (int size : interestingSizes(SMALL_CHUNK)) {
            byte[] data = random(size);
            Assert.assertEquals("a file of " + size + " bytes",
                    ArrayOps.bytesToHex(Blake3.hash(data)),
                    ArrayOps.bytesToHex(treeOf(data, SMALL_CHUNK).rootHash.hash));
        }
    }

    /** And at the size files actually use, including a partial last chunk. */
    @Test
    public void rootIsTheFilesBlake3HashAtTheRealChunkSize() {
        int chunk = Chunk.DEFAULT_SIZE;
        for (int size : new int[]{0, 1, chunk - 1, chunk, chunk + 1, 2 * chunk, 3 * chunk - 1, 3 * chunk + 1024}) {
            byte[] data = random(size);
            Assert.assertEquals("a file of " + size + " bytes",
                    ArrayOps.bytesToHex(Blake3.hash(data)),
                    ArrayOps.bytesToHex(treeOf(data, chunk).rootHash.hash));
        }
    }

    /** Hashing in parallel workers must give the same tree as hashing straight through. */
    @Test
    public void parallelAgreesWithSerial() {
        for (int size : interestingSizes(SMALL_CHUNK)) {
            byte[] data = random(size);
            HashTree serial = treeOf(data, SMALL_CHUNK);
            for (int parallelism : new int[]{1, 2, 3, 8}) {
                HashTree parallel = HashTree.buildParallel(i -> AsyncReader.build(data), 0, size,
                        SMALL_CHUNK, hasher, parallelism).join();
                Assert.assertEquals(size + " bytes in " + parallelism + " workers", serial, parallel);
            }
        }
    }

    /**
     * A blob of level 1 holds one 32 byte value per chunk, and the levels above hold one per blob
     * below. A modification reads and rewrites one blob per level, so this shape is what makes a
     * one chunk write cheap.
     */
    @Test
    public void levelsHoldOneValuePerChildBlob() {
        int chunks = 2049;
        HashTree tree = treeOf(random(chunks * SMALL_CHUNK), SMALL_CHUNK);
        Assert.assertEquals("level 1 blobs", 3, tree.level1.size());
        Assert.assertEquals(1024, tree.level1.get(0).nChunks());
        Assert.assertEquals(1024, tree.level1.get(1).nChunks());
        Assert.assertEquals("only the rightmost blob is partial", 1, tree.level1.get(2).nChunks());
        Assert.assertEquals("level 2 holds one value per level 1 blob", 1, tree.level2.size());
        Assert.assertEquals(3, tree.level2.get(0).nChunks());
        Assert.assertTrue(tree.level3.isEmpty());

        // and a branch picks out the one blob per level covering a given chunk
        Assert.assertEquals(tree.level1.get(1), tree.branch(1500).level1.get());
        Assert.assertEquals(tree.level1.get(2), tree.branch(2048).level1.get());
        Assert.assertEquals(tree.level2.get(0), tree.branch(2048).level2.get());
    }

    /**
     * A level 2 value is the merge of its blob's chaining values, i.e. a genuine node of the
     * file's tree, rather than a hash of the serialised blob as a legacy file's is. That is what
     * lets one chunk be modified without rehashing the file, so check it directly.
     */
    @Test
    public void interiorValuesAreSubtreeMerges() {
        int chunks = 1500;
        HashTree tree = treeOf(random(chunks * SMALL_CHUNK), SMALL_CHUNK);
        List<byte[]> level2 = tree.level2.get(0).hashes();
        Assert.assertEquals(tree.level1.size(), level2.size());
        for (int i = 0; i < tree.level1.size(); i++)
            Assert.assertEquals("level 2 entry " + i + " is its blob's subtree",
                    ArrayOps.bytesToHex(Blake3.mergeSubtree(tree.level1.get(i).hashes())),
                    ArrayOps.bytesToHex(level2.get(i)));
        Assert.assertEquals("and the root is the root merge of those",
                ArrayOps.bytesToHex(Blake3.mergeAsRoot(level2)),
                ArrayOps.bytesToHex(tree.rootHash.hash));
    }

    /**
     * A one chunk file has no merges: a chaining value cannot be finalised into a root
     * afterwards, so the chunk's stored value has to be the root hash itself. It is also the
     * commonest file size, so getting it wrong would be worse than any of the above.
     */
    @Test
    public void aSingleChunkFileStoresItsRoot() {
        for (int size : new int[]{0, 1, SMALL_CHUNK - 1, SMALL_CHUNK}) {
            byte[] data = random(size);
            HashTree tree = treeOf(data, SMALL_CHUNK);
            Assert.assertEquals(1, tree.level1.size());
            Assert.assertEquals(1, tree.level1.get(0).nChunks());
            Assert.assertEquals("the only chunk's value is the file's hash, at " + size,
                    ArrayOps.bytesToHex(Blake3.hash(data)),
                    ArrayOps.bytesToHex(tree.level1.get(0).hashes().get(0)));
            Assert.assertEquals(ArrayOps.bytesToHex(Blake3.hash(data)), ArrayOps.bytesToHex(tree.rootHash.hash));
        }
    }

    /**
     * What a partial overwrite compares against must be what a whole file hash stored. These are
     * computed by different code paths, and a disagreement writes a wrong root into a cryptree
     * node rather than failing.
     */
    @Test
    public void storedChunkValuesAreWhatAnOverwriteRecomputes() {
        for (int chunks : new int[]{1, 2, 3, 7}) {
            for (int tail : new int[]{0, 1, SMALL_CHUNK / 2}) {
                int size = (chunks - 1) * SMALL_CHUNK + (tail == 0 ? SMALL_CHUNK : tail);
                byte[] data = random(size);
                HashTree tree = treeOf(data, SMALL_CHUNK);
                List<byte[]> stored = tree.level1.get(0).hashes();
                Assert.assertEquals(chunks, stored.size());
                for (int i = 0; i < chunks; i++) {
                    int start = i * SMALL_CHUNK;
                    byte[] chunk = Arrays.copyOfRange(data, start, Math.min(size, start + SMALL_CHUNK));
                    Assert.assertEquals("chunk " + i + " of " + size,
                            ArrayOps.bytesToHex(stored.get(i)),
                            ArrayOps.bytesToHex(HashTree.chunkHash(chunk, i, SMALL_CHUNK, chunks == 1, hasher).join()));
                }
            }
        }
    }

    /**
     * Growing a file past a chunk boundary changes the values of chunks that did not change,
     * because a chunk's chaining value depends on where it sits and a lone chunk's value is a
     * root. A caller that reused the old values would write a wrong root, so pin the behaviour.
     */
    @Test
    public void aChunksValueDependsOnWhetherItIsAlone() {
        byte[] first = random(SMALL_CHUNK);
        byte[] alone = HashTree.chunkHash(first, 0, SMALL_CHUNK, true, hasher).join();
        byte[] inATree = HashTree.chunkHash(first, 0, SMALL_CHUNK, false, hasher).join();
        Assert.assertFalse("a lone chunk's root is not its chaining value", Arrays.equals(alone, inATree));

        HashTree oneChunk = treeOf(first, SMALL_CHUNK);
        HashTree twoChunks = treeOf(ArrayOps.concat(first, random(SMALL_CHUNK)), SMALL_CHUNK);
        Assert.assertEquals(ArrayOps.bytesToHex(alone), ArrayOps.bytesToHex(oneChunk.level1.get(0).hashes().get(0)));
        Assert.assertEquals(ArrayOps.bytesToHex(inATree), ArrayOps.bytesToHex(twoChunks.level1.get(0).hashes().get(0)));
    }

    /**
     * A legacy file keeps the sha256 tree it has. Its data is already written, its root is stored
     * in cryptree nodes, and it can never be re-derived, so this path is permanent rather than
     * transitional.
     */
    @Test
    public void legacyFilesKeepTheirSha256Tree() {
        List<byte[]> chunkHashes = new ArrayList<>();
        for (int i = 0; i < 3; i++)
            chunkHashes.add(crypto.hasher.sha256(random(32)).join());
        HashTree legacy = HashTree.build(chunkHashes, Chunk.LEGACY_SIZE, hasher).join();

        Assert.assertEquals(1, legacy.level1.size());
        ChunkHashList blob = legacy.level1.get(0);
        Assert.assertEquals("the root is the sha256 of the serialised top blob",
                ArrayOps.bytesToHex(hasher.sha256(new peergos.shared.cbor.CborObject.CborList(
                        java.util.Collections.singletonList(blob)).serialize()).join()),
                ArrayOps.bytesToHex(legacy.rootHash.hash));

        HashTree blake3 = HashTree.build(chunkHashes, Chunk.DEFAULT_SIZE, hasher).join();
        Assert.assertNotEquals("and the two schemes do not produce the same root",
                ArrayOps.bytesToHex(legacy.rootHash.hash), ArrayOps.bytesToHex(blake3.rootHash.hash));
    }
}
