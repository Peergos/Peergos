package peergos.server.tests;

import org.junit.*;
import peergos.server.*;
import peergos.server.storage.*;
import peergos.server.util.*;
import peergos.shared.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.*;
import peergos.shared.mutable.*;
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

    private static UserContext login(String username, String password) {
        return ensureSignedUp(username, password, network.clear(), crypto);
    }

    private static byte[] read(UserContext context, Path path) {
        FileWrapper file = context.getByPath(path).join()
                .orElseThrow(() -> new AssertionError(context.username + " can't see " + path));
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

    /** Every writer holding a file or directory in this subtree */
    private static Set<PublicKeyHash> writersUnder(UserContext context, FileWrapper dir) {
        Set<PublicKeyHash> res = new HashSet<>();
        res.add(dir.writer());
        if (dir.isDirectory())
            for (FileWrapper child : dir.getChildren(crypto.hasher, context.network).join())
                res.addAll(writersUnder(context, child));
        return res;
    }

    private static void assertOwnedKeyCanNotCommit(PublicKeyHash owner, SigningPrivateKeyAndPublicHash old) {
        PointerUpdate current = service.mutable.getPointerTarget(owner, old.publicKeyHash, service.storage).join();
        PointerUpdate cas = new PointerUpdate(current.updated, MaybeMultihash.empty(), PointerUpdate.increment(current.sequence));
        boolean set;
        try {
            set = service.mutable.setPointer(owner, old, cas).join();
        } catch (Exception e) {
            set = false;
        }
        Assert.assertFalse("A retired writer can still commit", set);
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
        final PublicKeyHash oldRoot, oldTeam, oldInner;
        final SigningPrivateKeyAndPublicHash oldTeamSigner;

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
            oldTeamSigner = owner.getByPath(team).join().get().signingPair();
            Assert.assertTrue(oldRoot.isIdentity());
            Assert.assertTrue(oldTeam.isIdentity());
            Assert.assertTrue(oldInner.isIdentity());
            Assert.assertNotEquals(oldRoot, oldTeam);
            Assert.assertNotEquals(oldTeam, oldInner);
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
            for (PublicKeyHash w : writersUnder(after, after.getUserRoot().join()))
                assertHybrid(after, w);
            PublicKeyHash newTeam = writer(after, team);
            PublicKeyHash newInner = writer(after, inner);
            Assert.assertNotEquals(writer(after, home), newTeam);
            Assert.assertNotEquals(newTeam, newInner);
            assertAllReadable(after);

            // nothing moves twice
            UserContext again = login(owner.username, password);
            Assert.assertEquals(writer(after, home), writer(again, home));
            Assert.assertEquals(newTeam, writer(again, team));

            // the old writer of a moved space can't commit
            assertOwnedKeyCanNotCommit(owner.signer.publicKeyHash, oldTeamSigner);

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

    /** A move that fails before its commit point leaves the old account working, and the next login moves it */
    @Test
    public void aMoveStoppedBeforeItsCommitPointIsRedone() {
        OldAccount old = new OldAccount();
        SigningKeyPair.HYBRID_WRITERS = true;
        UserContext context = old.owner;
        try {
            context.moveToHybridWriters("not the password", UserTests::noMfa, x -> {}).join();
            Assert.fail("Moved with the wrong password");
        } catch (Exception expected) {}

        SigningKeyPair.HYBRID_WRITERS = false;
        UserContext unmoved = login(old.owner.username, old.password);
        Assert.assertEquals(old.oldRoot, writer(unmoved, old.home));
        Assert.assertEquals(old.oldTeam, writer(unmoved, old.team));
        old.assertAllReadable(unmoved);
        Assert.assertArrayEquals(old.files.get(old.docs.resolve("doc.bin")),
                read(login(old.friend.username, old.friendPassword), old.docs.resolve("doc.bin")));

        old.assertMigrated();
    }

    /** An account with no friends, shares or nested writing spaces */
    @Test
    public void loneAccountMoves() {
        SigningKeyPair.HYBRID_WRITERS = false;
        String password = generatePassword();
        UserContext owner = login(generateUsername(random), password);
        Path home = PathUtil.get(owner.username);
        byte[] data = upload(owner, home, "only.bin", 4000);
        PublicKeyHash oldRoot = writer(owner, home);

        SigningKeyPair.HYBRID_WRITERS = true;
        UserContext after = login(owner.username, password);
        assertHybrid(after, writer(after, home));
        Assert.assertArrayEquals(data, read(after, home.resolve("only.bin")));
        byte[] more = upload(after, home, "more.bin", 4000);
        service.gc.collect(x -> Futures.of(true));
        UserContext again = login(owner.username, password);
        Assert.assertArrayEquals(data, read(again, home.resolve("only.bin")));
        Assert.assertArrayEquals(more, read(again, home.resolve("more.bin")));
        // with no friends to link, the old home writer goes
        Assert.assertFalse(DeletableContentAddressedStorage.getDirectOwnedKeys(after.signer.publicKeyHash,
                after.signer.publicKeyHash, service.mutable,
                (h, s) -> ContentAddressedStorage.getWriterData(after.signer.publicKeyHash, h, s, service.storage),
                service.storage, crypto.hasher).join().contains(oldRoot));
    }
}
