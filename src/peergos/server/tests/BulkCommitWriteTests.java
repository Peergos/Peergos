package peergos.server.tests;

import org.junit.*;
import peergos.server.*;
import peergos.server.util.*;
import peergos.shared.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.Cid;
import peergos.shared.io.ipfs.Multihash;
import peergos.shared.mutable.*;
import peergos.shared.storage.*;
import peergos.shared.user.*;
import peergos.shared.user.fs.*;
import peergos.shared.util.*;
import peergos.shared.util.Exceptions;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static peergos.server.tests.PeergosNetworkUtils.*;

/** What bulk/commit is for: a small write is one request, and the server refuses a commit that isn't
 *  closed, isn't reachable from the root it signs, or isn't signed for at all.
 */
public class BulkCommitWriteTests {

    private static final Crypto crypto = Main.initCrypto();
    private static final Args args = UserTests.buildArgs().with("useIPFS", "false");
    private static UserService service;
    private static CountingStorage storage;
    private static CountingPointers pointers;
    private static NetworkAccess network;
    private static final Random random = new Random(28);

    @BeforeClass
    public static void init() {
        service = Main.PKI_INIT.main(args).localApi;
        storage = new CountingStorage(service.storage);
        pointers = new CountingPointers(service.mutable);
        network = NetworkAccess.buildBuffered(storage, service.bats, service.coreNode, service.account,
                pointers, 0, service.social, service.controller, service.usage, service.serverMessages,
                crypto.hasher, Arrays.asList("peergos"), false);
    }

    @Test
    public void smallWriteIsOneRequest() {
        UserContext user = ensureSignedUp(generateUsername(random), generatePassword(), network, crypto);
        user.getUserRoot().join().mkdir("warmup", user.network, false, user.mirrorBatId(), crypto).join();

        storage.reset();
        pointers.reset();
        user.getUserRoot().join().mkdir("adir", user.network, false, user.mirrorBatId(), crypto).join();

        Assert.assertEquals("bulk commits", 1, storage.bulkCommits.get());
        Assert.assertEquals("block puts", 0, storage.puts.get());
        Assert.assertEquals("transactions started", 0, storage.transactionsStarted.get());
        Assert.assertEquals("transactions closed", 0, storage.transactionsClosed.get());
        Assert.assertEquals("pointer updates", 0, pointers.sets.get());
        Assert.assertTrue("the directory is readable", user.getByPath(PathUtil.get(user.username, "adir")).join().isPresent());
    }

    /** A commit that carries pointer updates is the last call under its transaction, so it closes it:
     *  an upload pays a round trip to open one and none to close it.
     */
    @Test
    public void aCommitClosesTheTransactionItWasGiven() {
        UserContext user = ensureSignedUp(generateUsername(random), generatePassword(), network, crypto);
        // a file with fragments big enough to be written ahead of the commit, which needs a transaction
        byte[] data = randomBytes(2 * 1024 * 1024);
        storage.reset();
        user.getUserRoot().join().uploadOrReplaceFile("big.bin", AsyncReader.build(data), data.length,
                user.network, crypto, () -> false, x -> {}).join();

        Assert.assertTrue("the upload needed a transaction", storage.transactionsStarted.get() > 0);
        Assert.assertEquals("but never had to close one", 0, storage.transactionsClosed.get());
        Assert.assertTrue("and it committed", storage.bulkCommits.get() > 0);
        Assert.assertEquals(data.length, user.getByPath(PathUtil.get(user.username, "big.bin")).join().get().getSize());
    }

    @Test
    public void rejectsAPointerToABlockNobodyHas() {
        UserContext user = ensureSignedUp(generateUsername(random), generatePassword(), network, crypto);
        PublicKeyHash owner = user.signer.publicKeyHash;
        Cid absent = new Cid(Cid.V1, Cid.Codec.DagCbor, Multihash.Type.sha2_256, randomBytes(32));

        BulkCommit commit = new BulkCommit(Optional.empty(), Arrays.asList(
                writerCommit(user, Collections.emptyList(), MaybeMultihash.of(absent))));
        assertRejected(owner, commit, "block we don't have");
    }

    @Test
    public void rejectsABlockWithAMissingLink() {
        UserContext user = ensureSignedUp(generateUsername(random), generatePassword(), network, crypto);
        PublicKeyHash owner = user.signer.publicKeyHash;
        Cid absent = new Cid(Cid.V1, Cid.Codec.DagCbor, Multihash.Type.sha2_256, randomBytes(32));
        byte[] dangling = new CborObject.CborList(Arrays.asList(new CborObject.CborMerkleLink(absent))).serialize();
        Cid danglingHash = crypto.hasher.hash(dangling, false).join();

        BulkCommit commit = new BulkCommit(Optional.empty(), Arrays.asList(
                writerCommit(user, Arrays.asList(dangling), MaybeMultihash.of(danglingHash))));
        assertRejected(owner, commit, "block we don't have");
    }

    @Test
    public void rejectsABlockTheRootDoesNotReach() {
        UserContext user = ensureSignedUp(generateUsername(random), generatePassword(), network, crypto);
        PublicKeyHash owner = user.signer.publicKeyHash;
        byte[] root = new CborObject.CborLong(1).serialize();
        byte[] orphan = new CborObject.CborLong(2).serialize();
        Cid rootHash = crypto.hasher.hash(root, false).join();

        BulkCommit commit = new BulkCommit(Optional.empty(), Arrays.asList(
                writerCommit(user, Arrays.asList(root, orphan), MaybeMultihash.of(rootHash))));
        assertRejected(owner, commit, "not reachable from the new root");
    }

    @Test
    public void rejectsBlocksWithNoSignatureAtAll() {
        UserContext user = ensureSignedUp(generateUsername(random), generatePassword(), network, crypto);
        PublicKeyHash owner = user.signer.publicKeyHash;
        byte[] block = new CborObject.CborLong(3).serialize();

        BulkCommit commit = new BulkCommit(Optional.of(new TransactionId("1")), Arrays.asList(
                new WriterCommit(user.signer.publicKeyHash, Arrays.asList(block), Collections.emptyList(),
                        Collections.emptyList(), Optional.empty(), Optional.empty())));
        assertRejected(owner, commit, "unauthenticated");
    }

    @Test
    public void rejectsABadBlockListSignature() {
        UserContext user = ensureSignedUp(generateUsername(random), generatePassword(), network, crypto);
        PublicKeyHash owner = user.signer.publicKeyHash;
        byte[] block = new CborObject.CborLong(4).serialize();
        // a signature over the wrong list
        byte[] wrong = user.signer.secret.signMessage(crypto.hasher.sha256(randomBytes(32)).join()).join();

        BulkCommit commit = new BulkCommit(Optional.of(new TransactionId("1")), Arrays.asList(
                new WriterCommit(user.signer.publicKeyHash, Arrays.asList(block), Collections.emptyList(),
                        Collections.emptyList(), Optional.empty(), Optional.of(wrong))));
        assertRejected(owner, commit, "Invalid block list signature");
    }

    @Test
    public void acceptsBlocksOnlyWithABlockListSignature() {
        UserContext user = ensureSignedUp(generateUsername(random), generatePassword(), network, crypto);
        PublicKeyHash owner = user.signer.publicKeyHash;
        byte[] block = new CborObject.CborLong(5).serialize();
        Cid hash = crypto.hasher.hash(block, false).join();
        Optional<Long> next = PointerUpdate.increment(service.mutable
                .getPointerTarget(owner, user.signer.publicKeyHash, service.storage).join().sequence);
        byte[] sig = WriterCommit.blockListPayload(Arrays.asList(hash), next, crypto.hasher)
                .thenCompose(payload -> user.signer.secret.signMessage(payload)).join();

        TransactionId tid = service.storage.startTransaction(owner).join();
        BulkCommit commit = new BulkCommit(Optional.of(tid), Arrays.asList(
                new WriterCommit(user.signer.publicKeyHash, Arrays.asList(block), Collections.emptyList(),
                        Collections.emptyList(), Optional.empty(), Optional.of(sig))));
        List<Cid> written = service.storage.bulkCommit(owner, commit).join();
        Assert.assertEquals(Arrays.asList(hash), written);
        Assert.assertTrue(service.storage.getRaw(owner, hash, Optional.empty()).join().isPresent());
        service.storage.closeTransaction(owner, tid).join();
    }

    /** The shape of a split commit: blocks first, held by a transaction and signed for as a list, then a
     *  final call whose pointer update makes them reachable.
     */
    @Test
    public void aSplitCommitLands() {
        UserContext user = ensureSignedUp(generateUsername(random), generatePassword(), network, crypto);
        PublicKeyHash owner = user.signer.publicKeyHash;
        byte[] child = new CborObject.CborLong(11).serialize();
        Cid childHash = crypto.hasher.hash(child, false).join();

        PointerUpdate before = service.mutable.getPointerTarget(owner, owner, service.storage).join();
        byte[] sig = WriterCommit.blockListPayload(Arrays.asList(childHash),
                        PointerUpdate.increment(before.sequence), crypto.hasher)
                .thenCompose(payload -> user.signer.secret.signMessage(payload)).join();
        TransactionId tid = service.storage.startTransaction(owner).join();
        service.storage.bulkCommit(owner, new BulkCommit(Optional.of(tid), Arrays.asList(
                new WriterCommit(owner, Arrays.asList(child), Collections.emptyList(),
                        Collections.emptyList(), Optional.empty(), Optional.of(sig))))).join();

        // the final call's root names the blocks the earlier call wrote
        WriterData current = WriterData.getWriterData(owner, (Cid) before.updated.get(), Optional.empty(), service.storage)
                .join().props.get();
        byte[] root = current.withChamp(childHash).serialize();
        Cid rootHash = crypto.hasher.hash(root, false).join();
        PointerUpdate update = new PointerUpdate(before.updated, MaybeMultihash.of(rootHash),
                PointerUpdate.increment(before.sequence));
        SignedPointerUpdate signedUpdate = new SignedPointerUpdate(owner,
                user.signer.secret.signMessage(update.serialize()).join());
        service.storage.bulkCommit(owner, new BulkCommit(Optional.empty(), Arrays.asList(
                new WriterCommit(owner, Arrays.asList(root), Collections.emptyList(),
                        Collections.emptyList(), Optional.of(signedUpdate), Optional.empty())))).join();
        service.storage.closeTransaction(owner, tid).join();

        PointerUpdate after = service.mutable.getPointerTarget(owner, owner, service.storage).join();
        Assert.assertEquals(MaybeMultihash.of(rootHash), after.updated);
        Assert.assertTrue(service.storage.getRaw(owner, childHash, Optional.empty()).join().isPresent());
    }

    @Test
    public void rejectsAPreWrittenBlockThatWasNeverUploaded() {
        UserContext user = ensureSignedUp(generateUsername(random), generatePassword(), network, crypto);
        PublicKeyHash owner = user.signer.publicKeyHash;
        Cid neverUploaded = new Cid(Cid.V1, Cid.Codec.Raw, Multihash.Type.sha2_256, randomBytes(32));
        byte[] root = new CborObject.CborList(Arrays.asList(new CborObject.CborMerkleLink(neverUploaded))).serialize();
        Cid rootHash = crypto.hasher.hash(root, false).join();

        WriterCommit w = new WriterCommit(user.signer.publicKeyHash, Arrays.asList(root), Collections.emptyList(),
                Arrays.asList(neverUploaded), pointer(user, MaybeMultihash.of(rootHash)), Optional.empty());
        assertRejected(owner, new BulkCommit(Optional.empty(), Arrays.asList(w)), "block we don't have");
    }

    private static byte[] randomBytes(int len) {
        byte[] res = new byte[len];
        random.nextBytes(res);
        return res;
    }

    private static Optional<SignedPointerUpdate> pointer(UserContext user, MaybeMultihash newRoot) {
        PointerUpdate current = service.mutable
                .getPointerTarget(user.signer.publicKeyHash, user.signer.publicKeyHash, service.storage).join();
        PointerUpdate update = new PointerUpdate(current.updated, newRoot, PointerUpdate.increment(current.sequence));
        return Optional.of(new SignedPointerUpdate(user.signer.publicKeyHash,
                user.signer.secret.signMessage(update.serialize()).join()));
    }

    private static WriterCommit writerCommit(UserContext user, List<byte[]> cborBlocks, MaybeMultihash newRoot) {
        return new WriterCommit(user.signer.publicKeyHash, cborBlocks, Collections.emptyList(),
                Collections.emptyList(), pointer(user, newRoot), Optional.empty());
    }

    private static void assertRejected(PublicKeyHash owner, BulkCommit commit, String expected) {
        try {
            service.storage.bulkCommit(owner, commit).join();
            Assert.fail("Should have rejected the commit: " + expected);
        } catch (Exception e) {
            String msg = Exceptions.getRootCause(e).getMessage();
            Assert.assertTrue("Expected \"" + expected + "\" but was: " + msg,
                    msg != null && msg.contains(expected));
        }
    }

    private static class CountingStorage extends DelegatingStorage {
        private final ContentAddressedStorage target;
        final AtomicInteger bulkCommits = new AtomicInteger();
        final AtomicInteger puts = new AtomicInteger();
        final AtomicInteger transactionsStarted = new AtomicInteger();
        final AtomicInteger transactionsClosed = new AtomicInteger();

        CountingStorage(ContentAddressedStorage target) {
            super(target);
            this.target = target;
        }

        void reset() {
            bulkCommits.set(0);
            puts.set(0);
            transactionsStarted.set(0);
            transactionsClosed.set(0);
        }

        @Override
        public ContentAddressedStorage directToOrigin() {
            return this;
        }

        @Override
        public CompletableFuture<List<Cid>> bulkCommit(PublicKeyHash owner, BulkCommit commit) {
            bulkCommits.incrementAndGet();
            return target.bulkCommit(owner, commit);
        }

        @Override
        public CompletableFuture<TransactionId> startTransaction(PublicKeyHash owner) {
            transactionsStarted.incrementAndGet();
            return target.startTransaction(owner);
        }

        @Override
        public CompletableFuture<Boolean> closeTransaction(PublicKeyHash owner, TransactionId tid) {
            transactionsClosed.incrementAndGet();
            return target.closeTransaction(owner, tid);
        }

        @Override
        public CompletableFuture<List<Cid>> put(PublicKeyHash owner, PublicKeyHash writer, List<byte[]> signedHashes,
                                                List<byte[]> blocks, TransactionId tid) {
            puts.incrementAndGet();
            return target.put(owner, writer, signedHashes, blocks, tid);
        }

        @Override
        public CompletableFuture<List<Cid>> putRaw(PublicKeyHash owner, PublicKeyHash writer, List<byte[]> signatures,
                                                   List<byte[]> blocks, TransactionId tid, ProgressConsumer<Long> progress) {
            puts.incrementAndGet();
            return target.putRaw(owner, writer, signatures, blocks, tid, progress);
        }
    }

    private static class CountingPointers implements MutablePointers {
        private final MutablePointers target;
        final AtomicInteger sets = new AtomicInteger();

        CountingPointers(MutablePointers target) {
            this.target = target;
        }

        void reset() {
            sets.set(0);
        }

        @Override
        public CompletableFuture<Boolean> setPointer(PublicKeyHash owner, PublicKeyHash writer, byte[] signed) {
            sets.incrementAndGet();
            return target.setPointer(owner, writer, signed);
        }

        @Override
        public CompletableFuture<Boolean> setPointers(PublicKeyHash owner, List<SignedPointerUpdate> updates) {
            sets.incrementAndGet();
            return target.setPointers(owner, updates);
        }

        @Override
        public void recordApplied(PublicKeyHash owner, List<SignedPointerUpdate> updates) {
            target.recordApplied(owner, updates);
        }

        @Override
        public CompletableFuture<Optional<byte[]>> getPointer(PublicKeyHash owner, PublicKeyHash writer) {
            return target.getPointer(owner, writer);
        }

        @Override
        public MutablePointers clearCache() {
            return target.clearCache();
        }
    }
}
