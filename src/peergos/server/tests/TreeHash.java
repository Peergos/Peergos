package peergos.server.tests;

import org.junit.Assert;
import org.junit.Test;
import peergos.server.Main;
import peergos.server.crypto.hash.ScryptJava;
import peergos.shared.crypto.hash.Hasher;
import peergos.shared.user.fs.Chunk;
import peergos.shared.user.fs.HashTree;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Random;

public class TreeHash {

    private static final Hasher hasher = Main.initCrypto().hasher;

    /**
     * Hashing a file in parallel must give what hashing it straight through gives, for both
     * schemes. It is not the same code twice: BLAKE3's per-chunk value depends on where the
     * chunk sits, so a worker that started at the wrong chunk index would agree with itself
     * and disagree with a serial run.
     */
    @Test
    public void parallelTreeHash() {
        for (int chunk : new int[]{Chunk.LEGACY_SIZE, Chunk.DEFAULT_SIZE}) {
        for (long chunks=0; chunks < 1024; chunks += 200) {
            for (long size : List.of(
                    chunks * chunk + 1024,
                    Math.max(0, chunks * chunk - 1024))) {
                long t0 = System.nanoTime();
                List<byte[]> parallel = ScryptJava.parallelHashChunks(() -> new RandomStream(size), 8, size, chunk, hasher);
                long t1 = System.nanoTime();

                List<byte[]> serial = ScryptJava.hashChunks(new RandomStream(size), size, 0, chunk, size, hasher);
                long t2 = System.nanoTime();
                long sizeMiB = size / 1024 / 1024;
                if (sizeMiB > 0) {
                    System.out.println("parallel took " + (t1 - t0) / 1_000_000_000 + ", serial took " + (t2 - t1) / 1_000_000_000);
                    System.out.println("Speed up " + (t2 - t1) / (t1 - t0) + " for file size " + sizeMiB + " MiB");
                }
                long expectedChunks = Math.max(1, (size + chunk - 1) / chunk);
                Assert.assertEquals(parallel.size(), expectedChunks);
                Assert.assertEquals(parallel.size(), serial.size());
                for (int i = 0; i < parallel.size(); i++)
                    Assert.assertArrayEquals("chunk " + i + " of " + size + " at " + chunk, parallel.get(i), serial.get(i));
                Assert.assertEquals("the same tree either way",
                        HashTree.build(parallel, chunk, hasher).join(), HashTree.build(serial, chunk, hasher).join());
            }
        }
        }
    }

    static class RandomStream extends InputStream {
        final int val = new Random(42).nextInt() & 0xFF;
        private final long size;
        private long read = 0;

        public RandomStream(long size) {
            this.size = size;
        }

        @Override
        public int read() throws IOException {
            if (read >= size)
                return -1;
            read++;
            return val;
        }

        @Override
        public long skip(long n) throws IOException {
            read += n;
            return n;
        }
    }
}
