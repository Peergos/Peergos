package peergos.server.tests;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import peergos.server.Main;
import peergos.server.storage.FileBlockCache;
import peergos.shared.Crypto;
import peergos.shared.io.ipfs.Cid;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.stream.Stream;

public class FileBlockCacheEvictionTests {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private static List<Cid> put(FileBlockCache cache, Crypto crypto, Random rnd, int count, int size, boolean raw) {
        List<Cid> hashes = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            byte[] data = new byte[size];
            rnd.nextBytes(data);
            Cid hash = crypto.hasher.hash(data, raw).join();
            cache.put(hash, data).join();
            hashes.add(hash);
        }
        return hashes;
    }

    /** The Android app signs in offline from this cache, so going over its limit with file contents must not take
     *  the small metadata blocks with them */
    @Test
    public void evictsBigBlocksFirst() throws IOException {
        Crypto crypto = Main.initCrypto();
        Path tmpDir = folder.newFolder().toPath();
        FileBlockCache cache = new FileBlockCache(tmpDir, 100_000_000);
        Random rnd = new Random(42);
        List<Cid> small = put(cache, crypto, rnd, 200, 1_000, false);
        put(cache, crypto, rnd, 12, 100_000, true);

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
        Path tmpDir = folder.newFolder().toPath();
        FileBlockCache cache = new FileBlockCache(tmpDir, 100_000_000);
        Random rnd = new Random(42);
        List<Cid> small = put(cache, crypto, rnd, 200, 1_000, false);
        List<Cid> big = put(cache, crypto, rnd, 12, 100_000, true);
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
        Path tmpDir = folder.newFolder().toPath();
        FileBlockCache cache = new FileBlockCache(tmpDir, 100_000_000);
        List<Cid> blocks = put(cache, crypto, new Random(42), 200, 100, false);
        while (! blocks.isEmpty()) {
            cache.delete(blocks.remove(blocks.size() - 1));
            for (Cid hash : blocks)
                Assert.assertTrue(cache.get(hash).join().isPresent());
        }
        try (Stream<Path> dirs = Files.list(tmpDir)) {
            Assert.assertEquals(0, dirs.filter(Files::isDirectory).count());
        }
    }

    /** Only a full disk may clear the cache, which a cached sign-in offline needs. Other errors name their file, so
     *  under a folder such as .../workspace/... their message holds "space". */
    @Test
    public void otherPutErrorsKeepTheCache() throws IOException {
        Crypto crypto = Main.initCrypto();
        Path tmpDir = folder.newFolder("workspace").toPath();
        FileBlockCache cache = new FileBlockCache(tmpDir, 100_000_000);
        Random rnd = new Random(42);
        byte[] data = new byte[1_000];
        rnd.nextBytes(data);
        Cid failing = crypto.hasher.hash(data, false).join();
        cache.put(failing, data).join();
        // a file where its folder was fails its next put
        Path dir;
        try (Stream<Path> files = Files.walk(tmpDir)) {
            dir = files.filter(p -> p.toString().endsWith(".data")).findFirst().get().getParent();
        }
        cache.delete(failing);
        Files.createFile(dir);
        List<Cid> cached = put(cache, crypto, rnd, 20, 1_000, false);

        try {
            cache.put(failing, data).join();
        } catch (RuntimeException expected) {}
        for (Cid hash : cached)
            Assert.assertTrue("a put error cleared the cache", cache.hasBlock(hash));
    }

    /** An eviction that fails part way must not stop every later one */
    @Test
    public void evictsAgainAfterAFailedEviction() throws IOException {
        Crypto crypto = Main.initCrypto();
        Path tmpDir = folder.newFolder().toPath();
        AtomicBoolean failWalk = new AtomicBoolean(false);
        FileBlockCache cache = new FileBlockCache(tmpDir, 100_000_000) {
            @Override
            public void applyToAll(BiConsumer<Path, BasicFileAttributes> processor) {
                if (failWalk.getAndSet(false))
                    throw new IllegalStateException("walk failed");
                super.applyToAll(processor);
            }
        };
        put(cache, crypto, new Random(42), 12, 100_000, true);
        int limit = 500_000;
        failWalk.set(true);
        try {
            cache.ensureWithinSizeLimit(limit);
            Assert.fail("the walk should have failed");
        } catch (IllegalStateException expected) {}

        cache.ensureWithinSizeLimit(limit);
        Assert.assertTrue(blocksSize(tmpDir) <= limit);
    }

    private static long blocksSize(Path dir) throws IOException {
        try (Stream<Path> files = Files.walk(dir)) {
            return files.filter(p -> p.toString().endsWith(".data")).mapToLong(p -> p.toFile().length()).sum();
        }
    }
}
