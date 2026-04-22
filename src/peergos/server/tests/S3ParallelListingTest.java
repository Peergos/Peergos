package peergos.server.tests;

import org.junit.*;
import peergos.server.storage.*;
import peergos.shared.io.ipfs.Cid;
import peergos.shared.io.ipfs.Multihash;
import peergos.shared.storage.DirectS3BlockStore;
import peergos.shared.util.Pair;

import java.util.*;
import java.util.stream.*;

public class S3ParallelListingTest {

    private static String makeKey(String folder, String username, Cid cid) {
        return folder + username + "/" + DirectS3BlockStore.hashToKey(cid);
    }

    private static Cid randomCid(Random r) {
        byte[] hash = new byte[32];
        r.nextBytes(hash);
        return new Cid(1, Cid.Codec.DagCbor, Multihash.Type.sha2_256, hash);
    }

    @Test
    public void parallelListingRangesDoNotLeakBeyondPrefix() {
        String folder = "blocks/";
        Random r = new Random(42);

        String ada = "ada"; // sorts before alice
        String alice = "alice";
        String bob = "bob"; // sorts after alice

        List<Cid> adaBlocks = IntStream.range(0, 50).mapToObj(i -> randomCid(r)).collect(Collectors.toList());
        List<Cid> aliceBlocks = IntStream.range(0, 50).mapToObj(i -> randomCid(r)).collect(Collectors.toList());
        List<Cid> bobBlocks   = IntStream.range(0, 50).mapToObj(i -> randomCid(r)).collect(Collectors.toList());

        Set<String> adaKeys = adaBlocks.stream().map(c -> makeKey(folder, ada, c)).collect(Collectors.toSet());
        Set<String> aliceKeys = aliceBlocks.stream().map(c -> makeKey(folder, alice, c)).collect(Collectors.toSet());
        Set<String> bobKeys   = bobBlocks.stream().map(c -> makeKey(folder, bob, c)).collect(Collectors.toSet());

        // All S3 keys sorted, as S3 would return them
        List<String> allKeys = new ArrayList<>();
        allKeys.addAll(adaKeys);
        allKeys.addAll(aliceKeys);
        allKeys.addAll(bobKeys);
        Collections.sort(allKeys);

        // Compute the ranges that applyToAllVersionsParallel seeds for alice's prefix
        String alicePrefix = alice + "/";
        List<Pair<Optional<String>, Optional<String>>> ranges =
                S3BlockStorage.computeInitialRanges(folder, alicePrefix, "");

        // Collect every key that falls within ANY of the seeded ranges
        Set<String> coveredByRanges = new HashSet<>();
        for (String key : allKeys) {
            for (Pair<Optional<String>, Optional<String>> range : ranges) {
                String startMarker = range.left.get(); // exclusive lower bound
                boolean afterStart = key.compareTo(startMarker) > 0;
                boolean beforeEnd  = range.right.isEmpty() || key.compareTo(range.right.get()) < 0;
                if (afterStart && beforeEnd) {
                    coveredByRanges.add(key);
                    break;
                }
            }
        }

        // All of alice's keys must be covered
        for (String key : aliceKeys)
            Assert.assertTrue("Alice's key missing from ranges: " + key, coveredByRanges.contains(key));

        // None of bob's keys should be covered
        List<String> leaked = bobKeys.stream().filter(coveredByRanges::contains).collect(Collectors.toList());
        Assert.assertTrue(
                "Bug: " + leaked.size() + " of bob's keys fall within alice's listing ranges: " + leaked,
                leaked.isEmpty());

        // None of ada's keys should be covered
        List<String> leakedAda = adaKeys.stream().filter(coveredByRanges::contains).collect(Collectors.toList());
        Assert.assertTrue(
                "Bug: " + leaked.size() + " of ada's keys fall within alice's listing ranges: " + leaked,
                leaked.isEmpty());
    }
}
