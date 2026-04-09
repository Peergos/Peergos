package peergos.server.tests;

import org.junit.*;
import peergos.server.*;
import peergos.shared.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.storage.auth.*;
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
 * These tests bypass all network/storage layers: they pre-derive the real map-key+bat
 * chain for N chunks, then inject a synthetic lookup function that answers
 * "present if index < K" to verify that the binary search returns K in
 * O(log₈ N) CHAMP round-trips rather than O(N).
 */
public class FileChunkBinarySearchTests {

    private static final Crypto crypto = Main.initCrypto();
    private static final Hasher hasher = crypto.hasher;
    private static final Random random = new Random(0xdeadbeef);

    /**
     * Pre-computes all N (mapKey, bat) pairs for the given stream secret, first key and first bat.
     * Uses the cumulative chain so each step is just one hash.
     */
    private static List<Pair<byte[], Optional<Bat>>> computeAllProbes(
            byte[] streamSecret, byte[] firstKey, Optional<Bat> firstBat, int n) {
        List<Pair<byte[], Optional<Bat>>> probes = new ArrayList<>(n);
        if (n == 0) return probes;
        probes.add(new Pair<>(firstKey, firstBat));
        for (int i = 1; i < n; i++) {
            Pair<byte[], Optional<Bat>> prev = probes.get(i - 1);
            probes.add(FileProperties.calculateMapKey(streamSecret, prev.left, prev.right,
                    Chunk.MAX_SIZE, hasher).join());
        }
        return probes;
    }

    /**
     * Builds a lookup function over pre-computed probes.
     * Probes with index < k are "present"; index >= k are "absent".
     * Also counts the number of CHAMP round-trips (each call = one bulk server request).
     */
    private static Function<List<Pair<byte[], Optional<Bat>>>, CompletableFuture<List<Boolean>>> buildLookup(
            List<Pair<byte[], Optional<Bat>>> allProbes, int k, int[] callCount) {
        Set<ByteArrayWrapper> presentSet = new HashSet<>();
        for (int i = 0; i < k; i++)
            presentSet.add(new ByteArrayWrapper(allProbes.get(i).left));
        return ps -> {
            callCount[0]++;
            return Futures.of(ps.stream()
                    .map(p -> presentSet.contains(new ByteArrayWrapper(p.left)))
                    .collect(Collectors.toList()));
        };
    }

    private void runSearch(int n, int k) {
        byte[] streamSecret = new byte[32];
        random.nextBytes(streamSecret);
        byte[] firstKey = new byte[32];
        random.nextBytes(firstKey);
        Optional<Bat> firstBat = Optional.of(new Bat(new byte[32]));
        random.nextBytes(firstBat.get().secret);

        List<Pair<byte[], Optional<Bat>>> allProbes = computeAllProbes(streamSecret, firstKey, firstBat, Math.max(n, 1));

        int[] callCount = {0};
        Function<List<Pair<byte[], Optional<Bat>>>, CompletableFuture<List<Boolean>>> lookup =
                buildLookup(allProbes, k, callCount);

        long result = FileWrapper.binarySearchAbsentChunk(
                streamSecret, 0L, n, firstKey, firstBat, lookup, hasher).join();

        Assert.assertEquals("N=" + n + " k=" + k + ": wrong first absent chunk", (long) k, result);

        // Verify O(log₈ N) round-trips.
        int maxExpectedCalls = (int) Math.ceil(Math.log(Math.max(n, 1)) / Math.log(8)) + 2;
        Assert.assertTrue("N=" + n + " k=" + k + ": too many CHAMP calls: " + callCount[0]
                + " (expected ≤ " + maxExpectedCalls + ")", callCount[0] <= maxExpectedCalls);
    }

    @Test
    public void zeroChunks() {
        byte[] streamSecret = new byte[32];
        byte[] firstKey = new byte[32];
        int[] calls = {0};
        Function<List<Pair<byte[], Optional<Bat>>>, CompletableFuture<List<Boolean>>> lookup =
                buildLookup(Collections.emptyList(), 0, calls);
        long result = FileWrapper.binarySearchAbsentChunk(
                streamSecret, 0L, 0L, firstKey, Optional.empty(), lookup, hasher).join();
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
        int[] ns = {8, 64, 512, 4096, 32768};
        int[] previousCalls = {0};
        for (int n : ns) {
            byte[] streamSecret = new byte[32];
            random.nextBytes(streamSecret);
            byte[] firstKey = new byte[32];
            random.nextBytes(firstKey);
            Optional<Bat> firstBat = Optional.of(new Bat(new byte[32]));
            random.nextBytes(firstBat.get().secret);

            List<Pair<byte[], Optional<Bat>>> allProbes = computeAllProbes(streamSecret, firstKey, firstBat, n);

            int k = n / 2;
            int[] callCount = {0};
            Function<List<Pair<byte[], Optional<Bat>>>, CompletableFuture<List<Boolean>>> lookup =
                    buildLookup(allProbes, k, callCount);
            FileWrapper.binarySearchAbsentChunk(streamSecret, 0L, n, firstKey, firstBat, lookup, hasher).join();

            // Each 8x growth in N adds at most 1 more round-trip.
            if (previousCalls[0] > 0)
                Assert.assertTrue("N=" + n + ": calls=" + callCount[0] + " grew too fast from " + previousCalls[0],
                        callCount[0] <= previousCalls[0] + 2);
            previousCalls[0] = callCount[0];
        }
    }
}
