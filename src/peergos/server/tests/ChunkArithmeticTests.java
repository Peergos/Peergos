package peergos.server.tests;

import org.junit.Assert;
import org.junit.Test;
import peergos.server.Main;
import peergos.shared.Crypto;
import peergos.shared.crypto.hash.Hasher;
import peergos.shared.storage.auth.Bat;
import peergos.shared.user.fs.Chunk;
import peergos.shared.user.fs.FileProperties;
import peergos.shared.util.ArrayOps;
import peergos.shared.cbor.CborObject;
import peergos.shared.cbor.Cborable;
import peergos.shared.util.Pair;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Every place a byte offset becomes a chunk index, tested at the boundaries.
 *
 * Written before the chunk size became a property of the file rather than a constant, so that
 * the arithmetic can be shown not to have moved. A file's chunk labels come from walking
 * offset/chunkSize steps of a hash chain, so an off by one here does not throw: it reads the
 * wrong chunk, or assembles the right chunks at the wrong spacing, and hands back bytes that
 * are quietly wrong.
 */
public class ChunkArithmeticTests {

    private static final Crypto crypto = Main.initCrypto();
    private static final Hasher hasher = crypto.hasher;

    private static byte[] random(int length) {
        byte[] out = new byte[length];
        new Random(42).nextBytes(out);
        return out;
    }

    private static FileProperties propsOfSize(long size) {
        return new FileProperties("f", false, false, "", size, LocalDateTime.MIN, LocalDateTime.MIN,
                false, Optional.empty(), Optional.empty(), Optional.empty());
    }

    /** The number of chunks a file of each size occupies, either side of every boundary. */
    @Test
    public void chunkCountAtBoundaries() {
        int chunk = Chunk.LEGACY_SIZE;
        long[][] cases = {
                {0, 1}, {1, 1}, {chunk - 1, 1}, {chunk, 1}, {chunk + 1, 2},
                {2L * chunk - 1, 2}, {2L * chunk, 2}, {2L * chunk + 1, 3},
                {10L * chunk, 10}, {10L * chunk + 1, 11},
        };
        for (long[] c : cases) {
            long size = c[0], expected = c[1];
            Assert.assertEquals("a file of " + size + " bytes occupies " + expected + " chunks",
                    expected, propsOfSize(size).chunkCount());
        }
    }

    /**
     * Seeking to an offset picks the chunk containing it: the label for offset o must equal the
     * label for the start of o's chunk, and must change exactly at a boundary.
     */
    @Test
    public void mapKeyChangesExactlyAtChunkBoundaries() {
        int chunk = Chunk.LEGACY_SIZE;
        byte[] streamSecret = random(32);
        byte[] firstMapKey = random(32);
        Optional<Bat> firstBat = Optional.of(Bat.random(crypto.random));

        // the label at each of the first few chunk starts
        List<String> chunkStarts = new ArrayList<>();
        for (int i = 0; i < 4; i++)
            chunkStarts.add(labelAt(streamSecret, firstMapKey, firstBat, (long) i * chunk));

        Assert.assertEquals("distinct chunks have distinct labels", 4, chunkStarts.stream().distinct().count());

        for (int i = 0; i < 4; i++) {
            long start = (long) i * chunk;
            for (long offset : new long[]{start, start + 1, start + chunk / 2, start + chunk - 1}) {
                Assert.assertEquals("offset " + offset + " is in chunk " + i,
                        chunkStarts.get(i), labelAt(streamSecret, firstMapKey, firstBat, offset));
            }
            // and one byte past the end of this chunk is the next chunk
            if (i < 3)
                Assert.assertEquals("offset " + (start + chunk) + " is in chunk " + (i + 1),
                        chunkStarts.get(i + 1), labelAt(streamSecret, firstMapKey, firstBat, start + chunk));
        }
    }

    /**
     * The chain of labels must match walking to each chunk's offset one at a time.
     *
     * The chain includes the first chunk, so asking for n subsequent keys returns n+1 entries,
     * entry i being the label of the chunk at offset i*chunkSize.
     */
    @Test
    public void subsequentMapKeysMatchSeeking() {
        int chunk = Chunk.LEGACY_SIZE;
        byte[] streamSecret = random(32);
        byte[] firstMapKey = random(32);
        Optional<Bat> firstBat = Optional.of(Bat.random(crypto.random));

        int nChunks = 5;
        List<Pair<byte[], Optional<Bat>>> chain =
                FileProperties.calculateSubsequentMapKeys(streamSecret, firstMapKey, firstBat, nChunks, hasher).join();
        Assert.assertEquals(nChunks + 1, chain.size());
        for (int i = 0; i < chain.size(); i++) {
            Assert.assertEquals("entry " + i + " of the chain is the label at offset " + ((long) i * chunk),
                    ArrayOps.bytesToHex(chain.get(i).left),
                    labelAt(streamSecret, firstMapKey, firstBat, (long) i * chunk));
        }
    }

    /** Truncating to a boundary leaves that many chunks; one byte more needs another. */
    @Test
    public void truncationAtBoundaries() {
        int chunk = Chunk.LEGACY_SIZE;
        for (int chunks = 1; chunks <= 3; chunks++) {
            long exact = (long) chunks * chunk;
            Assert.assertEquals(chunks, propsOfSize(exact).chunkCount());
            Assert.assertEquals(chunks, propsOfSize(exact - 1).chunkCount());
            Assert.assertEquals(chunks + 1, propsOfSize(exact + 1).chunkCount());
        }
    }

    /** The chunk size survives a cbor round trip, and a legacy file's cbor does not change. */
    @Test
    public void chunkSizeRoundTrips() {
        FileProperties legacy = propsOfSize(1234);
        Assert.assertEquals("a file with no chunk size is a legacy file",
                Chunk.LEGACY_SIZE, legacy.chunkSize);
        Assert.assertFalse("a legacy file writes no chunk size, so its cbor is unchanged",
                ((CborObject.CborMap) legacy.toCbor()).containsKey("cs"));
        Assert.assertEquals(Chunk.LEGACY_SIZE,
                FileProperties.fromCbor(CborObject.fromByteArray(legacy.toCbor().serialize())).chunkSize);

        FileProperties modern = legacy.withChunkSize(Chunk.DEFAULT_SIZE);
        Assert.assertTrue("a file with a chunk size writes it",
                ((CborObject.CborMap) modern.toCbor()).containsKey("cs"));
        Assert.assertEquals(Chunk.DEFAULT_SIZE,
                FileProperties.fromCbor(CborObject.fromByteArray(modern.toCbor().serialize())).chunkSize);
    }

    /**
     * Every with* method has to carry the chunk size through: a rename or a size change that
     * quietly moved a file back to the legacy size would make every chunk label after the first
     * point at the wrong place.
     */
    @Test
    public void chunkSizeSurvivesEveryDerivation() {
        FileProperties props = propsOfSize(4321).withChunkSize(Chunk.DEFAULT_SIZE);
        Assert.assertEquals("withSize", Chunk.DEFAULT_SIZE, props.withSize(99).chunkSize);
        Assert.assertEquals("withHash", Chunk.DEFAULT_SIZE, props.withHash(Optional.empty()).chunkSize);
        Assert.assertEquals("withThumbnail", Chunk.DEFAULT_SIZE, props.withThumbnail(Optional.empty()).chunkSize);
        Assert.assertEquals("withNoThumbnail", Chunk.DEFAULT_SIZE, props.withNoThumbnail().chunkSize);
        Assert.assertEquals("withModified", Chunk.DEFAULT_SIZE, props.withModified(LocalDateTime.MIN).chunkSize);
        Assert.assertEquals("withNewStreamSecret", Chunk.DEFAULT_SIZE, props.withNewStreamSecret(random(32)).chunkSize);
        Assert.assertEquals("asLink", Chunk.DEFAULT_SIZE, props.asLink().chunkSize);
    }

    /** A file claiming a size we do not support is a bad file, not a new code path. */
    @Test
    public void unsupportedChunkSizesAreRejected() {
        FileProperties props = propsOfSize(1).withChunkSize(Chunk.DEFAULT_SIZE);
        CborObject.CborMap m = (CborObject.CborMap) props.toCbor();
        SortedMap<String, Cborable> values = new TreeMap<>();
        m.keySet().forEach(k -> values.put(k, m.get(k)));
        for (long badLog2 : new long[]{25, 0, 31, -1}) {
            values.put("cs", new CborObject.CborLong(badLog2));
            try {
                FileProperties.fromCbor(CborObject.CborMap.build(values));
                Assert.fail("should have rejected a chunk size of 2^" + badLog2);
            } catch (IllegalStateException expected) {
            }
        }
    }

    private static String labelAt(byte[] streamSecret, byte[] firstMapKey, Optional<Bat> firstBat, long offset) {
        return ArrayOps.bytesToHex(
                FileProperties.calculateMapKey(streamSecret, firstMapKey, firstBat, offset, Chunk.LEGACY_SIZE, hasher).join().left);
    }
}
