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

    private static long blocksSize(Path dir) throws IOException {
        try (Stream<Path> files = Files.walk(dir)) {
            return files.filter(p -> p.toString().endsWith(".data")).mapToLong(p -> p.toFile().length()).sum();
        }
    }
}
