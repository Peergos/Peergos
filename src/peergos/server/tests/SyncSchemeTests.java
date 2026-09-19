package peergos.server.tests;

import org.junit.Assert;
import org.junit.Test;
import peergos.server.Main;
import peergos.server.crypto.hash.ScryptJava;
import peergos.server.sync.FileState;
import peergos.shared.Crypto;
import peergos.shared.cbor.CborObject;
import peergos.shared.crypto.hash.Blake3;
import peergos.shared.crypto.hash.Hasher;
import peergos.shared.user.fs.AsyncReader;
import peergos.shared.user.fs.Chunk;
import peergos.shared.user.fs.HashTree;
import peergos.shared.util.ArrayOps;
import peergos.shared.util.Pair;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;

/**
 * Sync hashes a local file to compare it against a file in Peergos. The two must be hashed the
 * same way, and a local file has no properties of its own to say which way that is.
 *
 * Getting this wrong does not throw. It makes an unchanged file look changed, so the file is
 * re-transferred, and on every sync after that too - a drive that churns forever rather than an
 * error anyone would see.
 */
public class SyncSchemeTests {

    private static final Crypto crypto = Main.initCrypto();
    private static final Hasher hasher = crypto.hasher;

    private static Path write(byte[] data) throws IOException {
        Path p = Files.createTempFile("peergos-sync-scheme", ".bin");
        p.toFile().deleteOnExit();
        Files.write(p, data);
        return p;
    }

    private static byte[] random(int size) {
        byte[] out = new byte[size];
        new Random(size).nextBytes(out);
        return out;
    }

    /**
     * A local file hashed at the BLAKE3 chunk size gets the file's real BLAKE3 hash - the same
     * value the browser and the Java tree produce, and the one b3sum prints.
     */
    @Test
    public void localFileHashesToItsBlake3Root() throws IOException {
        int chunk = Chunk.DEFAULT_SIZE;
        for (int size : new int[]{0, 1, 1024, chunk - 1, chunk, chunk + 1, 2 * chunk, 2 * chunk + 4096}) {
            byte[] data = random(size);
            HashTree tree = ScryptJava.hashFile(write(data), hasher, size, chunk);
            Assert.assertEquals("a local file of " + size + " bytes",
                    ArrayOps.bytesToHex(Blake3.hash(data)), ArrayOps.bytesToHex(tree.rootHash.hash));
        }
    }

    /** And hashing the same local bytes through the streaming reader path agrees with it. */
    @Test
    public void theLocalAndStreamPathsAgree() throws IOException {
        for (int chunk : new int[]{Chunk.LEGACY_SIZE, Chunk.DEFAULT_SIZE}) {
            for (int size : new int[]{0, 1, chunk - 1, chunk, chunk + 1, 3 * chunk / 2}) {
                byte[] data = random(size);
                HashTree fromDisk = ScryptJava.hashFile(write(data), hasher, size, chunk);
                HashTree fromStream = HashTree.build(AsyncReader.build(data), 0, size, chunk, hasher).join();
                Assert.assertEquals("size " + size + " at chunk " + chunk, fromStream, fromDisk);
            }
        }
    }

    /**
     * The reason the scheme has to be carried rather than assumed: the same bytes hashed the two
     * ways are different values, so comparing across them always says "changed".
     */
    @Test
    public void theTwoSchemesDisagreeOnTheSameBytes() throws IOException {
        byte[] data = random(3 * Chunk.DEFAULT_SIZE);
        Path p = write(data);
        HashTree blake3 = ScryptJava.hashFile(p, hasher, data.length, Chunk.DEFAULT_SIZE);
        HashTree sha256 = ScryptJava.hashFile(p, hasher, data.length, Chunk.LEGACY_SIZE);
        Assert.assertNotEquals("the same file, hashed two ways, must not collide",
                ArrayOps.bytesToHex(blake3.rootHash.hash), ArrayOps.bytesToHex(sha256.rootHash.hash));
    }

    /** The sync db has to remember the scheme, or the next sync cannot reproduce the hash. */
    @Test
    public void fileStateCarriesTheChunkSize() throws IOException {
        byte[] data = random(1024);
        HashTree tree = ScryptJava.hashFile(write(data), hasher, data.length, Chunk.DEFAULT_SIZE);

        FileState modern = new FileState("f.bin", 1234, data.length, tree, Chunk.DEFAULT_SIZE);
        FileState roundTripped = FileState.fromCbor(CborObject.fromByteArray(modern.serialize()));
        Assert.assertEquals(Chunk.DEFAULT_SIZE, roundTripped.chunkSize);

        FileState legacy = new FileState("f.bin", 1234, data.length, tree);
        Assert.assertEquals("no chunk size means the legacy one", Chunk.LEGACY_SIZE, legacy.chunkSize);
        Assert.assertFalse("and a legacy row's cbor is unchanged",
                ((CborObject.CborMap) legacy.toCbor()).containsKey("cs"));
        Assert.assertEquals(Chunk.LEGACY_SIZE,
                FileState.fromCbor(CborObject.fromByteArray(legacy.serialize())).chunkSize);
    }

    /**
     * A chunk index becomes a byte range at the file's chunk size, so the ranges sync transfers
     * have to come from the same size the tree was built at.
     */
    @Test
    public void diffRangesUseTheFilesChunkSize() throws IOException {
        int chunk = Chunk.DEFAULT_SIZE;
        byte[] first = random(3 * chunk);
        byte[] second = first.clone();
        second[chunk + 5] ^= 0xff; // a byte in chunk 1

        FileState a = state(first, chunk);
        FileState b = state(second, chunk);
        List<Pair<Long, Long>> diff = b.diffRanges(a);
        Assert.assertEquals(1, diff.size());
        Assert.assertEquals("the range is chunk 1 at the file's own chunk size",
                new Pair<>((long) chunk, 2L * chunk), diff.get(0));

        // and two states at different chunk sizes are not comparable at all
        FileState legacy = state(first, Chunk.LEGACY_SIZE);
        Assert.assertEquals("different schemes mean the whole file",
                List.of(new Pair<>(0L, (long) first.length)), b.diffRanges(legacy));
    }

    private static FileState state(byte[] data, int chunk) throws IOException {
        return new FileState("f.bin", 1234, data.length,
                ScryptJava.hashFile(write(data), hasher, data.length, chunk), chunk);
    }
}
