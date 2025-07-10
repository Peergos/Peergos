package peergos.server.tests;

import org.junit.Assert;
import org.junit.Test;
import peergos.server.crypto.hash.ScryptJava;
import peergos.shared.user.fs.Chunk;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Random;

public class TreeHash {

    @Test
    public void parallelTreeHash() {
        for (long chunks=0; chunks < 1024; chunks += 200) {
            for (long size : List.of(
                    chunks * Chunk.MAX_SIZE + 1024,
                    Math.max(0, chunks * Chunk.MAX_SIZE - 1024))) {
                long t0 = System.nanoTime();
                List<byte[]> parallel = ScryptJava.parallelHashChunks(() -> new RandomStream(size), 8, size);
                long t1 = System.nanoTime();

                List<byte[]> serial = ScryptJava.hashChunks(new RandomStream(size), size);
                long t2 = System.nanoTime();
                long sizeMiB = size / 1024 / 1024;
                if (sizeMiB > 0) {
                    System.out.println("parallel took " + (t1 - t0) / 1_000_000_000 + ", serial took " + (t2 - t1) / 1_000_000_000);
                    System.out.println("Speed up " + (t2 - t1) / (t1 - t0) + " for file size " + sizeMiB + " MiB");
                }
                long expectedChunks = Math.max(1, (size + Chunk.MAX_SIZE - 1) / Chunk.MAX_SIZE);
                Assert.assertEquals(parallel.size(), expectedChunks);
                Assert.assertEquals(parallel.size(), serial.size());
                for (int i = 0; i < parallel.size(); i++)
                    Assert.assertArrayEquals(parallel.get(i), serial.get(i));
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
