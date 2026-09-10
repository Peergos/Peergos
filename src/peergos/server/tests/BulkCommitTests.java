package peergos.server.tests;

import org.junit.*;
import peergos.server.*;
import peergos.shared.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.Cid;
import peergos.shared.io.ipfs.Multihash;
import peergos.shared.mutable.*;
import peergos.shared.storage.*;

import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

public class BulkCommitTests {
    private static final Crypto crypto = Main.initCrypto();
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

    /** A commit too big for one request goes as several, of which only the last carries the pointer
     *  updates, and each block in that last call must be reachable from the root within it.
     */
    @Test
    public void splitsAnOversizedCommit() {
        SigningKeyPair keys = SigningKeyPair.random(crypto.random, crypto.signer);
        PublicKeyHash writer = ContentAddressedStorage.hashKey(keys.publicSigningKey);
        SigningPrivateKeyAndPublicHash signer = new SigningPrivateKeyAndPublicHash(writer, keys.secretSigningKey);

        List<byte[]> children = new ArrayList<>();
        List<Cborable> links = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            byte[] child = new CborObject.CborByteArray(random(1000)).serialize();
            children.add(child);
            links.add(new CborObject.CborMerkleLink(crypto.hasher.hash(child, false).join()));
        }
        byte[] root = new CborObject.CborList(links).serialize();
        Cid rootHash = crypto.hasher.hash(root, false).join();
        List<byte[]> all = new ArrayList<>();
        all.add(root);
        all.addAll(children);

        BulkCommit whole = new BulkCommit(Optional.empty(), Arrays.asList(new WriterCommit(writer, all,
                Collections.emptyList(), Collections.emptyList(),
                Optional.of(new SignedPointerUpdate(writer, random(64))), Optional.empty())));
        CommitContext context = new CommitContext(Collections.singletonMap(writer, signer),
                Collections.emptySet(),
                Collections.singletonMap(writer, MaybeMultihash.of(rootHash)),
                Collections.singletonMap(writer, Optional.of(7L)));

        int ceiling = 4500;
        RecordingStorage recorder = new RecordingStorage();
        BulkCommitter committer = new ServerBulkCommitter(recorder, refusingFallback(), crypto.hasher, ceiling, 1000);
        committer.commit(writer, whole, context).join();

        List<BulkCommit> calls = recorder.calls;
        Assert.assertTrue("split into more than one call", calls.size() > 1);
        Assert.assertEquals("only the last call carries pointers", 1,
                calls.stream().filter(BulkCommit::hasPointerUpdate).count());
        Assert.assertTrue("the pointers are in the last call", calls.get(calls.size() - 1).hasPointerUpdate());
        Assert.assertEquals("every block is sent exactly once", all.size(),
                calls.stream().mapToInt(BulkCommit::blockCount).sum());
        for (BulkCommit call : calls)
            Assert.assertTrue("each call fits", call.inlineSize() <= ceiling);

        // the blocks-only calls each carry a signature over their own ordered block hashes
        for (BulkCommit call : calls) {
            Assert.assertTrue("blocks-only calls are held by a transaction",
                    call.hasPointerUpdate() || call.tid.isPresent());
            for (WriterCommit w : call.writers) {
                if (w.pointer.isPresent())
                    continue;
                List<Cid> hashes = w.cborBlocks.stream()
                        .map(b -> crypto.hasher.hash(b, false).join())
                        .collect(Collectors.toList());
                byte[] expected = WriterCommit.blockListPayload(hashes, Optional.of(7L), crypto.hasher).join();
                byte[] signed = keys.publicSigningKey.unsignMessage(w.blockListSignature.get()).join();
                Assert.assertArrayEquals("block list signature", expected, signed);
            }
        }

        // everything in the final call hangs off the root without leaving it
        BulkCommit last = calls.get(calls.size() - 1);
        Map<Cid, byte[]> inLast = new HashMap<>();
        for (byte[] block : last.writers.get(0).cborBlocks)
            inLast.put(crypto.hasher.hash(block, false).join(), block);
        Set<Cid> reachable = new HashSet<>();
        Deque<Cid> toVisit = new ArrayDeque<>(Collections.singletonList(rootHash));
        while (! toVisit.isEmpty()) {
            Cid next = toVisit.poll();
            byte[] block = inLast.get(next);
            if (block == null || ! reachable.add(next))
                continue;
            CborObject.fromByteArray(block).links().forEach(l -> toVisit.add((Cid) l));
        }
        Assert.assertEquals("the last call stands on its own", inLast.keySet(), reachable);
    }

    /** Bytes are not the only bound: a commit of many tiny blocks - what deleting a large folder
     *  produces - must be split too, or one request holds the server for far too long.
     */
    @Test
    public void splitsACommitWithTooManyBlocks() {
        SigningKeyPair keys = SigningKeyPair.random(crypto.random, crypto.signer);
        PublicKeyHash writer = ContentAddressedStorage.hashKey(keys.publicSigningKey);
        SigningPrivateKeyAndPublicHash signer = new SigningPrivateKeyAndPublicHash(writer, keys.secretSigningKey);

        int blocks = 25;
        List<byte[]> children = new ArrayList<>();
        List<Cborable> links = new ArrayList<>();
        for (int i = 0; i < blocks; i++) {
            byte[] child = new CborObject.CborLong(i).serialize();
            children.add(child);
            links.add(new CborObject.CborMerkleLink(crypto.hasher.hash(child, false).join()));
        }
        byte[] root = new CborObject.CborList(links).serialize();
        Cid rootHash = crypto.hasher.hash(root, false).join();
        List<byte[]> all = new ArrayList<>();
        all.add(root);
        all.addAll(children);

        BulkCommit whole = new BulkCommit(Optional.empty(), Arrays.asList(new WriterCommit(writer, all,
                Collections.emptyList(), Collections.emptyList(),
                Optional.of(new SignedPointerUpdate(writer, random(64))), Optional.empty())));
        CommitContext context = new CommitContext(Collections.singletonMap(writer, signer),
                Collections.emptySet(),
                Collections.singletonMap(writer, MaybeMultihash.of(rootHash)),
                Collections.singletonMap(writer, Optional.of(3L)));

        int maxBlocks = 10;
        RecordingStorage recorder = new RecordingStorage();
        // a byte ceiling far larger than the whole commit, so only the block count can force a split
        BulkCommitter committer = new ServerBulkCommitter(recorder, refusingFallback(), crypto.hasher,
                1024 * 1024, maxBlocks);
        committer.commit(writer, whole, context).join();

        List<BulkCommit> calls = recorder.calls;
        Assert.assertTrue("split on count alone", calls.size() > 1);
        for (BulkCommit call : calls)
            Assert.assertTrue("each call is within the block cap: " + call.blockCount(),
                    call.blockCount() <= maxBlocks);
        Assert.assertEquals("every block is sent exactly once", all.size(),
                calls.stream().mapToInt(BulkCommit::blockCount).sum());
        Assert.assertEquals("only the last call carries pointers", 1,
                calls.stream().filter(BulkCommit::hasPointerUpdate).count());
        Assert.assertTrue(calls.get(calls.size() - 1).hasPointerUpdate());
    }

    /** A server that predates the endpoint answers 404, and each client puts that to us in its own
     *  words: the java one names the status code, a browser hands us the status text, or the body of
     *  whatever generic page the server serves for an unknown path.
     */
    @Test
    public void fallsBackOnEveryShapeOfMissingEndpoint() {
        List<String> asSeenByAClient = Arrays.asList(
                "Unexpected Error. Status code: 404 for url http://localhost:8000/api/v0/bulk/commit",
                "Not Found",
                "<!DOCTYPE+html>%0A<html+lang=\"en\">%0A++++<head>%0A++++++++<title>404+Not+Found</title>" +
                        "%0A++++</head>%0A++++<body><h1>404+Page+Not+Found</h1></body>%0A</html>");

        for (String message : asSeenByAClient) {
            PublicKeyHash writer = randomWriter();
            BulkCommit commit = new BulkCommit(Optional.empty(), Arrays.asList(new WriterCommit(writer,
                    Arrays.asList(random(64)), Collections.emptyList(), Collections.emptyList(),
                    Optional.of(new SignedPointerUpdate(writer, random(64))), Optional.empty())));
            CommitContext context = new CommitContext(Collections.emptyMap(), Collections.emptySet(),
                    Collections.emptyMap(), Collections.emptyMap());

            List<BulkCommit> viaFallback = new ArrayList<>();
            BulkCommitter committer = new ServerBulkCommitter(unimplemented(message),
                    (owner, c, ctx) -> {
                        viaFallback.add(c);
                        return Futures.of(Collections.emptyList());
                    }, crypto.hasher);
            committer.commit(writer, commit, context).join();

            Assert.assertEquals("fell back for: " + message, 1, viaFallback.size());
        }
    }

    private static ContentAddressedStorage unimplemented(String message) {
        return new DelegatingStorage(null) {
            @Override
            public ContentAddressedStorage directToOrigin() {
                return this;
            }

            @Override
            public CompletableFuture<List<Cid>> bulkCommit(PublicKeyHash owner, BulkCommit commit) {
                return Futures.errored(new RuntimeException(message));
            }
        };
    }

    private static BulkCommitter refusingFallback() {
        return (owner, commit, context) -> Futures.errored(new IllegalStateException("Should not fall back!"));
    }

    private static class RecordingStorage extends DelegatingStorage {
        final List<BulkCommit> calls = new ArrayList<>();

        RecordingStorage() {
            super(null);
        }

        @Override
        public ContentAddressedStorage directToOrigin() {
            return this;
        }

        @Override
        public CompletableFuture<TransactionId> startTransaction(PublicKeyHash owner) {
            return Futures.of(new TransactionId("test"));
        }

        @Override
        public CompletableFuture<Boolean> closeTransaction(PublicKeyHash owner, TransactionId tid) {
            return Futures.of(true);
        }

        @Override
        public CompletableFuture<List<Cid>> bulkCommit(PublicKeyHash owner, BulkCommit commit) {
            calls.add(commit);
            return Futures.of(Collections.emptyList());
        }
    }

    private static void assertBlocksEqual(List<byte[]> expected, List<byte[]> actual) {
        Assert.assertEquals(expected.size(), actual.size());
        for (int i = 0; i < expected.size(); i++)
            Assert.assertArrayEquals(expected.get(i), actual.get(i));
    }
}
