package peergos.server.tests;

import org.junit.Assert;
import org.junit.Test;
import peergos.server.Main;
import peergos.server.storage.FileBlockCache;
import peergos.shared.Crypto;
import peergos.shared.io.ipfs.Cid;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

public class FileBlockCacheEvictionTests {

    /** The Android app signs in offline from this cache, so going over its limit with file contents must not take
     *  the small metadata blocks with them */
    @Test
    public void evictsBigBlocksFirst() throws IOException {
        Crypto crypto = Main.initCrypto();
        Path tmpDir = Files.createTempDirectory("peergos-test");
        FileBlockCache cache = new FileBlockCache(tmpDir, 100_000_000);
        Random rnd = new Random(42);
        List<Cid> small = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            byte[] data = new byte[1_000];
            rnd.nextBytes(data);
            Cid hash = crypto.hasher.hash(data, false).join();
            cache.put(hash, data).join();
            small.add(hash);
        }
        for (int i = 0; i < 12; i++) {
            byte[] data = new byte[100_000];
            rnd.nextBytes(data);
            cache.put(crypto.hasher.hash(data, true).join(), data).join();
        }

        int limit = 1_000_000;
        cache.ensureWithinSizeLimit(limit);
        Assert.assertTrue(blocksSize(tmpDir) <= limit);
        for (Cid hash : small)
            Assert.assertTrue(cache.hasBlock(hash));
    }

    /** A count that has run ahead of the files, as a second cache over them leaves it, must not make an eviction
     *  delete what it doesn't need to */
    @Test
    public void evictionGoesByTheFilesNotADriftedCount() throws IOException {
        Crypto crypto = Main.initCrypto();
        Path tmpDir = Files.createTempDirectory("peergos-test");
        FileBlockCache cache = new FileBlockCache(tmpDir, 100_000_000);
        Random rnd = new Random(42);
        List<Cid> small = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            byte[] data = new byte[1_000];
            rnd.nextBytes(data);
            Cid hash = crypto.hasher.hash(data, false).join();
            cache.put(hash, data).join();
            small.add(hash);
        }
        List<Cid> big = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            byte[] data = new byte[100_000];
            rnd.nextBytes(data);
            Cid hash = crypto.hasher.hash(data, true).join();
            cache.put(hash, data).join();
            big.add(hash);
        }
        // another cache over the same folder evicts most of the big ones, which this one still counts
        try (Stream<Path> files = Files.walk(tmpDir)) {
            for (Path p : files.filter(p -> p.toString().endsWith(".data") && p.toFile().length() == 100_000).limit(7).toList())
                Files.delete(p);
        }
        long bigLeft = big.stream().filter(cache::hasBlock).count();

        // the files are within the limit, only the count is over it
        cache.ensureWithinSizeLimit(1_000_000);
        for (Cid hash : small)
            Assert.assertTrue(cache.hasBlock(hash));
        Assert.assertEquals(bigLeft, big.stream().filter(cache::hasBlock).count());
    }

    @Test
    public void deleteRemovesAFolderOnlyOnceItIsEmpty() throws IOException {
        Crypto crypto = Main.initCrypto();
        Path tmpDir = Files.createTempDirectory("peergos-test");
        FileBlockCache cache = new FileBlockCache(tmpDir, 100_000_000);
        Random rnd = new Random(42);
        List<Cid> blocks = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            byte[] data = new byte[100];
            rnd.nextBytes(data);
            Cid hash = crypto.hasher.hash(data, false).join();
            cache.put(hash, data).join();
            blocks.add(hash);
        }
        while (! blocks.isEmpty()) {
            cache.delete(blocks.remove(blocks.size() - 1));
            for (Cid hash : blocks)
                Assert.assertTrue(cache.get(hash).join().isPresent());
        }
        try (Stream<Path> dirs = Files.list(tmpDir)) {
            Assert.assertEquals(0, dirs.filter(Files::isDirectory).count());
        }
    }

    private static long blocksSize(Path dir) throws IOException {
        try (Stream<Path> files = Files.walk(dir)) {
            return files.filter(p -> p.toString().endsWith(".data")).mapToLong(p -> p.toFile().length()).sum();
        }
    }
}
