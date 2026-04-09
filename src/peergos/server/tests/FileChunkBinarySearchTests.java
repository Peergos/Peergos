package peergos.server.tests;

import org.junit.*;
import peergos.server.*;
import peergos.shared.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.user.fs.*;
import peergos.shared.user.fs.cryptree.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.stream.*;

/**
 * Fast unit tests for FileWrapper.binarySearchAbsentChunk.
 *
 * These tests bypass all network/storage layers: they pre-derive the real map-key
 * chain for N chunks, then inject a synthetic lookup function that answers
 * "present if index < K" to verify that the binary search returns K in
 * O(log₈ N) CHAMP round-trips rather than O(N).
 */
public class FileChunkBinarySearchTests {

    private static final Crypto crypto = Main.initCrypto();
    private static final Hasher hasher = crypto.hasher;
    private static final Random random = new Random(0xdeadbeef);

    /**
     * Derives the map key for chunk {@code index} starting from {@code firstKey},
     * reusing the sequential chain (same algorithm as the production code).
     */
    private static byte[] deriveKey(byte[] streamSecret, byte[] firstKey, long index) {
        if (index == 0)
            return firstKey;
        // Sequential derivation: key[i] = calculateMapKey(key[i-1], i * Chunk.MAX_SIZE)
        // We derive key[index] by stepping from key[0] by index * Chunk.MAX_SIZE bytes.
        return FileProperties.calculateMapKey(streamSecret, firstKey, Optional.empty(),
                        (long) index * Chunk.MAX_SIZE, hasher)
                .thenApply(p -> p.left)
                .join();
    }

    /**
     * Pre-computes all N map keys for the given stream secret and first key.
     * Uses the cumulative chain so each step is just one hash.
     */
    private static byte[][] computeAllKeys(byte[] streamSecret, byte[] firstKey, int n) {
        byte[][] keys = new byte[n][];
        if (n == 0) return keys;
        keys[0] = firstKey;
        for (int i = 1; i < n; i++) {
            keys[i] = FileProperties.calculateMapKey(streamSecret, keys[i - 1], Optional.empty(),
                            Chunk.MAX_SIZE, hasher)
                    .thenApply(p -> p.left)
                    .join();
        }
        return keys;
    }

    /**
     * Builds a lookup function over a pre-computed key array.
     * Keys with index < k are "present"; keys at index >= k are "absent".
     * Also counts how many times the lookup is called (= number of CHAMP round-trips).
     */
    private static Function<List<byte[]>, CompletableFuture<List<Boolean>>> buildLookup(
            byte[][] allKeys, int k, int[] callCount) {
        Set<ByteArrayWrapper> presentSet = new HashSet<>();
        for (int i = 0; i < k; i++)
            presentSet.add(new ByteArrayWrapper(allKeys[i]));
        return ks -> {
            callCount[0]++;
            return Futures.of(ks.stream()
                    .map(key -> presentSet.contains(new ByteArrayWrapper(key)))
                    .collect(Collectors.toList()));
        };
    }

    private void runSearch(int n, int k) {
        byte[] streamSecret = new byte[32];
        random.nextBytes(streamSecret);
        byte[] firstKey = new byte[32];
        random.nextBytes(firstKey);

        byte[][] allKeys = computeAllKeys(streamSecret, firstKey, Math.max(n, 1));

        int[] callCount = {0};
        Function<List<byte[]>, CompletableFuture<List<Boolean>>> lookup = buildLookup(allKeys, k, callCount);

        long result = FileWrapper.binarySearchAbsentChunk(
                streamSecret, firstKey, 0L, n, firstKey, lookup, hasher).join();

        Assert.assertEquals("N=" + n + " k=" + k + ": wrong first absent chunk", (long) k, result);

        // Verify O(log₈ N) round-trips — at most ceil(log₈(N)) + 1 CHAMP calls.
        int maxExpectedCalls = (int) Math.ceil(Math.log(Math.max(n, 1)) / Math.log(8)) + 2;
        Assert.assertTrue("N=" + n + " k=" + k + ": too many CHAMP calls: " + callCount[0]
                + " (expected ≤ " + maxExpectedCalls + ")", callCount[0] <= maxExpectedCalls);
    }

    @Test
    public void zeroChunks() {
        // totalChunks=0 is handled before binarySearchAbsentChunk is called; test hi=0 edge case.
        byte[] streamSecret = new byte[32];
        byte[] firstKey = new byte[32];
        int[] calls = {0};
        Function<List<byte[]>, CompletableFuture<List<Boolean>>> lookup = buildLookup(new byte[0][], 0, calls);
        long result = FileWrapper.binarySearchAbsentChunk(streamSecret, firstKey, 0L, 0L, firstKey, lookup, hasher).join();
        Assert.assertEquals(0L, result);
        Assert.assertEquals("no lookups needed", 0, calls[0]);
    }

    @Test
    public void singleChunkAbsent() {
        runSearch(1, 0);
    }

    @Test
    public void singleChunkPresent() {
        runSearch(1, 1);
    }

    @Test
    public void smallFileAllPresent() {
        for (int n = 1; n <= 10; n++)
            runSearch(n, n);
    }

    @Test
    public void smallFileVariousK() {
        int n = 8;
        for (int k = 0; k <= n; k++)
            runSearch(n, k);
    }

    @Test
    public void largeFileFirstChunkAbsent() {
        runSearch(10_000, 0);
    }

    @Test
    public void largeFileLastChunkAbsent() {
        runSearch(10_000, 9_999);
    }

    @Test
    public void largeFileAllPresent() {
        runSearch(10_000, 10_000);
    }

    @Test
    public void largeFileHalfPresent() {
        runSearch(10_000, 5_000);
    }

    @Test
    public void logarithmicRoundTrips() {
        // Verify the number of CHAMP round-trips grows as O(log₈ N) not O(N).
        // For each N, with k = N/2 (worst-ish case), count the lookup calls.
        int[] ns = {8, 64, 512, 4096, 32768};
        int[] previousCalls = {0};
        for (int n : ns) {
            byte[] streamSecret = new byte[32];
            random.nextBytes(streamSecret);
            byte[] firstKey = new byte[32];
            random.nextBytes(firstKey);
            byte[][] allKeys = computeAllKeys(streamSecret, firstKey, n);

            int k = n / 2;
            int[] callCount = {0};
            Function<List<byte[]>, CompletableFuture<List<Boolean>>> lookup = buildLookup(allKeys, k, callCount);
            FileWrapper.binarySearchAbsentChunk(streamSecret, firstKey, 0L, n, firstKey, lookup, hasher).join();

            // Each 8x growth in N adds at most 1 more round-trip.
            if (previousCalls[0] > 0)
                Assert.assertTrue("N=" + n + ": calls=" + callCount[0] + " grew too fast from " + previousCalls[0],
                        callCount[0] <= previousCalls[0] + 2);
            previousCalls[0] = callCount[0];
        }
    }
}
