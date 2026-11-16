package peergos.server.tests.util;

import org.junit.Assert;
import org.junit.Test;
import peergos.server.Main;
import peergos.server.sync.FileState;
import peergos.shared.Crypto;
import peergos.shared.user.fs.AsyncReader;
import peergos.shared.user.fs.Chunk;
import peergos.shared.user.fs.HashBranch;
import peergos.shared.user.fs.HashTree;
import peergos.shared.util.Pair;

import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class HashTreeTests {
    private static Crypto crypto = Main.initCrypto();

    @Test
    public void treeFromStoredBranches() {
        Random r = new Random(1);
        for (int chunkSize : List.of(Chunk.LEGACY_SIZE, Chunk.DEFAULT_SIZE)) {
            for (int nChunks : List.of(1024, 1025, 2500, 1024 * 1024 + 5)) {
                List<byte[]> chunkHashes = IntStream.range(0, nChunks).mapToObj(i -> {
                    byte[] h = new byte[32];
                    r.nextBytes(h);
                    return h;
                }).collect(Collectors.toList());
                HashTree tree = HashTree.build(chunkHashes, chunkSize, crypto.hasher).join();
                long size = (long) nChunks * chunkSize - 7;
                List<HashBranch> branches = IntStream.range(0, (nChunks + 1023) / 1024)
                        .mapToObj(b -> tree.branch(b * 1024L))
                        .collect(Collectors.toList());
                HashTree stored = HashTree.fromBranches(branches);
                Assert.assertEquals(tree, stored);
                Assert.assertEquals(Optional.of(tree), HashTree.verified(stored, size, chunkSize, crypto.hasher).join());

                // a file that has grown since
                Assert.assertEquals(Optional.empty(), HashTree.verified(stored, size + chunkSize, chunkSize, crypto.hasher).join());
                if (branches.size() > 1) {
                    // a later branch left over from an earlier version of the file
                    List<byte[]> changed = new java.util.ArrayList<>(chunkHashes);
                    changed.set(nChunks - 1, new byte[32]);
                    HashTree other = HashTree.build(changed, chunkSize, crypto.hasher).join();
                    List<HashBranch> mixed = new java.util.ArrayList<>(branches);
                    mixed.set(mixed.size() - 1, other.branch((mixed.size() - 1) * 1024L));
                    Assert.assertEquals(Optional.empty(),
                            HashTree.verified(HashTree.fromBranches(mixed), size, chunkSize, crypto.hasher).join());
                }
            }
        }
    }

    @Test
    public void chunks1K() {
        List<byte[]> chunkHashes = IntStream.range(0, 1024).mapToObj(i -> new byte[32]).collect(Collectors.toList());
        HashTree tree = HashTree.build(chunkHashes, Chunk.LEGACY_SIZE, crypto.hasher).join();
        Assert.assertTrue(tree.level1.size() == 1);
        Assert.assertTrue(tree.level2.size() == 0);
        Assert.assertTrue(tree.level3.size() == 0);
        Assert.assertTrue(tree.level1.get(0).chunkHashes.length == 1024*32);
    }

    @Test
    public void chunks2K() {
        List<byte[]> chunkHashes = IntStream.range(0, 2*1024).mapToObj(i -> new byte[32]).collect(Collectors.toList());
        HashTree tree = HashTree.build(chunkHashes, Chunk.LEGACY_SIZE, crypto.hasher).join();
        Assert.assertTrue(tree.level1.size() == 2);
        Assert.assertTrue(tree.level2.size() == 1);
        Assert.assertTrue(tree.level3.size() == 0);
        Assert.assertTrue(tree.level1.get(0).chunkHashes.length == 1024*32);
    }

    @Test
    public void chunks1M() {
        List<byte[]> chunkHashes = IntStream.range(0, 1024*1024).mapToObj(i -> new byte[32]).collect(Collectors.toList());
        HashTree tree = HashTree.build(chunkHashes, Chunk.LEGACY_SIZE, crypto.hasher).join();
        Assert.assertTrue(tree.level1.size() == 1024);
        Assert.assertTrue(tree.level2.size() == 1);
        Assert.assertTrue(tree.level3.size() == 0);
        Assert.assertTrue(tree.level1.get(0).chunkHashes.length == 1024*32);
    }

    @Test
    public void chunks2M() { // A 2 TiB file
        List<byte[]> chunkHashes = IntStream.range(0, 2*1024*1024).mapToObj(i -> new byte[32]).collect(Collectors.toList());
        HashTree tree = HashTree.build(chunkHashes, Chunk.LEGACY_SIZE, crypto.hasher).join();
        Assert.assertTrue(tree.level1.size() == 2*1024);
        Assert.assertTrue(tree.level2.size() == 2);
        Assert.assertTrue(tree.level3.size() == 1);
        Assert.assertTrue(tree.level1.get(0).chunkHashes.length == 1024*32);
    }

    @Test
    public void chunks7M() { // A 7 TiB file
        List<byte[]> chunkHashes = IntStream.range(0, 7*1024*1024).mapToObj(i -> new byte[32]).collect(Collectors.toList());
        HashTree tree = HashTree.build(chunkHashes, Chunk.LEGACY_SIZE, crypto.hasher).join();
        Assert.assertTrue(tree.level1.size() == 7*1024);
        Assert.assertTrue(tree.level2.size() == 7);
        Assert.assertTrue(tree.level3.size() == 1);
        Assert.assertTrue(tree.level1.get(0).chunkHashes.length == 1024*32);
    }

    @Test
    public void diff7M() { // A 7 TiB file
        int nChunks = 7 * 1024 * 1024;
        List<byte[]> chunkHashes = IntStream.range(0, nChunks).mapToObj(i -> new byte[32]).collect(Collectors.toList());
        HashTree tree = HashTree.build(chunkHashes, Chunk.LEGACY_SIZE, crypto.hasher).join();

        int diffChunk = 124667;
        chunkHashes.get(diffChunk)[0] = 5;
        HashTree tree2 = HashTree.build(chunkHashes, Chunk.LEGACY_SIZE, crypto.hasher).join();

        long fileSize = ((long)nChunks) * Chunk.LEGACY_SIZE;
        FileState updated = new FileState("", 0, fileSize, tree);
        FileState old = new FileState("", 0, fileSize, tree2);
        List<Pair<Long, Long>> diff = updated.diffRanges(old);
        Assert.assertTrue(diff.size() == 1);
        Assert.assertTrue(diff.get(0).equals(new Pair<>(diffChunk * (long)Chunk.LEGACY_SIZE, (diffChunk + 1)* (long)Chunk.LEGACY_SIZE)));
    }

    @Test
    public void JsHashes() {
        Random rnd = new Random(42);
        for (int s: List.of(0, 1024,
                5*1024*1024, 6*1024*1024,
                10*1024*1024, 11*1024*1024,
                40*1024*1024, 41*1024*1024,
                80*1024*1024, 81*1024*1024,
                150*1024*1024, 161*1024*1024
                )) {
            byte[] data = new byte[s];
            rnd.nextBytes(data);
            AsyncReader reader = AsyncReader.build(data);
            HashTree serial = HashTree.build(reader, 0, data.length, Chunk.LEGACY_SIZE, crypto.hasher).join();
            HashTree parallel = HashTree.buildParallel(i -> AsyncReader.build(data), 0, data.length, Chunk.LEGACY_SIZE, crypto.hasher, 8).join();
            Assert.assertEquals(serial, parallel);
        }
    }
}
