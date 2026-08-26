package peergos.server.tests;

import org.junit.*;
import peergos.server.*;
import peergos.server.storage.*;
import peergos.server.util.*;
import peergos.shared.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.Cid;
import peergos.shared.storage.*;
import peergos.shared.user.*;
import peergos.shared.user.fs.*;
import peergos.shared.util.*;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

import static peergos.server.tests.PeergosNetworkUtils.*;

/** A single operation can write to more than one owner's space. Every block and pointer must be sent to the server
 *  of the owner that actually owns the writer, otherwise it is rejected by an owner's server that has never heard
 *  of the writer.
 */
public class CrossOwnerWriteTests {

    private static final Crypto crypto = Main.initCrypto();
    private static final Args args = UserTests.buildArgs().with("useIPFS", "false");
    private static UserService service;
    private static WriteRecordingStorage recorder;
    private static NetworkAccess sharerNet, shareeNet;
    private static final Random random = new Random(42);

    @BeforeClass
    public static void init() {
        service = Main.PKI_INIT.main(args).localApi;
        recorder = new WriteRecordingStorage(service.storage);
        sharerNet = NetworkAccess.buildBuffered(service.storage, service.bats, service.coreNode, service.account,
                service.mutable, 0, service.social, service.controller, service.usage, service.serverMessages,
                crypto.hasher, Arrays.asList("peergos"), false);
        shareeNet = NetworkAccess.buildBuffered(recorder, service.bats, service.coreNode, service.account,
                service.mutable, 0, service.social, service.controller, service.usage, service.serverMessages,
                crypto.hasher, Arrays.asList("peergos"), false);
    }

    @Test
    public void largeUploadToSharedDir() {
        UserContext sharer = ensureSignedUp(generateUsername(random), generatePassword(), sharerNet, crypto);
        UserContext sharee = ensureSignedUp(generateUsername(random), generatePassword(), shareeNet, crypto);
        friendBetweenGroups(Arrays.asList(sharer), Arrays.asList(sharee));

        String folderName = "toshare";
        Path folder = PathUtil.get(sharer.username, folderName);
        sharer.getUserRoot().join().mkdir(folderName, sharer.network, false, sharer.mirrorBatId(), crypto).join();
        sharer.shareWriteAccessWith(folder, Collections.singleton(sharee.username)).join();

        // a file bigger than a chunk writes an upload transaction into the uploader's own space
        byte[] data = new byte[6 * 1024 * 1024];
        random.nextBytes(data);
        recorder.reset();
        sharee.getByPath(folder).join().get()
                .uploadFileJS("big.bin", AsyncReader.build(data), 0, data.length, false, sharee.mirrorBatId(),
                        sharee.network, crypto, x -> {}, sharee.getTransactionService(), f -> Futures.of(false)).join();

        Map<PublicKeyHash, PublicKeyHash> ownerOfWriter = new HashMap<>();
        addWriters(sharer, ownerOfWriter);
        addWriters(sharee, ownerOfWriter);

        List<Pair<PublicKeyHash, PublicKeyHash>> writes = recorder.writes();
        Assert.assertFalse(writes.isEmpty());
        Set<PublicKeyHash> owners = new HashSet<>();
        for (Pair<PublicKeyHash, PublicKeyHash> write : writes) {
            PublicKeyHash actual = ownerOfWriter.get(write.right);
            Assert.assertNotNull("Unknown writer " + write.right, actual);
            owners.add(actual);
            Assert.assertEquals("Blocks for writer " + write.right + " sent to the wrong owner",
                    actual, write.left);
        }
        // the upload must have touched both users' spaces, or this proves nothing
        Assert.assertEquals(2, owners.size());
        Assert.assertEquals(data.length, sharee.getByPath(folder.resolve("big.bin")).join().get().getSize());
    }

    private static void addWriters(UserContext context, Map<PublicKeyHash, PublicKeyHash> ownerOfWriter) {
        PublicKeyHash owner = context.signer.publicKeyHash;
        Set<PublicKeyHash> visited = new HashSet<>();
        Deque<PublicKeyHash> toVisit = new ArrayDeque<>();
        toVisit.add(owner);
        while (! toVisit.isEmpty()) {
            PublicKeyHash current = toVisit.poll();
            if (! visited.add(current))
                continue;
            ownerOfWriter.put(current, owner);
            UserContext.getWriterData(context.network, owner, current).join()
                    .props.get()
                    .directOwnedKeys(owner, context.network.dhtClient, crypto.hasher).join()
                    .forEach(toVisit::add);
        }
    }

    private static class WriteRecordingStorage extends DelegatingStorage {
        private final ContentAddressedStorage target;
        private final List<Pair<PublicKeyHash, PublicKeyHash>> writes = new ArrayList<>();

        public WriteRecordingStorage(ContentAddressedStorage target) {
            super(target);
            this.target = target;
        }

        @Override
        public ContentAddressedStorage directToOrigin() {
            return target.directToOrigin();
        }

        public synchronized void reset() {
            writes.clear();
        }

        public synchronized List<Pair<PublicKeyHash, PublicKeyHash>> writes() {
            return new ArrayList<>(writes);
        }

        private synchronized void record(PublicKeyHash owner, PublicKeyHash writer) {
            writes.add(new Pair<>(owner, writer));
        }

        @Override
        public CompletableFuture<List<Cid>> put(PublicKeyHash owner,
                                                PublicKeyHash writer,
                                                List<byte[]> signedHashes,
                                                List<byte[]> blocks,
                                                TransactionId tid) {
            record(owner, writer);
            return target.put(owner, writer, signedHashes, blocks, tid);
        }

        @Override
        public CompletableFuture<List<Cid>> putRaw(PublicKeyHash owner,
                                                   PublicKeyHash writer,
                                                   List<byte[]> signatures,
                                                   List<byte[]> blocks,
                                                   TransactionId tid,
                                                   ProgressConsumer<Long> progressCounter) {
            record(owner, writer);
            return target.putRaw(owner, writer, signatures, blocks, tid, progressCounter);
        }
    }
}
