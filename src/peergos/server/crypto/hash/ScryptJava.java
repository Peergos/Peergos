package peergos.server.crypto.hash;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Supplier;
import java.util.logging.*;

import java.security.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import peergos.shared.crypto.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.Multihash;
import peergos.server.crypto.hash.lambdaworks.crypto.SCrypt;
import peergos.shared.user.*;
import peergos.shared.user.fs.*;
import peergos.shared.util.*;

import javax.crypto.*;
import javax.crypto.spec.*;

public class ScryptJava implements Hasher {
	private static final Logger LOG = Logger.getGlobal();
    public static void disableLog() {
        LOG.setLevel(Level.OFF);
    }
    private static final int LOG_2_MIN_RAM = 17;

    @Override
    public CompletableFuture<byte[]> hashToKeyBytes(String username, String password, SecretGenerationAlgorithm algorithm) {
        CompletableFuture<byte[]> res = new CompletableFuture<>();
        if (algorithm.getType() == SecretGenerationAlgorithm.Type.Scrypt) {
            byte[] hash = Hash.sha256(password.getBytes());
            byte[] salt = username.getBytes();
            try {
                ScryptGenerator params = (ScryptGenerator) algorithm;
                long t1 = System.currentTimeMillis();
                int parallelism = params.parallelism;
                int nOutputBytes = params.outputBytes;
                int cpuCost = params.cpuCost;
                int memoryCost = 1 << params.memoryCost; // Amount of ram required to run algorithm in bytes
                byte[] scryptHash = SCrypt.scrypt(hash, salt, memoryCost, cpuCost, parallelism, nOutputBytes);
                long t2 = System.currentTimeMillis();
                LOG.info("Scrypt hashing took: " + (t2 - t1) + " mS");
                res.complete(scryptHash);
                return res;
            } catch (GeneralSecurityException gse) {
                res.completeExceptionally(gse);
            }
            return res;
        }
        throw new IllegalStateException("Unknown user generation algorithm: " + algorithm);
    }

    @Override
    public CompletableFuture<ProofOfWork> generateProofOfWork(int difficulty, byte[] data) {
        byte[] combined = new byte[data.length + ProofOfWork.PREFIX_BYTES];
        System.arraycopy(data, 0, combined, ProofOfWork.PREFIX_BYTES, data.length);
        long counter = 0;
        while (true) {
            byte[] hash = Hash.sha256(combined);
            if (ProofOfWork.satisfiesDifficulty(difficulty, hash)) {
                byte[] prefix = Arrays.copyOfRange(combined, 0, ProofOfWork.PREFIX_BYTES);
                return Futures.of(new ProofOfWork(prefix, Multihash.Type.sha2_256));
            }
            counter++;
            combined[0] = (byte) counter;
            combined[1] = (byte) (counter >> 8);
            combined[2] = (byte) (counter >> 16);
            combined[3] = (byte) (counter >> 24);
            combined[4] = (byte) (counter >> 32);
            combined[5] = (byte) (counter >> 40);
            combined[6] = (byte) (counter >> 48);
            combined[7] = (byte) (counter >> 56);
        }
    }

    @Override
    public CompletableFuture<byte[]> sha256(byte[] input) {
        return CompletableFuture.completedFuture(Hash.sha256(input));
    }

    @Override
    public CompletableFuture<byte[]> hmacSha256(byte[] secretKeyBytes, byte[] message) {
        try {
            String algorithm = "HMACSHA256";
            Mac mac = Mac.getInstance(algorithm);
            SecretKey secretKey = new SecretKeySpec(secretKeyBytes, algorithm);
            mac.init(secretKey);
            return Futures.of(mac.doFinal(message));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public byte[] blake2b(byte[] input, int outputBytes) {
        return Blake2b.Digest.newInstance(outputBytes).digest(input);
    }

    @Override
    public CompletableFuture<Multihash> hashFromStream(AsyncReader stream, long length) {
        return Hash.sha256(stream, length)
                .thenApply(h -> new Multihash(Multihash.Type.sha2_256, h));
    }

    /**
     * The per-chunk values of a run of a file, starting at chunk {@code firstChunk}.
     *
     * The legacy path streams 64 KiB at a time into a running sha256, which is why it is kept
     * rather than folded into the BLAKE3 one: a chunk's BLAKE3 chaining value cannot be computed
     * from a running digest, so that path has to hold a whole chunk, and there is no reason to
     * make every existing file pay for that.
     */
    public static List<byte[]> hashChunks(InputStream fin, long size, long firstChunk, int chunkSize,
                                          long fileSize, Hasher hasher) {
        if (Chunk.usesBlake3(chunkSize))
            return blake3Chunks(fin, size, firstChunk, chunkSize, fileSize, hasher);
        return sha256Chunks(fin, size, chunkSize);
    }

    private static List<byte[]> blake3Chunks(InputStream fin, long size, long firstChunk, int chunkSize,
                                             long fileSize, Hasher hasher) {
        List<byte[]> chunkHashes = new ArrayList<>();
        boolean onlyChunk = fileSize <= chunkSize;
        byte[] buf = new byte[chunkSize];
        try {
            for (long done = 0; done < size || (size == 0 && chunkHashes.isEmpty()); ) {
                int want = (int) Math.min(chunkSize, size - done);
                int read = 0;
                while (read < want) {
                    int n = fin.read(buf, read, want - read);
                    if (n < 0)
                        throw new IllegalStateException("File ended after " + (done + read) + " of " + size + " bytes");
                    read += n;
                }
                byte[] chunk = read == buf.length ? buf : Arrays.copyOf(buf, read);
                chunkHashes.add(HashTree.chunkHash(chunk, firstChunk + chunkHashes.size(), chunkSize, onlyChunk, hasher).join());
                done += read;
                if (size == 0)
                    break;
            }
            return chunkHashes;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static List<byte[]> sha256Chunks(InputStream fin, long size, int chunkSize) {
        List<byte[]> chunkHashes = new ArrayList<>();
        int chunkOffset = 0;
        byte[] buf = new byte[64 * 1024];
        try {
            MessageDigest chunkHash = MessageDigest.getInstance("SHA-256");
            for (long i = 0; i < size; ) {
                int read = fin.read(buf);
                chunkOffset += read;
                if (chunkOffset >= chunkSize) {
                    int thisChunk = read - chunkOffset + chunkSize;
                    chunkHash.update(buf, 0, thisChunk);
                    chunkHashes.add(chunkHash.digest());
                    chunkHash = MessageDigest.getInstance("SHA-256");
                    int leftover = read - thisChunk;
                    if (leftover > 0)
                        chunkHash.update(buf, thisChunk, leftover);
                    chunkOffset = leftover;
                } else
                    chunkHash.update(buf, 0, read);
                i += read;
            }
            if (size == 0 || size % chunkSize != 0)
                chunkHashes.add(chunkHash.digest());
            return chunkHashes;
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    public static List<byte[]> parallelHashChunks(Supplier<InputStream> fins, int nThreads, long size,
                                                 int chunkSize, Hasher hasher) {
        int nChunks = (int) ((size + chunkSize - 1)/ chunkSize);
        long chunksPerThread = (nChunks + nThreads - 1) / nThreads;
        if (size < chunkSize)
            try (InputStream fin = fins.get()) {
                return hashChunks(fin, size, 0, chunkSize, size, hasher);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        return IntStream.range(0, nThreads)
                .parallel()
                .mapToObj(i -> {
                    try (InputStream fin = fins.get()) {
                        long start = i * chunksPerThread * chunkSize;
                        long end = Math.min(size, (i + 1) * chunksPerThread * chunkSize);
                        if (start == end || start > size)
                            return Collections.<byte[]>emptyList();
                        long skipped = fin.skip(start);
                        if (skipped != start)
                            throw new IllegalStateException("Skip did not complete!");
                        return hashChunks(fin, end - start, i * chunksPerThread, chunkSize, size, hasher);
                    } catch (IOException e) {
                        throw new IllegalStateException(e);
                    }
                })
                .flatMap(List::stream)
                .collect(Collectors.toList());
    }

    public static HashTree hashFile(Path p, Hasher hasher, int chunkSize) {
        return hashFile(p, hasher, p.toFile().length(), chunkSize);
    }

    /**
     * @param chunkSize the chunk size of the file this one is being compared against, which
     *                  decides the scheme as well as the cut - not a property of the local file,
     *                  which has none.
     */
    public static HashTree hashFile(Path p, Hasher hasher, long size, int chunkSize) {
        List<byte[]> chunkHashes = parallelHashChunks(() -> {
            try {
                return new FileInputStream(p.toFile());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }, Runtime.getRuntime().availableProcessors(), size, chunkSize, hasher);
        return HashTree.build(chunkHashes, chunkSize, hasher).join();
    }
}
