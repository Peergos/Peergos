package peergos.server.tests.util;

import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;
import peergos.server.Main;
import peergos.server.sync.FileState;
import peergos.shared.Crypto;
import peergos.shared.user.fs.Chunk;
import peergos.shared.user.fs.HashTree;
import peergos.shared.util.Pair;

import java.nio.file.FileStore;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class HashTreeTests {
    private static Crypto crypto = Main.initCrypto();

    @Test
    public void chunks1K() {
        List<byte[]> chunkHashes = IntStream.range(0, 1024).mapToObj(i -> new byte[32]).collect(Collectors.toList());
        HashTree tree = HashTree.build(chunkHashes, crypto.hasher).join();
        Assert.assertTrue(tree.level1.size() == 1);
        Assert.assertTrue(tree.level2.size() == 0);
        Assert.assertTrue(tree.level3.size() == 0);
        Assert.assertTrue(tree.level1.get(0).chunkHashes.length == 1024*32);
    }

    @Test
    public void chunks2K() {
        List<byte[]> chunkHashes = IntStream.range(0, 2*1024).mapToObj(i -> new byte[32]).collect(Collectors.toList());
        HashTree tree = HashTree.build(chunkHashes, crypto.hasher).join();
        Assert.assertTrue(tree.level1.size() == 2);
        Assert.assertTrue(tree.level2.size() == 1);
        Assert.assertTrue(tree.level3.size() == 0);
        Assert.assertTrue(tree.level1.get(0).chunkHashes.length == 1024*32);
    }

    @Test
    public void chunks1M() {
        List<byte[]> chunkHashes = IntStream.range(0, 1024*1024).mapToObj(i -> new byte[32]).collect(Collectors.toList());
        HashTree tree = HashTree.build(chunkHashes, crypto.hasher).join();
        Assert.assertTrue(tree.level1.size() == 1024);
        Assert.assertTrue(tree.level2.size() == 1);
        Assert.assertTrue(tree.level3.size() == 0);
        Assert.assertTrue(tree.level1.get(0).chunkHashes.length == 1024*32);
    }

    @Test
    public void chunks2M() { // A 2 TiB file
        List<byte[]> chunkHashes = IntStream.range(0, 2*1024*1024).mapToObj(i -> new byte[32]).collect(Collectors.toList());
        HashTree tree = HashTree.build(chunkHashes, crypto.hasher).join();
        Assert.assertTrue(tree.level1.size() == 2*1024);
        Assert.assertTrue(tree.level2.size() == 2);
        Assert.assertTrue(tree.level3.size() == 1);
        Assert.assertTrue(tree.level1.get(0).chunkHashes.length == 1024*32);
    }

    @Test
    public void chunks7M() { // A 7 TiB file
        List<byte[]> chunkHashes = IntStream.range(0, 7*1024*1024).mapToObj(i -> new byte[32]).collect(Collectors.toList());
        HashTree tree = HashTree.build(chunkHashes, crypto.hasher).join();
        Assert.assertTrue(tree.level1.size() == 7*1024);
        Assert.assertTrue(tree.level2.size() == 7);
        Assert.assertTrue(tree.level3.size() == 1);
        Assert.assertTrue(tree.level1.get(0).chunkHashes.length == 1024*32);
    }

    @Test
    public void diff7M() { // A 7 TiB file
        int nChunks = 7 * 1024 * 1024;
        List<byte[]> chunkHashes = IntStream.range(0, nChunks).mapToObj(i -> new byte[32]).collect(Collectors.toList());
        HashTree tree = HashTree.build(chunkHashes, crypto.hasher).join();

        int diffChunk = 124667;
        chunkHashes.get(diffChunk)[0] = 5;
        HashTree tree2 = HashTree.build(chunkHashes, crypto.hasher).join();

        long fileSize = ((long)nChunks) * Chunk.MAX_SIZE;
        FileState updated = new FileState("", 0, fileSize, tree);
        FileState old = new FileState("", 0, fileSize, tree2);
        List<Pair<Long, Long>> diff = updated.diffRanges(old);
        Assert.assertTrue(diff.size() == 1);
        Assert.assertTrue(diff.get(0).equals(new Pair<>(diffChunk * (long)Chunk.MAX_SIZE, (diffChunk + 1)* (long)Chunk.MAX_SIZE)));
    }
}
