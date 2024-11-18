package peergos.server.tests.util;

import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;
import peergos.server.Main;
import peergos.shared.Crypto;
import peergos.shared.user.fs.HashTree;

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
}
