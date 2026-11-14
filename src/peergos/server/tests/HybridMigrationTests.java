package peergos.server.tests;

import org.junit.*;
import peergos.server.*;
import peergos.server.storage.*;
import peergos.server.util.*;
import peergos.shared.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.display.*;
import peergos.shared.io.ipfs.*;
import peergos.shared.mutable.*;
import peergos.shared.social.*;
import peergos.shared.storage.*;
import peergos.shared.user.*;
import peergos.shared.user.fs.*;
import peergos.shared.util.*;

import java.nio.file.*;
import java.util.*;

import static peergos.server.tests.PeergosNetworkUtils.*;

/** An account made with Ed25519 writers moving each writing space to a hybrid writer when its owner logs in */
public class HybridMigrationTests {

    private static final Crypto crypto = Main.initCrypto();
    private static final Args args = UserTests.buildArgs().with("useIPFS", "false");
    private static UserService service;
    private static NetworkAccess network;
    private static final Random random = new Random(17);

    private static boolean hybridBefore;

    @BeforeClass
    public static void init() {
        hybridBefore = SigningKeyPair.HYBRID_WRITERS;
        service = Main.PKI_INIT.main(args).localApi;
        network = NetworkAccess.buildBuffered(service.storage, service.bats, service.coreNode, service.account,
                service.mutable, 0, service.social, service.controller, service.usage, service.serverMessages,
                crypto.hasher, Arrays.asList("peergos"), false);
    }

    @After
    public void reset() {
        SigningKeyPair.HYBRID_WRITERS = hybridBefore;
    }

    private static NetworkAccess device() {
        return NetworkAccess.buildBuffered(service.storage, service.bats, service.coreNode, service.account,
                service.mutable, 0, service.social, service.controller, service.usage, service.serverMessages,
                crypto.hasher, Arrays.asList("peergos"), false);
    }

    private static UserContext login(String username, String password) {
        return ensureSignedUp(username, password, network.clear(), crypto);
    }

    private static byte[] read(UserContext context, Path path) {
        FileWrapper file = context.getByPath(path).join()
                .orElseThrow(() -> new AssertionError(context.username + " can't see " + path));
        AsyncReader in = file.getInputStream(context.network, crypto, x -> {}).join();
        return Serialize.readFully(in, file.getFileProperties().size).join();
    }

    private static byte[] readFile(UserContext context, FileWrapper file) {
        AsyncReader in = file.getInputStream(context.network, crypto, x -> {}).join();
        return Serialize.readFully(in, file.getFileProperties().size).join();
    }

    private static byte[] upload(UserContext context, Path dir, String name, int size) {
        byte[] data = new byte[size];
        random.nextBytes(data);
        context.getByPath(dir).join().get()
                .uploadOrReplaceFile(name, AsyncReader.build(data), data.length, context.network, crypto, () -> false, x -> {}).join();
        return data;
    }

    private static void mkdir(UserContext context, Path parent, String name) {
        context.getByPath(parent).join().get().mkdir(name, context.network, false, context.mirrorBatId(), crypto).join();
    }

    private static PublicKeyHash writer(UserContext context, Path path) {
        return context.getByPath(path).join().get().writer();
    }

    private static void assertHybrid(UserContext context, PublicKeyHash writer) {
        Assert.assertFalse(writer + " is still an Ed25519 writer", writer.isIdentity());
        PublicSigningKey key = service.storage.getSigningKey(context.signer.publicKeyHash, writer).join().get();
        Assert.assertEquals(PublicSigningKey.Type.HybridEd25519MLDSA, key.type());
    }

    private static void assertAllHybrid(UserContext context, FileWrapper f, Path path) {
        Assert.assertFalse(path + " is still under an Ed25519 writer", f.writer().isIdentity());
        if (f.isDirectory())
            for (FileWrapper child : f.getChildren(crypto.hasher, context.network).join())
                assertAllHybrid(context, child, path.resolve(child.getName()));
    }

    /** Every writer holding a file or directory in this subtree */
    private static Set<PublicKeyHash> writersUnder(UserContext context, FileWrapper dir) {
        Set<PublicKeyHash> res = new HashSet<>();
        res.add(dir.writer());
        if (dir.isDirectory())
            for (FileWrapper child : dir.getChildren(crypto.hasher, context.network).join())
                res.addAll(writersUnder(context, child));
        return res;
    }

    /** An old writer keeps only a link, at its space's root, to where the space now is */
    private static void assertRetired(UserContext context, WritableAbsoluteCapability oldRoot, PublicKeyHash newWriter) {
        FileWrapper viaOld = context.network.getFile(oldRoot, context.username).join()
                .orElseThrow(() -> new AssertionError("An old cap to a moved space doesn't resolve"));
        Assert.assertTrue(viaOld.isLink());
        Assert.assertEquals(newWriter, viaOld.writer());
        CommittedWriterData old = WriterData.getWriterData(context.signer.publicKeyHash, oldRoot.writer, service.mutable, service.storage).join();
        Set<ByteArrayWrapper> keys = new HashSet<>();
        peergos.shared.hamt.ChampWrapper.create(context.signer.publicKeyHash, (Cid) old.props.get().tree.get(), Optional.empty(),
                        b -> Futures.of(b.data), service.storage, crypto.hasher, c -> (CborObject.CborMerkleLink) c).join()
                .applyToAllMappings(context.signer.publicKeyHash, p -> {
                    keys.add(p.left);
                    return Futures.of(true);
                }).join();
        // a link to the space's root, and for the home one to each sharing folder
        boolean isHome = oldRoot.getMapKey().length > 0 && context.getUserRoot().join().getPointer().capability.getMapKey().length > 0 &&
                Arrays.equals(oldRoot.getMapKey(), context.getUserRoot().join().getPointer().capability.getMapKey());
        int links = 1 + (isHome ? context.getSharingFolder().join().getChildren(crypto.hasher, context.network).join().size() : 0);
        Assert.assertTrue("The old writer holds more than links: " + keys.size(), keys.size() <= links);
    }

    /** Everything an old account has: files big and small, nested writing spaces two deep with a quota, a friend
     *  with a read share and a writable folder, another write sharee, read only and writable secret links, and a
     *  published file
     */
    private static class OldAccount {
        final String password = generatePassword(), friendPassword = generatePassword(), writerPassword = generatePassword();
        final UserContext owner, friend, writer;
        final Path home, docs, team, inner, pub, collab, linked;
        final Map<Path, byte[]> files = new HashMap<>();
        final LinkProperties link, writableLink;
        // made before the move: to a file in a nested writing space, and to a file that is a writing space itself
        final FileRef toNestedFile, toWritableFile;
        final PublicKeyHash oldRoot, oldTeam, oldInner;
        final WritableAbsoluteCapability oldRootCap, oldTeamCap;
        // a session from before the move, holding the old caps
        final FileWrapper staleHome, staleTeam;

        OldAccount() {
            SigningKeyPair.HYBRID_WRITERS = false;
            owner = login(generateUsername(random), password);
            friend = login(generateUsername(random), friendPassword);
            writer = login(generateUsername(random), writerPassword);
            friendBetweenGroups(Arrays.asList(owner), Arrays.asList(friend, writer));
            home = PathUtil.get(owner.username);
            docs = home.resolve("docs");
            team = home.resolve("team");
            inner = team.resolve("inner");
            pub = home.resolve("pub");
            collab = home.resolve("collab");
            linked = home.resolve("linked");
            files.put(home.resolve("small.bin"), upload(owner, home, "small.bin", 2000));
            files.put(home.resolve("big.bin"), upload(owner, home, "big.bin", 6 * 1024 * 1024));
            mkdir(owner, home, "docs");
            mkdir(owner, docs, "deep");
            files.put(docs.resolve("doc.bin"), upload(owner, docs, "doc.bin", 1000));
            files.put(docs.resolve("deep").resolve("deep.bin"), upload(owner, docs.resolve("deep"), "deep.bin", 3000));
            owner.shareReadAccessWith(docs.resolve("doc.bin"), Collections.singleton(friend.username)).join();

            mkdir(owner, home, "team");
            mkdir(owner, team, "inner");
            owner.shareWriteAccessWith(team, Collections.singleton(writer.username)).join();
            owner.shareWriteAccessWith(inner, Collections.singleton(writer.username)).join();
            owner.setWriteShareQuota(team, Optional.of(10L * 1024 * 1024)).join();
            files.put(team.resolve("t.bin"), upload(owner, team, "t.bin", 1500));
            files.put(inner.resolve("i.bin"), upload(owner, inner, "i.bin", 1500));
            UserContext writerNow = login(writer.username, writerPassword);
            files.put(team.resolve("from-writer.bin"), upload(writerNow, team, "from-writer.bin", 1200));

            mkdir(owner, home, "collab");
            owner.shareWriteAccessWith(collab, Collections.singleton(friend.username)).join();
            files.put(collab.resolve("c.bin"), upload(owner, collab, "c.bin", 1100));
            UserContext friendNow = login(friend.username, friendPassword);
            files.put(collab.resolve("from-friend.bin"), upload(friendNow, collab, "from-friend.bin", 1300));
            Assert.assertArrayEquals(files.get(docs.resolve("doc.bin")), read(friendNow, docs.resolve("doc.bin")));

            files.put(home.resolve("wfile.bin"), upload(owner, home, "wfile.bin", 1400));
            owner.shareWriteAccessWith(home.resolve("wfile.bin"), Collections.singleton(writer.username)).join();
            UserContext writerAgain = login(writer.username, writerPassword);
            byte[] rewritten = new byte[1600];
            random.nextBytes(rewritten);
            writerAgain.getByPath(home.resolve("wfile.bin")).join().get()
                    .overwriteFile(AsyncReader.build(rewritten), rewritten.length, writerAgain.network, crypto, x -> {}).join();
            files.put(home.resolve("wfile.bin"), rewritten);
            toNestedFile = ref(owner, team.resolve("t.bin"));
            toWritableFile = ref(owner, home.resolve("wfile.bin"));

            link = owner.createSecretLink(home.resolve("small.bin").toString(), false, Optional.empty(), Optional.empty(), "", false).join();
            mkdir(owner, home, "linked");
            files.put(linked.resolve("l.bin"), upload(owner, linked, "l.bin", 900));
            writableLink = owner.createSecretLink(linked.toString(), true, Optional.empty(), Optional.empty(), "", false).join();
            files.put(linked.resolve("via-link.bin"), upload(fromLink(writableLink), linked, "via-link.bin", 700));
            mkdir(owner, home, "pub");
            files.put(pub.resolve("page.html"), upload(owner, pub, "page.html", 500));
            owner.makePublic(owner.getByPath(pub.resolve("page.html")).join().get()).join();

            oldRoot = writer(owner, home);
            oldTeam = writer(owner, team);
            oldInner = writer(owner, inner);
            oldRootCap = owner.getUserRoot().join().writableFilePointer();
            oldTeamCap = owner.getByPath(team).join().get().writableFilePointer();
            staleHome = owner.getUserRoot().join();
            staleTeam = owner.getByPath(team).join().get();
            Assert.assertTrue(oldRoot.isIdentity());
            Assert.assertTrue(oldTeam.isIdentity());
            Assert.assertTrue(oldInner.isIdentity());
            Assert.assertNotEquals(oldRoot, oldTeam);
            Assert.assertNotEquals(oldTeam, oldInner);
        }

        static FileRef ref(UserContext context, Path path) {
            FileWrapper file = context.getByPath(path).join().get();
            return new FileRef(path.toString(), file.getPointer().capability.readOnly(), file.getContentHash(context.network, crypto).join());
        }

        UserContext fromLink(LinkProperties props) {
            return UserContext.fromSecretLinkV2(props.toLinkString(owner.signer.publicKeyHash),
                    () -> Futures.of(""), network.clear(), crypto).join();
        }

        void assertAllReadable(UserContext context) {
            for (Map.Entry<Path, byte[]> e : files.entrySet())
                Assert.assertArrayEquals(e.getKey().toString(), e.getValue(), read(context, e.getKey()));
        }

        void assertMigrated() {
            SigningKeyPair.HYBRID_WRITERS = true;
            UserContext after = login(owner.username, password);
            assertAllHybrid(after, after.getUserRoot().join(), home);
            PublicKeyHash newTeam = writer(after, team);
            PublicKeyHash newInner = writer(after, inner);
            Assert.assertNotEquals(writer(after, home), newTeam);
            Assert.assertNotEquals(newTeam, newInner);
            assertAllReadable(after);

            // nothing moves twice
            UserContext again = login(owner.username, password);
            Assert.assertEquals(writer(after, home), writer(again, home));
            Assert.assertEquals(newTeam, writer(again, team));

            // the old writers keep only links to where their spaces are now
            assertRetired(again, oldRootCap, writer(again, home));
            assertRetired(again, oldTeamCap, newTeam);

            // the cap on the moved writing space carries over
            Assert.assertTrue(again.getWriteShareQuotas().join().stream()
                    .anyMatch(q -> q.writer.equals(newTeam) && q.quota.equals(Optional.of(10L * 1024 * 1024))));
            Assert.assertTrue(again.getWriteShareQuotas().join().stream().noneMatch(q -> q.writer.equals(oldTeam)));

            // the friend's entry point is to the old writer, which links it to the moved folder
            UserContext friendNow = login(friend.username, friendPassword);
            Assert.assertArrayEquals(files.get(docs.resolve("doc.bin")), read(friendNow, docs.resolve("doc.bin")));
            Assert.assertTrue(friendNow.getByPath(home).join().isPresent());

            // the write sharee is sent the new cap and can carry on writing
            UserContext writerNow = login(writer.username, writerPassword);
            Assert.assertEquals(newTeam, writer(writerNow, team));
            byte[] later = upload(writerNow, team, "later.bin", 1000);
            Assert.assertArrayEquals(later, read(login(owner.username, password), team.resolve("later.bin")));
            byte[] innerLater = upload(writerNow, inner, "inner-later.bin", 1000);
            Assert.assertArrayEquals(innerLater, read(login(owner.username, password), inner.resolve("inner-later.bin")));

            // the friend still has their writable folder, now under its new writer
            Assert.assertEquals(writer(after, collab), writer(friendNow, collab));
            assertHybrid(after, writer(friendNow, collab));
            Assert.assertArrayEquals(files.get(collab.resolve("c.bin")), read(friendNow, collab.resolve("c.bin")));
            Assert.assertArrayEquals(files.get(collab.resolve("from-friend.bin")), read(friendNow, collab.resolve("from-friend.bin")));
            byte[] friendLater = upload(friendNow, collab, "friend-later.bin", 800);
            Assert.assertArrayEquals(friendLater, read(login(owner.username, password), collab.resolve("friend-later.bin")));

            // the secret links still open, and the writable one still writes
            Assert.assertArrayEquals(files.get(home.resolve("small.bin")), read(fromLink(link), home.resolve("small.bin")));
            UserContext viaWritable = fromLink(writableLink);
            Assert.assertArrayEquals(files.get(linked.resolve("l.bin")), read(viaWritable, linked.resolve("l.bin")));
            Assert.assertArrayEquals(files.get(linked.resolve("via-link.bin")), read(viaWritable, linked.resolve("via-link.bin")));
            Assert.assertTrue(viaWritable.getByPath(linked).join().get().isWritable());
            byte[] linkLater = upload(viaWritable, linked, "link-later.bin", 600);
            Assert.assertArrayEquals(linkLater, read(login(owner.username, password), linked.resolve("link-later.bin")));

            // a file that is its own writing space moved too, and its sharee still writes it
            Assert.assertNotEquals(writer(after, home), writer(after, home.resolve("wfile.bin")));
            FileWrapper writable = writerNow.getByPath(home.resolve("wfile.bin")).join().get();
            Assert.assertEquals(writer(after, home.resolve("wfile.bin")), writable.writer());
            byte[] rewrittenLater = new byte[1700];
            random.nextBytes(rewrittenLater);
            writable.overwriteFile(AsyncReader.build(rewrittenLater), rewrittenLater.length, writerNow.network, crypto, x -> {}).join();
            Assert.assertArrayEquals(rewrittenLater, read(login(owner.username, password), home.resolve("wfile.bin")));
            files.put(home.resolve("wfile.bin"), rewrittenLater);

            // refs made before the move find the files by path, not a copy left under an old writer
            UserContext ownerNow = login(owner.username, password);
            Assert.assertTrue(ownerNow.network.getFile(toNestedFile.cap, owner.username).join().isEmpty());
            Assert.assertArrayEquals(files.get(team.resolve("t.bin")), readFile(ownerNow, ownerNow.getFile(toNestedFile).join().get()));
            // a cap to a space's root still resolves, through the link its old writer holds
            Assert.assertArrayEquals(rewrittenLater, readFile(ownerNow, ownerNow.network.getFile(toWritableFile.cap, owner.username).join().get()));
            Assert.assertArrayEquals(rewrittenLater, readFile(ownerNow, ownerNow.getFile(toWritableFile).join().get()));

            // the published file still opens
            FileWrapper page = UserContext.getPublicFile(pub.resolve("page.html"), network.clear(), crypto).join().get();
            Assert.assertTrue(page.writer().equals(writer(again, pub)));

            // nothing still in use is collected
            service.gc.collect(x -> Futures.of(true));
            assertAllReadable(login(owner.username, password));
            UserContext friendAfterGc = login(friend.username, friendPassword);
            Assert.assertArrayEquals(files.get(docs.resolve("doc.bin")), read(friendAfterGc, docs.resolve("doc.bin")));
            Assert.assertArrayEquals(friendLater, read(friendAfterGc, collab.resolve("friend-later.bin")));
            Assert.assertArrayEquals(linkLater, read(fromLink(writableLink), linked.resolve("link-later.bin")));
            Assert.assertArrayEquals(files.get(home.resolve("small.bin")), read(fromLink(link), home.resolve("small.bin")));
        }
    }

    @Test
    public void oldAccountMovesToHybridWritersOnLogin() {
        OldAccount old = new OldAccount();
        old.assertMigrated();
    }

    /** A login that stops once the home has moved leaves the account working through the old home's link, and the
     *  next login finishes
     */
    @Test
    public void aMoveStoppedAfterTheHomeMovedIsFinished() {
        OldAccount old = new OldAccount();
        SigningKeyPair.HYBRID_WRITERS = true;
        Assert.assertTrue(old.owner.switchHomeToHybridWriter(x -> {}).join());
        Assert.assertTrue(old.owner.isMovingToHybridWriters().join());
        Assert.assertFalse(old.owner.getUserRoot().join().writer().isIdentity());
        Assert.assertEquals(old.oldTeam, writer(old.owner, old.team));
        old.assertAllReadable(old.owner);
        old.assertMigrated();
    }

    /** A login that stops before replacing the entry point, with the nested spaces moved and the shares sent again,
     *  is finished by the next
     */
    @Test
    public void aMoveStoppedBeforeTheEntryPointIsFinished() {
        OldAccount old = new OldAccount();
        SigningKeyPair.HYBRID_WRITERS = true;
        old.owner.switchHomeToHybridWriter(x -> {}).join();
        try {
            old.owner.finishMoveToHybridWriters("not the password", UserTests::noMfa, x -> {}).join();
            Assert.fail("Replaced the entry point with the wrong password");
        } catch (Exception expected) {}
        Assert.assertTrue(old.owner.isMovingToHybridWriters().join());
        Assert.assertFalse(writer(old.owner, old.team).isIdentity());
        old.assertAllReadable(old.owner);
        old.assertMigrated();
    }

    /** A session from before the move can't write into a space that has moved: its update is from the old writer's
     *  version, which is gone, and merging onto the link left there conflicts
     */
    @Test
    public void staleSessionsCantWriteToMovedSpaces() {
        OldAccount old = new OldAccount();
        SigningKeyPair.HYBRID_WRITERS = true;
        UserContext after = login(old.owner.username, old.password);
        Assert.assertFalse(after.isMovingToHybridWriters().join());
        for (FileWrapper stale : Arrays.asList(old.staleHome, old.staleTeam)) {
            byte[] data = new byte[1000];
            random.nextBytes(data);
            try {
                stale.uploadOrReplaceFile("stale.bin", AsyncReader.build(data), data.length, old.owner.network, crypto, () -> false, x -> {}).join();
                Assert.fail("A stale session wrote to a moved space");
            } catch (Exception expected) {}
        }
        UserContext now = login(old.owner.username, old.password);
        Assert.assertTrue(now.getByPath(old.home.resolve("stale.bin")).join().isEmpty());
        Assert.assertTrue(now.getByPath(old.team.resolve("stale.bin")).join().isEmpty());
        assertRetired(now, old.oldRootCap, writer(now, old.home));
        assertRetired(now, old.oldTeamCap, writer(now, old.team));
        old.assertAllReadable(now);
    }

    /** Another device, logged in before the move, keeps writing while it runs. Each write either lands in the
     *  moved account or fails, never succeeds and is lost.
     */
    @Test
    public void writesDuringTheMoveAreKeptOrRefused() throws Exception {
        OldAccount old = new OldAccount();
        UserContext other = ensureSignedUp(old.owner.username, old.password, device(), crypto);
        SigningKeyPair.HYBRID_WRITERS = true;
        java.util.concurrent.atomic.AtomicBoolean moving = new java.util.concurrent.atomic.AtomicBoolean(true);
        Map<Path, byte[]> written = new java.util.concurrent.ConcurrentHashMap<>();
        java.util.concurrent.atomic.AtomicInteger refused = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.CompletableFuture<Void> writes = java.util.concurrent.CompletableFuture.runAsync(() -> {
            for (int i = 0; moving.get() || i < 3; i++) {
                for (Path dir : Arrays.asList(old.home, old.team, old.docs)) {
                    byte[] data = new byte[500 + i];
                    random.nextBytes(data);
                    String name = "during-" + i + ".bin";
                    try {
                        other.getByPath(dir).join().get().uploadOrReplaceFile(name, AsyncReader.build(data), data.length,
                                other.network, crypto, () -> false, x -> {}).join();
                        written.put(dir.resolve(name), data);
                    } catch (Exception e) {
                        refused.incrementAndGet();
                    }
                }
            }
        });
        // a move that keeps losing to concurrent writes is put off, which is safe, so it may take more than one login
        ensureSignedUp(old.owner.username, old.password, device(), crypto);
        moving.set(false);
        writes.get(10, java.util.concurrent.TimeUnit.MINUTES);
        Assert.assertFalse("Nothing was written during the move", written.isEmpty());
        UserContext now = login(old.owner.username, old.password);
        Assert.assertFalse(now.isMovingToHybridWriters().join());
        Assert.assertFalse(now.getUserRoot().join().writer().isIdentity());
        Assert.assertFalse(writer(now, old.team).isIdentity());
        for (Map.Entry<Path, byte[]> e : written.entrySet())
            Assert.assertArrayEquals(e.getKey() + " was written, then lost", e.getValue(), read(now, e.getKey()));
        old.assertAllReadable(now);
    }

    /** A write to the old home after the move has read it, but before the move commits, makes the move start again
     *  from the version with the write, rather than lose it
     */
    @Test
    public void aWriteBetweenTheSnapshotAndTheCommitIsKept() {
        OldAccount old = new OldAccount();
        UserContext other = ensureSignedUp(old.owner.username, old.password, device(), crypto);
        SigningKeyPair.HYBRID_WRITERS = true;
        NetworkAccess moving = device();
        byte[] data = new byte[777];
        random.nextBytes(data);
        java.util.concurrent.atomic.AtomicBoolean written = new java.util.concurrent.atomic.AtomicBoolean(false);
        moving.synchronizer.setCommitObserver((w, seq) -> {
            // the move's first commit to a new writer comes after it has read the old one
            if (! w.isIdentity() && written.compareAndSet(false, true))
                other.getByPath(old.home).join().get().uploadOrReplaceFile("between.bin", AsyncReader.build(data), data.length,
                        other.network, crypto, () -> false, x -> {}).join();
        });
        ensureSignedUp(old.owner.username, old.password, moving, crypto);
        Assert.assertTrue(written.get());
        UserContext now = login(old.owner.username, old.password);
        Assert.assertFalse(now.getUserRoot().join().writer().isIdentity());
        Assert.assertArrayEquals(data, read(now, old.home.resolve("between.bin")));
        old.assertAllReadable(now);
    }

    /** Two devices logging in at once both move the account. Each space moves once, as the second commit to an old
     *  writer finds it has moved and follows its link
     */
    @Test
    public void twoDevicesMoveAtOnce() throws Exception {
        OldAccount old = new OldAccount();
        SigningKeyPair.HYBRID_WRITERS = true;
        // separate devices don't share a write buffer, as contexts from one NetworkAccess do
        java.util.concurrent.CompletableFuture<UserContext> a = java.util.concurrent.CompletableFuture.supplyAsync(
                () -> ensureSignedUp(old.owner.username, old.password, device(), crypto));
        java.util.concurrent.CompletableFuture<UserContext> b = java.util.concurrent.CompletableFuture.supplyAsync(
                () -> ensureSignedUp(old.owner.username, old.password, device(), crypto));
        UserContext first = a.get(10, java.util.concurrent.TimeUnit.MINUTES), second = b.get(10, java.util.concurrent.TimeUnit.MINUTES);
        Assert.assertEquals(writer(first, old.home), writer(second, old.home));
        Assert.assertEquals(writer(first, old.team), writer(second, old.team));
        old.assertMigrated();
    }

    private static SocialPost post(UserContext context, FileRef ref) {
        FileWrapper file = context.getFile(ref).join()
                .orElseThrow(() -> new AssertionError(context.username + " can't open " + ref.path));
        return Serialize.parse(file, SocialPost::fromCbor, context.network, crypto).join();
    }

    private static FileRef shared(UserContext context, String sharer) {
        SocialFeed feed = context.getSocialFeed().join().update().join();
        Pair<SharedItem, FileWrapper> item = feed.getSharedFiles(0, 1000).join().stream()
                .filter(p -> p.left.sharer.equals(sharer) && ! p.right.isDirectory())
                .reduce((a, b) -> b)
                .orElseThrow(() -> new AssertionError(context.username + " has nothing from " + sharer));
        return new FileRef(item.left.path, item.left.cap, item.right.getContentHash(context.network, crypto).join());
    }

    /** Posts refer to each other by cap, which names the old writers once both sides have moved, so they are
     *  found by path
     */
    @Test
    public void postsAndCommentsStillResolve() {
        SigningKeyPair.HYBRID_WRITERS = false;
        String ownerPassword = generatePassword(), friendPassword = generatePassword();
        UserContext owner = login(generateUsername(random), ownerPassword);
        UserContext friend = login(generateUsername(random), friendPassword);
        friendBetweenGroups(Arrays.asList(owner), Arrays.asList(friend));

        SocialPost post = SocialPost.createInitialPost(owner.username, Arrays.asList(new Text("a post")), SocialPost.Resharing.Friends);
        Pair<Path, FileWrapper> posted = owner.getSocialFeed().join().update().join().createNewPost(post).join();
        owner.shareReadAccessWith(posted.left, Set.of(friend.username)).join();

        FileRef toPost = shared(friend, owner.username);
        SocialPost comment = SocialPost.createComment(toPost, SocialPost.Resharing.Friends, friend.username,
                Arrays.asList(new Text("a comment")));
        Pair<Path, FileWrapper> commented = friend.getSocialFeed().join().update().join().createNewPost(comment).join();
        friend.shareReadAccessWith(commented.left, Set.of(owner.username)).join();
        FileRef toComment = shared(owner, friend.username);
        Assert.assertEquals("a comment", ((Text) post(owner, toComment).body.get(0)).content);

        SigningKeyPair.HYBRID_WRITERS = true;
        UserContext ownerAfter = login(owner.username, ownerPassword);
        UserContext friendAfter = login(friend.username, friendPassword);
        assertHybrid(ownerAfter, ownerAfter.getUserRoot().join().writer());
        assertHybrid(friendAfter, friendAfter.getUserRoot().join().writer());
        ownerAfter = login(owner.username, ownerPassword);

        // the comment's reference to the post, and the post owner's to the comment
        Assert.assertTrue("the old cap still resolves, so the path isn't tested",
                friendAfter.network.getFile(toPost.cap, owner.username).join().isEmpty());
        SocialPost parent = post(friendAfter, post(friendAfter, toComment).parent.get());
        Assert.assertEquals("a post", ((Text) parent.body.get(0)).content);
        Assert.assertEquals("a post", ((Text) post(friendAfter, toPost).body.get(0)).content);
        Assert.assertTrue(ownerAfter.network.getFile(toComment.cap, friend.username).join().isEmpty());
        Assert.assertEquals("a comment", ((Text) post(ownerAfter, toComment).body.get(0)).content);
    }

    /** An account with no friends, shares or nested writing spaces */
    @Test
    public void loneAccountMoves() {
        SigningKeyPair.HYBRID_WRITERS = false;
        String password = generatePassword();
        UserContext owner = login(generateUsername(random), password);
        Path home = PathUtil.get(owner.username);
        byte[] data = upload(owner, home, "only.bin", 4000);
        WritableAbsoluteCapability oldRootCap = owner.getUserRoot().join().writableFilePointer();

        SigningKeyPair.HYBRID_WRITERS = true;
        UserContext after = login(owner.username, password);
        assertHybrid(after, writer(after, home));
        Assert.assertArrayEquals(data, read(after, home.resolve("only.bin")));
        byte[] more = upload(after, home, "more.bin", 4000);
        service.gc.collect(x -> Futures.of(true));
        UserContext again = login(owner.username, password);
        Assert.assertArrayEquals(data, read(again, home.resolve("only.bin")));
        Assert.assertArrayEquals(more, read(again, home.resolve("more.bin")));
        assertRetired(again, oldRootCap, writer(again, home));
    }
}
