package peergos.server.tests.slow;

import org.junit.Assert;
import org.junit.Test;
import peergos.server.Main;
import peergos.server.storage.FileBlockCache;
import peergos.shared.Crypto;
import peergos.shared.io.ipfs.Cid;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Comparator;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

public class FileBlockCacheTests {

    @Test
    public void limitSize() throws IOException {
        Crypto crypto = Main.initCrypto();
        Path tmpDir = Files.createTempDirectory("peergos-test");
        int limit = 10_000_000;
        FileBlockCache cache = new FileBlockCache(tmpDir, limit);
        byte[] data = new byte[1_000];
        Random rnd = new Random(42);
        for (int i=0; i < limit/1_000; i++) {
            rnd.nextBytes(data);
            Cid hash = crypto.hasher.hash(data, false).join();
            cache.put(hash, data).join();
        }
        long fullsize = getSize(tmpDir);
        Assert.assertTrue(fullsize == limit);

        cache.ensureWithinSizeLimit(limit);
        for (int i=0; i < 100; i++) {
            rnd.nextBytes(data);
            Cid hash = crypto.hasher.hash(data, false).join();
            cache.put(hash, data).join();
        }
        cache.ensureWithinSizeLimit(limit);
        long finalsize = getSize(tmpDir);
        Assert.assertTrue(finalsize <= limit);
    }

    @Test
    public void cleanEmptyDirs() throws IOException {
        Crypto crypto = Main.initCrypto();
        Path tmpDir = Files.createTempDirectory("peergos-test");
        try {
            int limit = 10_000_000;
            FileBlockCache cache = new FileBlockCache(tmpDir, limit);
            byte[] data = new byte[1_000];
            Random rnd = new Random(42);
            for (int i = 0; i < limit / 1_000; i++) {
                rnd.nextBytes(data);
                Cid hash = crypto.hasher.hash(data, false).join();
                cache.put(hash, data).join();
            }

            cache.ensureWithinSizeLimit(0);
            long finalsize = getSize(tmpDir);
            Assert.assertTrue(finalsize == 0);
            long dirCount = getDirs(tmpDir);
            Assert.assertTrue(dirCount == 0);
        } finally {
            try (Stream<Path> paths = Files.walk(tmpDir)) {
                paths.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
            }
        }
    }

    private static long getSize(Path dir) throws IOException {
        AtomicLong size = new AtomicLong(0);
        Files.walkFileTree(dir, new FileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path path, BasicFileAttributes basicFileAttributes) throws IOException {
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path path, BasicFileAttributes basicFileAttributes) throws IOException {
                size.addAndGet(basicFileAttributes.size());
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path path, IOException e) throws IOException {
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path path, IOException e) throws IOException {
                return FileVisitResult.CONTINUE;
            }
        });
        return size.get();
    }

    private static long getDirs(Path dir) throws IOException {
        AtomicLong dirs = new AtomicLong(-1);
        Files.walkFileTree(dir, new FileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path path, BasicFileAttributes basicFileAttributes) throws IOException {
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path path, BasicFileAttributes basicFileAttributes) throws IOException {
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path path, IOException e) throws IOException {
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path path, IOException e) throws IOException {
                dirs.incrementAndGet();
                return FileVisitResult.CONTINUE;
            }
        });
        return dirs.get();
    }
}
