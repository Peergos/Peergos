package peergos.server.tests;

import org.junit.*;
import peergos.server.*;
import peergos.server.util.*;
import peergos.shared.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.*;
import peergos.shared.mutable.*;
import peergos.shared.storage.*;
import peergos.shared.user.*;
import peergos.shared.user.fs.*;
import peergos.shared.user.fs.cryptree.*;
import peergos.shared.util.*;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

/** One session buffers the writes of every update it is running, whichever writer each is for. An update must
 *  only ever commit its own writes, never those of another one still in progress.
 */
public class ConcurrentWriterAtomicityTests {
    private static Args args = UserTests.useMemoryDbs(UserTests.buildArgs()).with("enable-gc", "false");
    private static UserService service;
    private static final Crypto crypto = Main.initCrypto();
    private final Random random = new Random();

    @BeforeClass
    public static void init() {
        service = Main.PKI_INIT.main(args).localApi;
    }

    private NetworkAccess network() {
        return NetworkAccess.buildBuffered(service.storage, service.bats, service.coreNode, service.account, service.mutable, 0,
                service.social, service.controller, service.usage, service.serverMessages, crypto.hasher,
                Arrays.asList("peergos"), false);
    }

    /** A network that flushes its buffer once it holds this many bytes, so a small update fills it partway through. */
    private NetworkAccess network(int bufferSize) {
        return network(bufferSize, new ArrayList<>());
    }

    private NetworkAccess network(int bufferSize, List<BulkCommit> sent) {
        ContentAddressedStorage recording = new DelegatingStorage(service.storage) {
            @Override
            public ContentAddressedStorage directToOrigin() {
                return this;
            }

            @Override
            public CompletableFuture<List<Cid>> bulkCommit(PublicKeyHash owner, BulkCommit commit) {
                sent.add(commit);
                return super.bulkCommit(owner, commit);
            }
        };
        BufferedStorage blockBuffer = new BufferedStorage(recording, crypto.hasher);
        BufferedPointers pointerBuffer = new BufferedPointers(service.mutable);
        WriteSynchronizer synchronizer = new WriteSynchronizer(pointerBuffer, blockBuffer, crypto.hasher);
        MutableTree tree = new MutableTreeImpl(pointerBuffer, blockBuffer, crypto.hasher, synchronizer);
        return new BufferedNetworkAccess(blockBuffer, pointerBuffer, bufferSize, service.coreNode, service.account,
                service.social, service.mutable, service.bats, Optional.empty(), tree, synchronizer, service.controller,
                service.usage, service.serverMessages, crypto.hasher, Arrays.asList("peergos"), false);
    }

    private static void upload(UserContext user, Path dir, String name, int size, Random r) {
        byte[] data = new byte[size];
        r.nextBytes(data);
        user.getByPath(dir).join().get()
                .uploadOrReplaceFile(name, AsyncReader.build(data), data.length, user.network, crypto, () -> false, x -> {})
                .join();
    }

    @Test
    public void anUpdateFinishingDoesNotCommitAnotherInProgress() throws Exception {
        String username = "cw" + Math.abs(random.nextInt() % 1_000_000);
        UserContext user = PeergosNetworkUtils.ensureSignedUp(username, "password", network(), crypto);
        Path home = PathUtil.get(username);
        Path shared = home.resolve("team");
        user.getUserRoot().join().mkdir("team", user.network, false, user.mirrorBatId(), crypto).join();
        user.shareWriteAccessWith(shared, new HashSet<>()).join();
        upload(user, shared, "file", 3000, random);
        FileWrapper file = user.getByPath(shared.resolve("file")).join().get();
        Assert.assertNotEquals("the file needs its own writer", user.getUserRoot().join().writer(), file.writer());

        CompletableFuture<Boolean> buffered = new CompletableFuture<>();
        CompletableFuture<Boolean> release = new CompletableFuture<>();
        CompletableFuture<Snapshot> inProgress = user.network.synchronizer.applyComplexUpdate(file.owner(), file.signingPair(),
                (v, c) -> file.truncate(v, c, 1000, user.network, crypto)
                        .thenCompose(s -> {
                            buffered.complete(true);
                            return release.thenCompose(x -> Futures.<Snapshot>errored(new RuntimeException("Simulated failure")));
                        }));
        buffered.get(30, TimeUnit.SECONDS);

        // a different writer's update starts and finishes while the first is still running
        upload(user, home, "other.txt", 3000, random);

        release.complete(true);
        try {
            inProgress.join();
            Assert.fail("the update should have failed");
        } catch (CompletionException expected) {}

        UserContext fresh = PeergosNetworkUtils.ensureSignedUp(username, "password", network(), crypto);
        Assert.assertEquals("another update committed the failed one's writes",
                3000, fresh.getByPath(shared.resolve("file")).join().get().getSize());
        Assert.assertTrue(fresh.getByPath(home.resolve("other.txt")).join().isPresent());
    }

    @Test
    public void anUpdateFinishingDoesNotDropAnotherInProgress() throws Exception {
        String username = "cd" + Math.abs(random.nextInt() % 1_000_000);
        UserContext user = PeergosNetworkUtils.ensureSignedUp(username, "password", network(), crypto);
        Path home = PathUtil.get(username);
        Path shared = home.resolve("team");
        user.getUserRoot().join().mkdir("team", user.network, false, user.mirrorBatId(), crypto).join();
        user.shareWriteAccessWith(shared, new HashSet<>()).join();
        upload(user, shared, "file", 3000, random);
        FileWrapper file = user.getByPath(shared.resolve("file")).join().get();

        CompletableFuture<Boolean> buffered = new CompletableFuture<>();
        CompletableFuture<Boolean> release = new CompletableFuture<>();
        CompletableFuture<Snapshot> inProgress = user.network.synchronizer.applyComplexUpdate(file.owner(), file.signingPair(),
                (v, c) -> file.truncate(v, c, 1000, user.network, crypto)
                        .thenCompose(s -> {
                            buffered.complete(true);
                            return release.thenApply(x -> s);
                        }));
        buffered.get(30, TimeUnit.SECONDS);

        upload(user, home, "other.txt", 3000, random);

        release.complete(true);
        inProgress.join();

        UserContext fresh = PeergosNetworkUtils.ensureSignedUp(username, "password", network(), crypto);
        Assert.assertEquals("an update in progress lost its writes to another finishing",
                1000, fresh.getByPath(shared.resolve("file")).join().get().getSize());
        Assert.assertTrue(fresh.getByPath(home.resolve("other.txt")).join().isPresent());
    }

    @Test
    public void anUpdateFailingDoesNotDropAnotherInProgress() throws Exception {
        String username = "cf" + Math.abs(random.nextInt() % 1_000_000);
        UserContext user = PeergosNetworkUtils.ensureSignedUp(username, "password", network(), crypto);
        Path home = PathUtil.get(username);
        Path shared = home.resolve("team");
        user.getUserRoot().join().mkdir("team", user.network, false, user.mirrorBatId(), crypto).join();
        user.shareWriteAccessWith(shared, new HashSet<>()).join();
        upload(user, shared, "file", 3000, random);
        upload(user, home, "mine", 3000, random);
        FileWrapper file = user.getByPath(shared.resolve("file")).join().get();
        FileWrapper mine = user.getByPath(home.resolve("mine")).join().get();

        CompletableFuture<Boolean> buffered = new CompletableFuture<>();
        CompletableFuture<Boolean> release = new CompletableFuture<>();
        CompletableFuture<Snapshot> inProgress = user.network.synchronizer.applyComplexUpdate(file.owner(), file.signingPair(),
                (v, c) -> file.truncate(v, c, 1000, user.network, crypto)
                        .thenCompose(s -> {
                            buffered.complete(true);
                            return release.thenApply(x -> s);
                        }));
        buffered.get(30, TimeUnit.SECONDS);

        try {
            user.network.synchronizer.applyComplexUpdate(mine.owner(), mine.signingPair(),
                    (v, c) -> mine.truncate(v, c, 1000, user.network, crypto)
                            .thenCompose(s -> Futures.<Snapshot>errored(new RuntimeException("Simulated failure")))).join();
            Assert.fail("the update should have failed");
        } catch (CompletionException expected) {}

        release.complete(true);
        inProgress.join();

        UserContext fresh = PeergosNetworkUtils.ensureSignedUp(username, "password", network(), crypto);
        Assert.assertEquals("an update in progress lost its writes to another failing",
                1000, fresh.getByPath(shared.resolve("file")).join().get().getSize());
        Assert.assertEquals(3000, fresh.getByPath(home.resolve("mine")).join().get().getSize());
    }

    /** Granting write access moves a folder to a new writer, and the server only accepts the new writer's blocks
     *  with the parent update that owns it. A big folder fills the buffer while its blocks are being copied, and
     *  that flush has to send the parent's update too.
     */
    @Test
    public void aFlushPartwayThroughAnUpdateCommitsAllItsWriters() {
        String username = "fl" + Math.abs(random.nextInt() % 1_000_000);
        UserContext user = PeergosNetworkUtils.ensureSignedUp(username, "password", network(), crypto);
        Path folder = PathUtil.get(username, "big");
        user.getUserRoot().join().mkdir("big", user.network, false, user.mirrorBatId(), crypto).join();
        int files = 30;
        for (int i = 0; i < files; i++)
            upload(user, folder, "f" + i, 1000, random);

        UserContext small = PeergosNetworkUtils.ensureSignedUp(username, "password", network(16 * 1024), crypto);
        small.shareWriteAccessWith(folder, new HashSet<>()).join();

        UserContext fresh = PeergosNetworkUtils.ensureSignedUp(username, "password", network(), crypto);
        FileWrapper moved = fresh.getByPath(folder).join().get();
        Assert.assertNotEquals("the folder should have its own writer", fresh.getUserRoot().join().writer(), moved.writer());
        Assert.assertEquals(files, moved.getChildren(crypto.hasher, fresh.network).join().size());
    }

    /** A commit too big for one call is split, and only the last call carries the pointer updates. A new writer's
     *  blocks can't go ahead of the parent update that owns it, so the update has to land the writer first.
     */
    @Test
    public void aWriterCreatedByAnUpdateTooBigForOneCallLands() {
        String username = "sp" + Math.abs(random.nextInt() % 1_000_000);
        UserContext user = PeergosNetworkUtils.ensureSignedUp(username, "password", network(), crypto);
        // enough files that moving them to a new writer takes more than one call's worth of blocks
        int subfolders = 24, perFolder = 500;
        Map<String, byte[]> contents = new HashMap<>();
        List<FileWrapper.FolderUploadProperties> folders = new ArrayList<>();
        for (int d = 0; d < subfolders; d++) {
            List<FileWrapper.FileUploadProperties> files = new ArrayList<>();
            for (int i = 0; i < perFolder; i++) {
                byte[] data = new byte[100];
                random.nextBytes(data);
                contents.put("d" + d + "/f" + i, data);
                files.add(new FileWrapper.FileUploadProperties("f" + i, () -> AsyncReader.build(data), 0, data.length,
                        Optional.empty(), Optional.empty(), false, false, x -> {}));
            }
            folders.add(new FileWrapper.FolderUploadProperties(Arrays.asList("big", "d" + d), files));
        }
        user.getUserRoot().join().uploadSubtree(folders.stream(), user.mirrorBatId(), user.network, crypto,
                user.getTransactionService(), f -> Futures.of(true), f -> Futures.of(true), () -> true).join();

        // a buffer that never fills, so nothing but the new writer flushes the move before it ends
        UserContext unbounded = PeergosNetworkUtils.ensureSignedUp(username, "password", network(Integer.MAX_VALUE), crypto);
        Path folder = PathUtil.get(username, "big");
        unbounded.shareWriteAccessWith(folder, new HashSet<>()).join();

        UserContext fresh = PeergosNetworkUtils.ensureSignedUp(username, "password", network(), crypto);
        FileWrapper moved = fresh.getByPath(folder).join().get();
        Assert.assertNotEquals("the folder should have its own writer", fresh.getUserRoot().join().writer(), moved.writer());
        for (int d = 0; d < subfolders; d++)
            Assert.assertEquals(perFolder, fresh.getByPath(folder.resolve("d" + d)).join().get()
                    .getChildren(crypto.hasher, fresh.network).join().size());
        for (int d = 0; d < subfolders; d++) {
            String name = "d" + d + "/f" + random.nextInt(perFolder);
            FileWrapper file = fresh.getByPath(folder.resolve(name)).join().get();
            byte[] read = Serialize.readFully(file.getInputStream(fresh.network, crypto, x -> {}).join(), 100).join();
            Assert.assertArrayEquals("contents of " + name, contents.get(name), read);
        }
    }

    /** An update can create a writer after it has buffered more than one call's worth for another of its writers,
     *  as revoking access to a big folder with a write-shared folder inside it does. The new writer's blocks still
     *  have to travel in the call that carries the update owning it.
     */
    @Test
    public void aWriterCreatedLateInABigUpdateLands() {
        String username = "lw" + Math.abs(random.nextInt() % 1_000_000);
        PeergosNetworkUtils.ensureSignedUp(username, "password", network(), crypto);
        UserContext user = PeergosNetworkUtils.ensureSignedUp(username, "password", network(Integer.MAX_VALUE), crypto);
        FileWrapper home = user.getUserRoot().join();
        PublicKeyHash owner = home.owner();
        SigningPrivateKeyAndPublicHash homeSigner = home.signingPair();
        CryptreeNode existing = home.getPointer().fileAccess;
        NetworkAccess net = user.network;
        List<PublicKeyHash> created = new ArrayList<>();
        net.synchronizer.applyComplexUpdate(owner, homeSigner, (v, c) ->
                CryptreeNode.initAndAuthoriseSigner(owner, homeSigner, SigningKeyPair.randomWriter(crypto), net, v, c)
                        .thenCompose(first -> {
                            created.add(first.right.publicKeyHash);
                            // many entries pointing at a block the server already has, so the update grows past one call
                            return IpfsTransaction.call(owner, tid -> Futures.reduceAll(IntStream.range(0, 12_000).boxed(), first.left,
                                            (s, i) -> net.addPreexistingChunk(existing, owner, crypto.random.randomBytes(32),
                                                    first.right, tid, s, c),
                                            (a, b) -> b), net.dhtClient)
                                    .thenCompose(s -> CryptreeNode.initAndAuthoriseSigner(owner, first.right,
                                            SigningKeyPair.randomWriter(crypto), net, s, c))
                                    .thenApply(second -> {
                                        created.add(second.right.publicKeyHash);
                                        return second.left;
                                    });
                        })).join();

        NetworkAccess fresh = network();
        for (PublicKeyHash writer : created)
            Assert.assertTrue("the new writer should have landed",
                    fresh.mutable.getPointerTarget(owner, writer, fresh.dhtClient).join().updated.isPresent());
    }

    /** Creating a writer still commits in one call when the update is well within one, so it can't land half done. */
    @Test
    public void grantingWriteAccessToAModestFolderIsOneCommit() {
        String username = "oc" + Math.abs(random.nextInt() % 1_000_000);
        UserContext user = PeergosNetworkUtils.ensureSignedUp(username, "password", network(), crypto);
        List<FileWrapper.FileUploadProperties> files = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            byte[] data = new byte[100];
            random.nextBytes(data);
            files.add(new FileWrapper.FileUploadProperties("f" + i, () -> AsyncReader.build(data), 0, data.length,
                    Optional.empty(), Optional.empty(), false, false, x -> {}));
        }
        user.getUserRoot().join().uploadSubtree(Stream.of(new FileWrapper.FolderUploadProperties(Arrays.asList("modest"), files)),
                user.mirrorBatId(), user.network, crypto, user.getTransactionService(),
                f -> Futures.of(true), f -> Futures.of(true), () -> true).join();

        List<BulkCommit> sent = Collections.synchronizedList(new ArrayList<>());
        UserContext recorded = PeergosNetworkUtils.ensureSignedUp(username, "password", network(20 * 1024 * 1024, sent), crypto);
        sent.clear();
        recorded.shareWriteAccessWith(PathUtil.get(username, "modest"), new HashSet<>()).join();
        Assert.assertEquals("granting write access should be a single bulk commit", 1, sent.size());
    }
}
