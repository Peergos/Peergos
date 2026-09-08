package peergos.server.tests;

import org.junit.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.Cid;
import peergos.shared.io.ipfs.Multihash;
import peergos.shared.mutable.*;
import peergos.shared.storage.*;

import java.util.*;

public class BulkCommitTests {
    private final Random rnd = new Random(42);

    private byte[] random(int len) {
        byte[] res = new byte[len];
        rnd.nextBytes(res);
        return res;
    }

    private Cid randomCid(boolean isRaw) {
        return new Cid(Cid.V1, isRaw ? Cid.Codec.Raw : Cid.Codec.DagCbor, Multihash.Type.sha2_256, random(32));
    }

    private PublicKeyHash randomWriter() {
        return new PublicKeyHash(randomCid(false));
    }

    @Test
    public void roundTrip() {
        PublicKeyHash w1 = randomWriter();
        PublicKeyHash w2 = randomWriter();
        WriterCommit withPointer = new WriterCommit(w1,
                Arrays.asList(random(37), random(1024)),
                Arrays.asList(random(500)),
                Arrays.asList(randomCid(true), randomCid(true)),
                Optional.of(new SignedPointerUpdate(w1, random(64))),
                Optional.empty());
        WriterCommit blocksOnly = new WriterCommit(w2,
                Collections.emptyList(),
                Arrays.asList(random(200)),
                Collections.emptyList(),
                Optional.empty(),
                Optional.of(random(64)));
        BulkCommit commit = new BulkCommit(Optional.of(new TransactionId("1234")), Arrays.asList(withPointer, blocksOnly));

        BulkCommit decoded = BulkCommit.fromCbor(CborObject.fromByteArray(commit.serialize()));
        assertEquals(commit, decoded);
        Assert.assertArrayEquals(commit.serialize(), decoded.serialize());
    }

    @Test
    public void roundTripWithoutTransaction() {
        PublicKeyHash w = randomWriter();
        BulkCommit commit = new BulkCommit(Optional.empty(), Arrays.asList(new WriterCommit(w,
                Arrays.asList(random(64)),
                Collections.emptyList(),
                Collections.emptyList(),
                Optional.of(new SignedPointerUpdate(w, random(64))),
                Optional.empty())));
        BulkCommit decoded = BulkCommit.fromCbor(CborObject.fromByteArray(commit.serialize()));
        Assert.assertTrue(decoded.tid.isEmpty());
        assertEquals(commit, decoded);
    }

    @Test
    public void pointerMustMatchWriter() {
        PublicKeyHash w1 = randomWriter();
        PublicKeyHash w2 = randomWriter();
        try {
            new WriterCommit(w1, Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                    Optional.of(new SignedPointerUpdate(w2, random(64))), Optional.empty());
            Assert.fail("Should have rejected a pointer update for a different writer");
        } catch (IllegalArgumentException expected) {}
    }

    private static void assertEquals(BulkCommit expected, BulkCommit actual) {
        Assert.assertEquals(expected.tid.map(TransactionId::toString), actual.tid.map(TransactionId::toString));
        Assert.assertEquals(expected.writers.size(), actual.writers.size());
        for (int i = 0; i < expected.writers.size(); i++) {
            WriterCommit e = expected.writers.get(i), a = actual.writers.get(i);
            Assert.assertEquals(e.writer, a.writer);
            assertBlocksEqual(e.cborBlocks, a.cborBlocks);
            assertBlocksEqual(e.rawBlocks, a.rawBlocks);
            Assert.assertEquals(e.preWritten, a.preWritten);
            Assert.assertEquals(e.pointer.map(p -> p.writer), a.pointer.map(p -> p.writer));
            Assert.assertEquals(e.pointer.isPresent(), a.pointer.isPresent());
            if (e.pointer.isPresent())
                Assert.assertArrayEquals(e.pointer.get().signed, a.pointer.get().signed);
            Assert.assertEquals(e.blockListSignature.isPresent(), a.blockListSignature.isPresent());
            if (e.blockListSignature.isPresent())
                Assert.assertArrayEquals(e.blockListSignature.get(), a.blockListSignature.get());
        }
    }

    private static void assertBlocksEqual(List<byte[]> expected, List<byte[]> actual) {
        Assert.assertEquals(expected.size(), actual.size());
        for (int i = 0; i < expected.size(); i++)
            Assert.assertArrayEquals(expected.get(i), actual.get(i));
    }
}
