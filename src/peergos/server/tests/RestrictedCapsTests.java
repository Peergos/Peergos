package peergos.server.tests;

import org.junit.*;
import peergos.server.*;
import peergos.server.util.*;
import peergos.shared.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.symmetric.*;
import peergos.shared.storage.*;
import peergos.shared.user.*;
import peergos.shared.user.fs.*;
import peergos.shared.user.fs.cryptree.*;
import peergos.shared.util.*;

import java.nio.file.*;
import java.util.*;
import java.util.function.*;
import java.util.stream.*;

public class RestrictedCapsTests {
    private static final Args args = UserTests.useMemoryDbs(UserTests.buildArgs()).with("enable-gc", "false");
    private static UserService service;
    private static final Crypto crypto = Main.initCrypto();
    private final Random random = new Random();

    @BeforeClass
    public static void init() {
        service = Main.PKI_INIT.main(args).localApi;
    }

    @After
    public void writeNewFormat() {
        CryptreeNode.setWriteLegacyFormat(false);
    }

    private NetworkAccess network() {
        return NetworkAccess.buildBuffered(new CachingStorage(service.storage, 1_000, 50 * 1024),
                service.bats, service.coreNode, service.account, service.mutable, 0, service.social,
                service.controller, service.usage, service.serverMessages, crypto.hasher, Arrays.asList("peergos"), false);
    }

    private UserContext signIn(String username) {
        return PeergosNetworkUtils.ensureSignedUp(username, "password", network(), crypto);
    }

    private String username(String prefix) {
        return prefix + Math.abs(random.nextInt() % 1_000_000);
    }

    private byte[] randomData(int size) {
        byte[] data = new byte[size];
        random.nextBytes(data);
        return data;
    }

    private FileWrapper upload(UserContext user, Path dir, String name, byte[] data) {
        return user.getByPath(dir).join().get()
                .uploadOrReplaceFile(name, AsyncReader.build(data), data.length, user.network, crypto, () -> false, x -> {})
                .join();
    }

    private FileWrapper mkdir(UserContext user, Path parent, String name) {
        return user.getByPath(parent).join().get()
                .mkdir(name, user.network, false, user.mirrorBatId(), crypto).join();
    }

    private static AbsoluteCapability restricted(FileWrapper f) {
        AbsoluteCapability cap = f.getPointer().capability;
        return cap.readOnly().withBaseKey(cap.rBaseKey.derive(CryptreeNode.RESTRICTED));
    }

    private FileWrapper open(AbsoluteCapability cap, String owner) {
        return network().getFile(cap, owner).join().get();
    }

    private static boolean isRestrictedFormat(FileWrapper f) {
        return f.getPointer().fileAccess.isRestrictedFormat(f.getPointer().capability.rBaseKey);
    }

    private static boolean isRestrictedKey(FileWrapper f) {
        return f.getPointer().fileAccess.isRestrictedKey(f.getPointer().capability.rBaseKey);
    }

    private static Optional<RelativeCapability> parentLink(FileWrapper f) {
        return f.getPointer().fileAccess.getParentCapability(f.getPointer().capability.rBaseKey);
    }

    private Map<String, FileWrapper> children(FileWrapper dir, NetworkAccess network) {
        return dir.getChildren(crypto.hasher, network).join().stream()
                .collect(Collectors.toMap(FileWrapper::getName, f -> f));
    }

    @Test
    public void newNodesAreRestrictedFormat() {
        UserContext user = signIn(username("rf"));
        Path home = PathUtil.get(user.username);
        mkdir(user, home, "dir");
        upload(user, home.resolve("dir"), "file.bin", randomData(1000));

        UserContext fresh = signIn(user.username);
        FileWrapper dir = fresh.getByPath(home.resolve("dir")).join().get();
        FileWrapper file = fresh.getByPath(home.resolve("dir/file.bin")).join().get();
        Assert.assertTrue(isRestrictedFormat(dir));
        Assert.assertTrue(isRestrictedFormat(file));
        Assert.assertFalse(isRestrictedKey(dir));
        Assert.assertFalse(isRestrictedKey(file));
        Assert.assertTrue(parentLink(file).isPresent());
        Assert.assertTrue(parentLink(dir).isPresent());
        Assert.assertEquals("/" + user.username + "/dir/file.bin", file.getPath(fresh.network).join());
    }

    @Test
    public void restrictedFileCap() {
        UserContext user = signIn(username("rc"));
        Path home = PathUtil.get(user.username);
        mkdir(user, home, "secret-dir");
        byte[] small = randomData(1000);
        byte[] large = randomData(Chunk.LEGACY_SIZE * 2 + 1000);
        upload(user, home.resolve("secret-dir"), "small.bin", small);
        upload(user, home.resolve("secret-dir"), "large.bin", large);

        for (Pair<String, byte[]> f : List.of(new Pair<>("small.bin", small), new Pair<>("large.bin", large))) {
            FileWrapper full = user.getByPath(home.resolve("secret-dir/" + f.left)).join().get();
            FileWrapper restricted = open(restricted(full), user.username);
            Assert.assertTrue(isRestrictedKey(restricted));
            Assert.assertEquals(f.left, restricted.getName());
            Assert.assertTrue(parentLink(restricted).isEmpty());
            Assert.assertFalse(restricted.isWritable());
            PeergosNetworkUtils.checkFileContents(f.right, restricted, user);
        }
    }

    @Test
    public void restrictedDirCap() {
        UserContext user = signIn(username("rd"));
        Path home = PathUtil.get(user.username);
        mkdir(user, home, "outer");
        mkdir(user, home.resolve("outer"), "shared");
        mkdir(user, home.resolve("outer/shared"), "sub");
        byte[] a = randomData(2000);
        byte[] b = randomData(Chunk.LEGACY_SIZE + 10);
        upload(user, home.resolve("outer/shared"), "a.bin", a);
        upload(user, home.resolve("outer/shared/sub"), "b.bin", b);

        FileWrapper full = user.getByPath(home.resolve("outer/shared")).join().get();
        NetworkAccess network = network();
        FileWrapper dir = network.getFile(restricted(full), user.username).join().get();
        Assert.assertTrue(isRestrictedKey(dir));
        Assert.assertEquals("shared", dir.getName());
        Assert.assertTrue(parentLink(dir).isEmpty());

        Map<String, FileWrapper> kids = children(dir, network);
        Assert.assertEquals(Set.of("a.bin", "sub"), kids.keySet());
        for (FileWrapper kid : kids.values()) {
            Assert.assertTrue(isRestrictedKey(kid));
            Assert.assertTrue(parentLink(kid).isEmpty());
        }
        PeergosNetworkUtils.checkFileContents(a, kids.get("a.bin"), user);
        Map<String, FileWrapper> grandKids = children(kids.get("sub"), network);
        Assert.assertEquals(Set.of("b.bin"), grandKids.keySet());
        Assert.assertTrue(isRestrictedKey(grandKids.get("b.bin")));
        Assert.assertTrue(parentLink(grandKids.get("b.bin")).isEmpty());
        PeergosNetworkUtils.checkFileContents(b, grandKids.get("b.bin"), user);
    }

    @Test
    public void legacyDataStillWorks() {
        UserContext user = signIn(username("rl"));
        Path home = PathUtil.get(user.username);
        CryptreeNode.setWriteLegacyFormat(true);
        mkdir(user, home, "old");
        mkdir(user, home.resolve("old"), "older");
        byte[] data = randomData(Chunk.LEGACY_SIZE + 5000);
        upload(user, home.resolve("old/older"), "file.bin", data);
        CryptreeNode.setWriteLegacyFormat(false);

        UserContext fresh = signIn(user.username);
        FileWrapper old = fresh.getByPath(home.resolve("old")).join().get();
        FileWrapper file = fresh.getByPath(home.resolve("old/older/file.bin")).join().get();
        Assert.assertFalse(isRestrictedFormat(old));
        Assert.assertFalse(isRestrictedFormat(file));
        PeergosNetworkUtils.checkFileContents(data, file, fresh);
        Assert.assertEquals("/" + user.username + "/old/older/file.bin", file.getPath(fresh.network).join());

        // new children of legacy directories, and legacy children of new ones
        byte[] newData = randomData(3000);
        upload(fresh, home.resolve("old"), "new.bin", newData);
        mkdir(fresh, home.resolve("old/older"), "newdir");
        FileWrapper newFile = fresh.getByPath(home.resolve("old/new.bin")).join().get();
        Assert.assertTrue(isRestrictedFormat(newFile));
        Assert.assertEquals("/" + user.username + "/old/new.bin", newFile.getPath(fresh.network).join());
        FileWrapper newDir = fresh.getByPath(home.resolve("old/older/newdir")).join().get();
        Assert.assertTrue(isRestrictedFormat(newDir));
        FileWrapper toMove = fresh.getByPath(home.resolve("old/older/file.bin")).join().get();
        toMove.moveTo(newDir, fresh.getByPath(home.resolve("old/older")).join().get(),
                home.resolve("old/older/file.bin"), fresh, () -> Futures.of(true)).join();

        UserContext again = signIn(user.username);
        FileWrapper moved = again.getByPath(home.resolve("old/older/newdir/file.bin")).join().get();
        PeergosNetworkUtils.checkFileContents(data, moved, again);
        Assert.assertEquals("/" + user.username + "/old/older/newdir/file.bin", moved.getPath(again.network).join());

        // modify and rename legacy nodes
        byte[] replaced = randomData(4000);
        moved.overwriteFile(AsyncReader.build(replaced), replaced.length, again.network, crypto, x -> {}).join();
        FileWrapper oldDir = again.getByPath(home.resolve("old/older")).join().get();
        oldDir.rename("renamed", again.getByPath(home.resolve("old")).join().get(), home.resolve("old/older"), again).join();

        UserContext last = signIn(user.username);
        FileWrapper finalFile = last.getByPath(home.resolve("old/renamed/newdir/file.bin")).join().get();
        PeergosNetworkUtils.checkFileContents(replaced, finalFile, last);
        Assert.assertEquals("/" + user.username + "/old/renamed/newdir/file.bin", finalFile.getPath(last.network).join());
        Assert.assertEquals(Set.of("new.bin", "renamed"), children(last.getByPath(home.resolve("old")).join().get(), last.network).keySet());
    }

    @Test
    public void walkingUpFromAShare() {
        UserContext owner = signIn(username("rw"));
        UserContext friend = signIn(username("rx"));
        PeergosNetworkUtils.friendBetweenGroups(List.of(owner), List.of(friend));
        Path home = PathUtil.get(owner.username);
        mkdir(owner, home, "a");
        mkdir(owner, home.resolve("a"), "b");
        upload(owner, home.resolve("a/b"), "c.bin", randomData(100));
        owner.shareReadAccessWith(home.resolve("a/b/c.bin"), Set.of(friend.username)).join();

        UserContext friendAgain = signIn(friend.username);
        FileWrapper shared = friendAgain.getByPath(home.resolve("a/b/c.bin")).join().get();
        Assert.assertEquals("/" + owner.username + "/a/b/c.bin", shared.getPath(friendAgain.network).join());
    }

    /** getPath always uses '/', where a Path's toString uses the platform separator. */
    private static String unix(Object path) {
        return path.toString().replace('\\', '/');
    }

    private UserContext openLink(UserContext owner, LinkProperties link) {
        return UserContext.fromSecretLinkV2(link.toLinkString(owner.signer.publicKeyHash), () -> Futures.of(""), network(), crypto).join();
    }

    private Set<String> topLevel(UserContext fromLink) {
        return children(fromLink.getByPath("/").join().get(), fromLink.network).keySet();
    }

    @Test
    public void hiddenLinkToAFile() {
        UserContext owner = signIn(username("hl"));
        Path home = PathUtil.get(owner.username);
        mkdir(owner, home, "private-folder");
        byte[] data = randomData(5000);
        upload(owner, home.resolve("private-folder"), "photo.jpg", data);
        String path = home.resolve("private-folder/photo.jpg").toString();
        LinkProperties link = owner.createSecretLink(List.of(path), List.of(), List.of(path),
                Optional.empty(), Optional.empty(), "", false).join();
        Assert.assertTrue(link.members.get(0).hidden);

        UserContext fromLink = openLink(owner, link);
        Assert.assertEquals("/", fromLink.getEntryPath().join());
        Assert.assertEquals(Set.of("photo.jpg"), topLevel(fromLink));
        FileWrapper file = fromLink.getByPath("/photo.jpg").join().get();
        Assert.assertTrue(file.isRestrictedKey());
        Assert.assertEquals("/photo.jpg", file.getPath(fromLink.network).join());
        Assert.assertTrue(file.retrieveParent(fromLink.network).join().isEmpty());
        PeergosNetworkUtils.checkFileContents(data, file, fromLink);

        List<LinkMember> members = owner.getSecretLinkMembers(link).join();
        Assert.assertEquals(List.of(new LinkMember(path, false, true)), members);
    }

    @Test
    public void hiddenLinkUpgradesLegacyData() {
        UserContext owner = signIn(username("hu"));
        Path home = PathUtil.get(owner.username);
        CryptreeNode.setWriteLegacyFormat(true);
        mkdir(owner, home, "old");
        mkdir(owner, home.resolve("old"), "shared");
        mkdir(owner, home.resolve("old/shared"), "inner");
        byte[] a = randomData(Chunk.LEGACY_SIZE + 100);
        byte[] b = randomData(100);
        upload(owner, home.resolve("old/shared"), "a.bin", a);
        upload(owner, home.resolve("old/shared/inner"), "b.bin", b);
        CryptreeNode.setWriteLegacyFormat(false);

        String path = home.resolve("old/shared").toString();
        LinkProperties link = owner.createSecretLink(List.of(path), List.of(), List.of(path),
                Optional.empty(), Optional.empty(), "", false).join();

        UserContext fromLink = openLink(owner, link);
        Assert.assertEquals("/shared", fromLink.getEntryPath().join());
        PeergosNetworkUtils.checkFileContents(a, fromLink.getByPath("/shared/a.bin").join().get(), fromLink);
        FileWrapper inner = fromLink.getByPath("/shared/inner/b.bin").join().get();
        Assert.assertTrue(inner.isRestrictedKey());
        PeergosNetworkUtils.checkFileContents(b, inner, fromLink);

        // the owner still sees everything where it was
        UserContext fresh = signIn(owner.username);
        FileWrapper ownerView = fresh.getByPath(home.resolve("old/shared/inner/b.bin")).join().get();
        Assert.assertTrue(isRestrictedFormat(ownerView));
        Assert.assertEquals("/" + owner.username + "/old/shared/inner/b.bin", ownerView.getPath(fresh.network).join());
        PeergosNetworkUtils.checkFileContents(a, fresh.getByPath(home.resolve("old/shared/a.bin")).join().get(), fresh);
        Assert.assertFalse(isRestrictedFormat(fresh.getByPath(home.resolve("old")).join().get()));
    }

    @Test(timeout = 120_000)
    public void writableHiddenLinkToLegacyDirectory() {
        UserContext owner = signIn(username("wl"));
        Path home = PathUtil.get(owner.username);
        CryptreeNode.setWriteLegacyFormat(true);
        mkdir(owner, home, "old");
        mkdir(owner, home.resolve("old"), "drop");
        mkdir(owner, home.resolve("old/drop"), "inner");
        byte[] a = randomData(Chunk.LEGACY_SIZE + 100);
        upload(owner, home.resolve("old/drop"), "a.bin", a);
        upload(owner, home.resolve("old/drop/inner"), "b.bin", randomData(100));
        CryptreeNode.setWriteLegacyFormat(false);

        String path = home.resolve("old/drop").toString();
        LinkProperties link = owner.createSecretLink(List.of(path), List.of(path), List.of(path),
                Optional.empty(), Optional.empty(), "", false).join();

        UserContext fromLink = openLink(owner, link);
        FileWrapper drop = fromLink.getByPath("/drop").join().get();
        Assert.assertTrue(drop.isWritable());
        PeergosNetworkUtils.checkFileContents(a, fromLink.getByPath("/drop/a.bin").join().get(), fromLink);
        byte[] added = randomData(200);
        upload(fromLink, PathUtil.get("/drop"), "added.txt", added);
        UserContext fresh = signIn(owner.username);
        PeergosNetworkUtils.checkFileContents(added, fresh.getByPath(home.resolve("old/drop/added.txt")).join().get(), fresh);
    }

    @Test(timeout = 120_000)
    public void secondWritableHiddenLink() {
        UserContext owner = signIn(username("w2"));
        Path home = PathUtil.get(owner.username);
        mkdir(owner, home, "work");
        mkdir(owner, home.resolve("work"), "drop");
        upload(owner, home.resolve("work/drop"), "a.txt", randomData(100));
        String path = home.resolve("work/drop").toString();
        LinkProperties first = owner.createSecretLink(List.of(path), List.of(path), List.of(path),
                Optional.empty(), Optional.empty(), "", false).join();
        upload(openLink(owner, first), PathUtil.get("/drop"), "via-link.txt", randomData(100));
        LinkProperties second = owner.createSecretLink(List.of(path), List.of(path), List.of(path),
                Optional.empty(), Optional.empty(), "", false).join();
        Assert.assertTrue(openLink(owner, second).getByPath("/drop/via-link.txt").join().isPresent());
        LinkProperties third = signIn(owner.username).createSecretLink(List.of(path), List.of(path), List.of(path),
                Optional.empty(), Optional.empty(), "", false).join();
        Assert.assertTrue(openLink(owner, third).getByPath("/drop/a.txt").join().isPresent());
    }

    @Test(timeout = 120_000)
    public void writableLinkToSomethingWithSeveralLinks() {
        UserContext owner = signIn(username("w3"));
        Path home = PathUtil.get(owner.username);
        mkdir(owner, home, "work");
        mkdir(owner, home.resolve("work"), "drop");
        upload(owner, home.resolve("work/drop"), "a.txt", randomData(100));
        String path = home.resolve("work/drop").toString();
        LinkProperties hidden = owner.createSecretLink(List.of(path), List.of(), List.of(path),
                Optional.empty(), Optional.empty(), "", false).join();
        LinkProperties visible = owner.createSecretLink(List.of(path), List.of(), List.of(),
                Optional.empty(), Optional.empty(), "", false).join();
        // making it writable moves it into its own writing space, which re-signs both links above
        LinkProperties writable = owner.createSecretLink(List.of(path), List.of(path), List.of(path),
                Optional.empty(), Optional.empty(), "", false).join();

        Assert.assertTrue(openLink(owner, hidden).getByPath("/drop/a.txt").join().isPresent());
        Assert.assertTrue(openLink(owner, visible).getByPath("/" + path + "/a.txt").join().isPresent());
        Assert.assertTrue(openLink(owner, writable).getByPath("/drop").join().get().isWritable());
    }

    private void move(UserContext ctx, String from, String toDir) {
        Path source = PathUtil.get(from);
        ctx.getByPath(from).join().get()
                .moveTo(ctx.getByPath(toDir).join().get(), ctx.getByPath(source.getParent()).join().get(),
                        source, ctx, () -> Futures.of(true)).join();
    }

    @Test(timeout = 120_000)
    public void movesThroughAHiddenFolderLink() {
        UserContext owner = signIn(username("mv"));
        Path home = PathUtil.get(owner.username);
        mkdir(owner, home, "work");
        mkdir(owner, home.resolve("work"), "drop");
        mkdir(owner, home.resolve("work/drop"), "sub");
        mkdir(owner, home.resolve("work/drop"), "nested");
        byte[] big = randomData(Chunk.LEGACY_SIZE + 100);
        byte[] inner = randomData(300);
        upload(owner, home.resolve("work/drop"), "big.bin", big);
        upload(owner, home.resolve("work/drop/nested"), "inner.txt", inner);
        String path = home.resolve("work/drop").toString();
        LinkProperties link = owner.createSecretLink(List.of(path), List.of(path), List.of(path),
                Optional.empty(), Optional.empty(), "", false).join();

        UserContext fromLink = openLink(owner, link);
        move(fromLink, "/drop/big.bin", "/drop/sub");
        move(fromLink, "/drop/nested", "/drop/sub");
        move(fromLink, "/drop/sub/nested/inner.txt", "/drop");
        fromLink.getByPath("/drop/inner.txt").join().get()
                .copyTo(fromLink.getByPath("/drop/sub/nested").join().get(), fromLink).join();

        UserContext again = openLink(owner, link);
        Assert.assertTrue(again.getByPath("/drop/big.bin").join().isEmpty());
        Assert.assertTrue(again.getByPath("/drop/nested").join().isEmpty());
        PeergosNetworkUtils.checkFileContents(big, again.getByPath("/drop/sub/big.bin").join().get(), again);
        PeergosNetworkUtils.checkFileContents(inner, again.getByPath("/drop/inner.txt").join().get(), again);
        Assert.assertTrue(again.getByPath("/drop/sub/nested").join().get().isDirectory());
        PeergosNetworkUtils.checkFileContents(inner, again.getByPath("/drop/sub/nested/inner.txt").join().get(), again);

        // the owner sees them where they went, and can share them like anything else made through the link
        UserContext fresh = signIn(owner.username);
        FileWrapper moved = fresh.getByPath(home.resolve("work/drop/sub/big.bin")).join().get();
        Assert.assertTrue(moved.isPending());
        PeergosNetworkUtils.checkFileContents(big, moved, fresh);
        PeergosNetworkUtils.checkFileContents(inner, fresh.getByPath(home.resolve("work/drop/inner.txt")).join().get(), fresh);
        Assert.assertEquals(Set.of("big.bin", "nested"), fresh.getByPath(home.resolve("work/drop/sub")).join().get()
                .getChildren(crypto.hasher, fresh.network).join().stream().map(FileWrapper::getName).collect(Collectors.toSet()));
        String movedPath = home.resolve("work/drop/sub/big.bin").toString();
        LinkProperties visible = fresh.createSecretLink(List.of(movedPath), List.of(), List.of(),
                Optional.empty(), Optional.empty(), "", false).join();
        Assert.assertEquals("/" + unix(movedPath), openLink(owner, visible).getByPath("/" + unix(movedPath)).join().get()
                .getPath(fresh.network).join());
    }

    @Test(timeout = 120_000)
    public void ownerMovesDontShowALinkWhereThingsGo() {
        UserContext owner = signIn(username("om"));
        Path home = PathUtil.get(owner.username);
        mkdir(owner, home, "work");
        mkdir(owner, home.resolve("work"), "drop");
        mkdir(owner, home.resolve("work/drop"), "sub");
        byte[] own = randomData(300);
        upload(owner, home.resolve("work/drop"), "own.txt", own);
        String path = home.resolve("work/drop").toString();
        LinkProperties link = owner.createSecretLink(List.of(path), List.of(path), List.of(path),
                Optional.empty(), Optional.empty(), "", false).join();

        UserContext fromLink = openLink(owner, link);
        byte[] up = randomData(200);
        fromLink.getByPath("/drop").join().get()
                .uploadOrReplaceFile("up.bin", AsyncReader.build(up), up.length, fromLink.network, crypto, () -> false, x -> {}).join();
        fromLink.getByPath("/drop").join().get().mkdir("made", fromLink.network, false, Optional.empty(), crypto).join();

        // whoever made these chose their full keys, which are the ones the owner reads them with
        UserContext fresh = signIn(owner.username);
        Path drop = home.resolve("work/drop");
        AbsoluteCapability uploadersCap = fresh.getByPath(drop.resolve("up.bin")).join().get().getPointer().capability.readOnly();
        AbsoluteCapability madeCap = fresh.getByPath(drop.resolve("made")).join().get().getPointer().capability.readOnly();
        SymmetricKey ownKey = fresh.getByPath(drop.resolve("own.txt")).join().get().getPointer().capability.rBaseKey;
        move(fresh, drop + "/up.bin", drop + "/sub");
        move(fresh, drop + "/own.txt", drop + "/made");

        UserContext after = signIn(owner.username);
        FileWrapper moved = after.getByPath(drop.resolve("sub/up.bin")).join().get();
        PeergosNetworkUtils.checkFileContents(up, moved, after);
        Assert.assertFalse(moved.isPending());
        Assert.assertEquals("/" + unix(drop) + "/sub/up.bin", moved.getPath(after.network).join());
        Optional<FileWrapper> withUploadersKey = after.network.getFile(uploadersCap, owner.username).join();
        Assert.assertTrue(withUploadersKey.isEmpty() || withUploadersKey.get().retrieveParent(after.network).join().isEmpty());

        PeergosNetworkUtils.checkFileContents(own, after.getByPath(drop.resolve("made/own.txt")).join().get(), after);
        Optional<FileWrapper> madeByLink = after.network.getFile(madeCap, owner.username).join();
        Optional<FileWrapper> ownSeenByMaker = madeByLink.isEmpty() ? Optional.empty() :
                madeByLink.get().getChild("own.txt", crypto.hasher, after.network).join();
        Assert.assertTrue(ownSeenByMaker.isEmpty() || ! ownKey.equals(ownSeenByMaker.get().getPointer().capability.rBaseKey));
        PeergosNetworkUtils.checkFileContents(own, openLink(owner, link).getByPath("/drop/made/own.txt").join().get(), after);
    }

    private static void assertRefused(Runnable r) {
        try {
            r.run();
        } catch (Exception e) {
            return;
        }
        Assert.fail("expected the move to be refused");
    }

    @Test(timeout = 120_000)
    public void cantMoveAFolderMadeThroughALinkIntoItself() {
        UserContext owner = signIn(username("md"));
        Path home = PathUtil.get(owner.username);
        mkdir(owner, home, "work");
        mkdir(owner, home.resolve("work"), "drop");
        String path = home.resolve("work/drop").toString();
        LinkProperties link = owner.createSecretLink(List.of(path), List.of(path), List.of(path),
                Optional.empty(), Optional.empty(), "", false).join();

        UserContext fromLink = openLink(owner, link);
        fromLink.getByPath("/drop").join().get().mkdir("made", fromLink.network, false, Optional.empty(), crypto).join();
        fromLink.getByPath("/drop/made").join().get().mkdir("by-link", fromLink.network, false, Optional.empty(), crypto).join();
        byte[] keep = randomData(100);
        fromLink.getByPath("/drop/made").join().get()
                .uploadOrReplaceFile("keep.txt", AsyncReader.build(keep), keep.length, fromLink.network, crypto, () -> false, x -> {}).join();
        Path drop = home.resolve("work/drop");
        mkdir(owner, drop.resolve("made"), "by-owner");

        UserContext fresh = signIn(owner.username);
        for (String into : List.of("by-owner", "by-link"))
            assertRefused(() -> move(fresh, drop + "/made", drop + "/made/" + into));
        UserContext linkAgain = openLink(owner, link);
        assertRefused(() -> move(linkAgain, "/drop/made", "/drop/made/by-owner"));

        UserContext after = signIn(owner.username);
        Assert.assertEquals(Set.of("made"), children(after.getByPath(drop).join().get(), after.network).keySet());
        Assert.assertEquals(Set.of("by-owner", "by-link", "keep.txt"), children(after.getByPath(drop.resolve("made")).join().get(), after.network).keySet());
        PeergosNetworkUtils.checkFileContents(keep, after.getByPath(drop.resolve("made/keep.txt")).join().get(), after);
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(CryptreeNode node, String name) {
        try {
            java.lang.reflect.Field f = CryptreeNode.class.getDeclaredField(name);
            f.setAccessible(true);
            return (T) f.get(node);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    /** What a malicious client holding restricted and write keys can do: rewrite the base and parent blocks. */
    private static void rewrite(FileWrapper f, UserContext ctx,
                                Consumer<CborObject.CborMap> base,
                                Function<CborObject.CborMap, Map<String, Cborable>> parent) {
        rewrite(f, ctx, base, parent, Optional.empty());
    }

    private static void rewrite(FileWrapper f, UserContext ctx,
                                Consumer<CborObject.CborMap> base,
                                Function<CborObject.CborMap, Map<String, Cborable>> parent,
                                Optional<SymmetricKey> newParentKey) {
        CryptreeNode node = f.getPointer().fileAccess;
        SymmetricKey r = f.getPointer().capability.rBaseKey;
        SymmetricKey oldParentKey = node.getParentKey(r);
        SymmetricKey parentKey = newParentKey.orElse(oldParentKey);
        CborObject.CborMap baseBlock = ((PaddedCipherText) field(node, "fromBaseKey")).decrypt(r, c -> (CborObject.CborMap) c);
        base.accept(baseBlock);
        CborObject.CborMap parentBlock = ((PaddedCipherText) field(node, "fromParentKey")).decrypt(oldParentKey, c -> (CborObject.CborMap) c);
        CryptreeNode forged = new CryptreeNode(node.committedHash(), f.isDirectory(), node.bats, PaddedCipherText.build(r, baseBlock, 64),
                field(node, "childrenOrData"), PaddedCipherText.build(parentKey, CborObject.CborMap.build(parent.apply(parentBlock)), 16));
        WritableAbsoluteCapability cap = f.writableFilePointer();
        SigningPrivateKeyAndPublicHash signer = f.signingPair();
        ctx.network.synchronizer.applyComplexUpdate(cap.owner, signer, (v, c) -> IpfsTransaction.call(cap.owner,
                tid -> forged.commit(v, c, cap, signer, ctx.network, tid), ctx.network.dhtClient)).join();
    }

    private static Map<String, Cborable> withParentLinks(CborObject.CborMap parent, Optional<RelativeCapability> p, boolean keepQ) {
        Map<String, Cborable> res = new TreeMap<>();
        for (String k : parent.keySet())
            if (keepQ || ! k.equals("q"))
                res.put(k, parent.get(k));
        p.ifPresent(link -> res.put("p", link));
        return res;
    }

    private static void forgeParentKey(FileWrapper dir, UserContext ctx) {
        rewrite(dir, ctx, base -> base.put("x", SymmetricLink.fromPair(SymmetricKey.random(), SymmetricKey.random()).toCbor()),
                parent -> withParentLinks(parent, Optional.empty(), false));
    }

    private static void forgeParentLink(FileWrapper f, FileWrapper fakeParent, boolean keepQ, UserContext ctx) {
        AbsoluteCapability to = fakeParent.getPointer().capability;
        RelativeCapability p = new RelativeCapability(Optional.empty(), to.getMapKey(), to.bat, to.rBaseKey, Optional.empty());
        rewrite(f, ctx, base -> {}, parent -> withParentLinks(parent, Optional.of(p), keepQ));
    }

    @Test(timeout = 120_000)
    public void aForgedPlaintextParentLinkIsIgnored() {
        UserContext owner = signIn(username("pl"));
        Path home = PathUtil.get(owner.username);
        mkdir(owner, home, "work");
        mkdir(owner, home.resolve("work"), "drop");
        mkdir(owner, home.resolve("work/drop"), "sub");
        mkdir(owner, home.resolve("work/drop"), "both");
        mkdir(owner, home.resolve("work/drop"), "above");
        byte[] data = randomData(200);
        upload(owner, home.resolve("work/drop"), "existing.txt", data);
        upload(owner, home.resolve("work/drop/above"), "child.txt", data);
        String path = home.resolve("work/drop").toString();
        LinkProperties link = owner.createSecretLink(List.of(path), List.of(path), List.of(path),
                Optional.empty(), Optional.empty(), "", false).join();

        UserContext fromLink = openLink(owner, link);
        FileWrapper fake = fromLink.getByPath("/drop/sub").join().get();
        forgeParentLink(fromLink.getByPath("/drop/existing.txt").join().get(), fake, false, fromLink);
        forgeParentLink(fromLink.getByPath("/drop/both").join().get(), fake, true, fromLink);
        forgeParentLink(fromLink.getByPath("/drop/above").join().get(), fake, false, fromLink);

        UserContext fresh = signIn(owner.username);
        Path drop = home.resolve("work/drop");
        FileWrapper file = fresh.getByPath(drop.resolve("existing.txt")).join().get();
        Assert.assertTrue(file.isPending());
        Assert.assertTrue(file.retrieveParent(fresh.network).join().isEmpty());
        PeergosNetworkUtils.checkFileContents(data, file, fresh);

        // a plaintext link alongside the real one doesn't stop the owner reading it, and the real one wins
        FileWrapper both = fresh.getByPath(drop.resolve("both")).join().get();
        Assert.assertFalse(both.isPending());
        Assert.assertEquals("/" + unix(drop) + "/both", both.getPath(fresh.network).join());

        // nor is it followed when walking up from below
        FileWrapper child = fresh.getByPath(drop.resolve("above/child.txt")).join().get();
        FileWrapper above = child.retrieveParent(fresh.network).join().get();
        Assert.assertEquals("above", above.getName());
        Assert.assertTrue(above.retrieveParent(fresh.network).join().isEmpty());
    }

    @Test(timeout = 120_000)
    public void aForgedParentKeyIsntWrittenUnder() {
        UserContext owner = signIn(username("fp"));
        Path home = PathUtil.get(owner.username);
        mkdir(owner, home, "work");
        mkdir(owner, home.resolve("work"), "drop");
        mkdir(owner, home.resolve("work/drop"), "sub");
        mkdir(owner, home.resolve("work/drop"), "other");
        byte[] data = randomData(200);
        upload(owner, home.resolve("work/drop/sub"), "in.txt", data);
        String path = home.resolve("work/drop").toString();
        LinkProperties link = owner.createSecretLink(List.of(path), List.of(path), List.of(path),
                Optional.empty(), Optional.empty(), "", false).join();

        UserContext fromLink = openLink(owner, link);
        forgeParentKey(fromLink.getByPath("/drop/sub").join().get(), fromLink);

        UserContext fresh = signIn(owner.username);
        Path drop = home.resolve("work/drop");
        Assert.assertTrue(fresh.getByPath(drop.resolve("sub")).join().get().isPending());
        move(fresh, drop + "/sub", drop + "/other");

        UserContext after = signIn(owner.username);
        PeergosNetworkUtils.checkFileContents(data, after.getByPath(drop.resolve("other/sub/in.txt")).join().get(), after);
        FileWrapper seenByLink = openLink(owner, link).getByPath("/drop/other/sub").join().get();
        CryptreeNode node = seenByLink.getPointer().fileAccess;
        SymmetricKey pPrime = node.getParentKey(seenByLink.getPointer().capability.rBaseKey);
        Assert.assertTrue(node.getParentCapability(pPrime).isEmpty());
    }

    @Test(timeout = 120_000)
    public void aChildKeyLinkCantBeAFoldersParentKeyLink() {
        UserContext owner = signIn(username("ck"));
        Path home = PathUtil.get(owner.username);
        mkdir(owner, home, "work");
        mkdir(owner, home.resolve("work"), "drop");
        mkdir(owner, home.resolve("work/drop"), "sub");
        mkdir(owner, home.resolve("work/drop"), "other");
        byte[] data = randomData(200);
        String path = home.resolve("work/drop").toString();
        LinkProperties link = owner.createSecretLink(List.of(path), List.of(path), List.of(path),
                Optional.empty(), Optional.empty(), "", false).join();
        UserContext fromLink = openLink(owner, link);
        upload(fromLink, PathUtil.get("/drop/sub"), "c.txt", data);
        Path sub = home.resolve("work/drop/sub");
        // the writer chose c's full key
        SymmetricKey childKey = signIn(owner.username).getByPath(sub.resolve("c.txt")).join().get().getPointer().capability.rBaseKey;
        upload(signIn(owner.username), sub, "routine.txt", data);

        FileWrapper subViaLink = openLink(owner, link).getByPath("/drop/sub").join().get();
        AbsoluteCapability subCap = subViaLink.getPointer().capability;
        Snapshot v = fromLink.network.synchronizer.getValue(subCap.owner, subCap.writer).join();
        SymmetricLink childLink = subViaLink.getPointer().fileAccess.getDirectChildren(subCap, v, fromLink.network).join().stream()
                .filter(n -> n.name.name.equals("c.txt"))
                .findFirst().get().cap.fullKeyLink.get();
        rewrite(subViaLink, fromLink, base -> {
                    base.put("x", childLink.toCbor());
                    base.put("k", childKey.derive(CryptreeNode.PROPERTIES).toCbor());
                }, parent -> withParentLinks(parent, Optional.empty(), false),
                Optional.of(childKey.derive(CryptreeNode.PROPERTIES)));

        UserContext fresh = signIn(owner.username);
        FileWrapper forged = fresh.getByPath(sub).join().get();
        Assert.assertNotEquals(childKey, forged.getPointer().fileAccess.getParentKey(forged.getPointer().capability.rBaseKey));
        Assert.assertTrue(forged.isPending());
        Path drop = home.resolve("work/drop");
        move(fresh, drop + "/sub", drop + "/other");

        UserContext after = signIn(owner.username);
        PeergosNetworkUtils.checkFileContents(data, after.getByPath(drop.resolve("other/sub/c.txt")).join().get(), after);
        CryptreeNode moved = openLink(owner, link).getByPath("/drop/other/sub").join().get().getPointer().fileAccess;
        Optional<RelativeCapability> parent;
        try {
            parent = moved.getParentCapability(childKey);
        } catch (Exception e) {
            parent = Optional.empty();
        }
        Assert.assertTrue(parent.isEmpty());
    }

    private static boolean readsParentLink(CryptreeNode node, SymmetricKey key) {
        try {
            return node.getParentCapability(key).isPresent();
        } catch (Exception e) {
            return false;
        }
    }

    /** A folder made through the link, by a maker who then writes it whatever parent link they like. */
    private FileWrapper madeThroughLink(UserContext owner, LinkProperties link, String dir, Optional<RelativeCapability> parentLink) {
        UserContext fromLink = openLink(owner, link);
        fromLink.getByPath(dir).join().get().mkdir("made", fromLink.network, false, Optional.empty(), crypto).join();
        FileWrapper made = fromLink.getByPath(dir + "/made").join().get();
        SymmetricKey p = signIn(owner.username).getByPath(owner.username + "/work" + dir + "/made").join().get().getParentKey();
        rewrite(made, fromLink, base -> {}, parent -> {
            Map<String, Cborable> res = withParentLinks(parent, Optional.empty(), false);
            parentLink.ifPresent(l -> res.put("q", PaddedCipherText.build(p, l, 16)));
            return res;
        });
        // and the owner writes to the folder it is in
        upload(signIn(owner.username), PathUtil.get(owner.username + "/work" + dir), "routine.txt", randomData(10));
        return signIn(owner.username).getByPath(owner.username + "/work" + dir + "/made").join().get();
    }

    private LinkProperties dropLink(UserContext owner) {
        Path home = PathUtil.get(owner.username);
        mkdir(owner, home, "work");
        mkdir(owner, home.resolve("work"), "drop");
        mkdir(owner, home.resolve("work/drop"), "sub");
        mkdir(owner, home.resolve("work/drop"), "other");
        String path = home.resolve("work/drop").toString();
        return owner.createSecretLink(List.of(path), List.of(path), List.of(path),
                Optional.empty(), Optional.empty(), "", false).join();
    }

    @Test(timeout = 120_000)
    public void aFolderMadeThroughALinkWithoutAParentLinkIsStillPending() {
        UserContext owner = signIn(username("np"));
        LinkProperties link = dropLink(owner);
        FileWrapper made = madeThroughLink(owner, link, "/drop/sub", Optional.empty());
        SymmetricKey makersP = made.getParentKey();
        Assert.assertTrue(made.isPending());

        Path drop = PathUtil.get(owner.username, "work", "drop");
        move(signIn(owner.username), drop + "/sub/made", drop + "/other");
        CryptreeNode moved = openLink(owner, link).getByPath("/drop/other/made").join().get().getPointer().fileAccess;
        Assert.assertFalse(readsParentLink(moved, makersP));
    }

    @Test(timeout = 120_000)
    public void theOwnersFileInAFolderMadeThroughALinkIsPending() {
        UserContext owner = signIn(username("of"));
        LinkProperties link = dropLink(owner);
        madeThroughLink(owner, link, "/drop/sub", Optional.empty());
        Path drop = PathUtil.get(owner.username, "work", "drop");
        byte[] data = randomData(200);
        upload(signIn(owner.username), drop.resolve("sub/made"), "owners.txt", data);
        // the maker can open the owner's file with the folder's full key
        FileWrapper owners = signIn(owner.username).getByPath(drop.resolve("sub/made/owners.txt")).join().get();
        SymmetricKey ownersKey = owners.getPointer().capability.rBaseKey;
        Assert.assertTrue(owners.isPending());

        move(signIn(owner.username), drop + "/sub/made/owners.txt", drop + "/other");
        UserContext after = signIn(owner.username);
        PeergosNetworkUtils.checkFileContents(data, after.getByPath(drop.resolve("other/owners.txt")).join().get(), after);
        CryptreeNode moved = openLink(owner, link).getByPath("/drop/other/owners.txt").join().get().getPointer().fileAccess;
        Assert.assertFalse(readsParentLink(moved, ownersKey));
    }

    @Test(timeout = 120_000)
    public void aFolderMadeThroughALinkWithAReadableParentLinkIsAdopted() {
        UserContext owner = signIn(username("rp"));
        LinkProperties link = dropLink(owner);
        AbsoluteCapability drop = signIn(owner.username).getByPath(owner.username + "/work/drop").join().get().getPointer().capability;
        RelativeCapability fake = new RelativeCapability(Optional.empty(), drop.getMapKey(), drop.bat, SymmetricKey.random(), Optional.empty());
        FileWrapper made = madeThroughLink(owner, link, "/drop", Optional.of(fake));
        SymmetricKey makersP = made.getParentKey();
        Assert.assertTrue(made.isPending());

        String path = owner.username + "/work/drop/made";
        signIn(owner.username).createSecretLink(List.of(path), List.of(path), List.of(path),
                Optional.empty(), Optional.empty(), "", false).join();
        CryptreeNode after = openLink(owner, link).getByPath("/drop/made").join().get().getPointer().fileAccess;
        Assert.assertFalse(readsParentLink(after, makersP));
        Assert.assertFalse(signIn(owner.username).getByPath(path).join().get().isPending());
    }

    @Test(timeout = 120_000)
    public void aReadOnlyHiddenLinkToSomethingMadeThroughALink() {
        UserContext owner = signIn(username("ro"));
        LinkProperties link = dropLink(owner);
        UserContext fromLink = openLink(owner, link);
        fromLink.getByPath("/drop").join().get().mkdir("made", fromLink.network, false, Optional.empty(), crypto).join();
        byte[] data = randomData(200);
        upload(fromLink, PathUtil.get("/drop/made"), "in.txt", data);

        String path = owner.username + "/work/drop/made";
        LinkProperties readOnly = signIn(owner.username).createSecretLink(List.of(path), List.of(), List.of(path),
                Optional.empty(), Optional.empty(), "", false).join();
        UserContext viaReadOnly = openLink(owner, readOnly);
        PeergosNetworkUtils.checkFileContents(data, viaReadOnly.getByPath("/made/in.txt").join().get(), viaReadOnly);
    }

    @Test(timeout = 120_000)
    public void legacyDataMovedIntoAHiddenLinkIsUpgraded() {
        UserContext owner = signIn(username("lm"));
        Path home = PathUtil.get(owner.username);
        CryptreeNode.setWriteLegacyFormat(true);
        mkdir(owner, home, "old");
        mkdir(owner, home.resolve("old"), "inner");
        byte[] data = randomData(200);
        upload(owner, home.resolve("old/inner"), "file.bin", data);
        CryptreeNode.setWriteLegacyFormat(false);
        mkdir(owner, home, "work");
        mkdir(owner, home.resolve("work"), "drop");
        mkdir(owner, home.resolve("work/drop"), "sub");
        String path = home.resolve("work/drop").toString();
        // read only, so the drop folder shares a writing space with the old one, and a move re-points
        LinkProperties link = owner.createSecretLink(List.of(path), List.of(), List.of(path),
                Optional.empty(), Optional.empty(), "", false).join();

        move(signIn(owner.username), home.resolve("old/inner").toString(), home.resolve("work/drop/sub").toString());
        UserContext fromLink = openLink(owner, link);
        Assert.assertEquals(Set.of("inner"), children(fromLink.getByPath("/drop/sub").join().get(), fromLink.network).keySet());
        PeergosNetworkUtils.checkFileContents(data, fromLink.getByPath("/drop/sub/inner/file.bin").join().get(), fromLink);
        UserContext fresh = signIn(owner.username);
        Assert.assertEquals("/" + unix(home) + "/work/drop/sub/inner/file.bin",
                fresh.getByPath(home.resolve("work/drop/sub/inner/file.bin")).join().get().getPath(fresh.network).join());
    }

    @Test(timeout = 120_000)
    public void aBadChildLinkOnlyBreaksThatChild() {
        UserContext owner = signIn(username("bc"));
        Path home = PathUtil.get(owner.username);
        mkdir(owner, home, "work");
        mkdir(owner, home.resolve("work"), "drop");
        byte[] data = randomData(200);
        upload(owner, home.resolve("work/drop"), "existing.txt", data);
        upload(owner, home.resolve("work/drop"), "wrapped.txt", data);
        upload(owner, home.resolve("work/drop"), "sealed.txt", data);
        String path = home.resolve("work/drop").toString();
        LinkProperties link = owner.createSecretLink(List.of(path), List.of(path), List.of(path),
                Optional.empty(), Optional.empty(), "", false).join();

        // a malicious client replaces two links with ones whose full keys the owner can't open, one wrapped and one sealed
        UserContext fromLink = openLink(owner, link);
        AbsoluteCapability w = fromLink.getByPath("/drop/wrapped.txt").join().get().getPointer().capability;
        AbsoluteCapability s = fromLink.getByPath("/drop/sealed.txt").join().get().getPointer().capability;
        RelativeCapability wrapped = new RelativeCapability(Optional.empty(), w.getMapKey(), w.bat, w.rBaseKey, Optional.empty(),
                Optional.of(SymmetricLink.fromPair(SymmetricKey.random(), SymmetricKey.random())), Optional.empty());
        RelativeCapability sealed = new RelativeCapability(Optional.empty(), s.getMapKey(), s.bat, s.rBaseKey, Optional.empty(),
                Optional.empty(), Optional.of(SealedKey.seal(SymmetricKey.random(), SealedKey.publicKey(SymmetricKey.random()), crypto.random)));
        List<NamedRelativeCapability> bad = List.of(
                new NamedRelativeCapability("wrapped.txt", wrapped, Optional.of(false), Optional.empty(), Optional.empty()),
                new NamedRelativeCapability("sealed.txt", sealed, Optional.of(false), Optional.empty(), Optional.empty()));
        FileWrapper drop = fromLink.getByPath("/drop").join().get();
        WritableAbsoluteCapability dropCap = drop.writableFilePointer();
        SigningPrivateKeyAndPublicHash signer = drop.signingPair();
        fromLink.network.synchronizer.applyComplexUpdate(dropCap.owner, signer, (v, c) -> drop.getPointer().fileAccess
                .removeChildren(v, c, List.of(w, s), dropCap, Optional.empty(), fromLink.network, crypto.random, crypto.hasher)).join();
        FileWrapper emptied = fromLink.getByPath("/drop").join().get();
        fromLink.network.synchronizer.applyComplexUpdate(dropCap.owner, signer, (v, c) -> emptied.getPointer().fileAccess
                .addChildrenAndCommit(v, c, bad, dropCap, signer, Optional.empty(), fromLink.network, crypto)).join();

        UserContext fresh = signIn(owner.username);
        Path dropPath = home.resolve("work/drop");
        Set<String> all = Set.of("existing.txt", "wrapped.txt", "sealed.txt");
        Assert.assertEquals(all, children(fresh.getByPath(dropPath).join().get(), fresh.network).keySet());
        PeergosNetworkUtils.checkFileContents(data, fresh.getByPath(dropPath.resolve("existing.txt")).join().get(), fresh);
        for (String name : List.of("wrapped.txt", "sealed.txt"))
            Assert.assertTrue(fresh.getByPath(dropPath.resolve(name)).join().get().isRestrictedKey());

        // and the owner rewriting the folder leaves them as they were for the link
        upload(fresh, dropPath, "later.txt", data);
        UserContext again = openLink(owner, link);
        Assert.assertEquals(Set.of("existing.txt", "wrapped.txt", "sealed.txt", "later.txt"),
                children(again.getByPath("/drop").join().get(), again.network).keySet());
        for (String name : List.of("wrapped.txt", "sealed.txt"))
            PeergosNetworkUtils.checkFileContents(data, again.getByPath("/drop/" + name).join().get(), again);
    }

    @Test
    public void writableHiddenLinkToAFile() {
        UserContext owner = signIn(username("hw"));
        Path home = PathUtil.get(owner.username);
        mkdir(owner, home, "documents");
        upload(owner, home.resolve("documents"), "notes.txt", randomData(300));
        String path = home.resolve("documents/notes.txt").toString();
        LinkProperties link = owner.createSecretLink(List.of(path), List.of(path), List.of(path),
                Optional.empty(), Optional.empty(), "", false).join();

        UserContext fromLink = openLink(owner, link);
        FileWrapper file = fromLink.getByPath("/notes.txt").join().get();
        Assert.assertTrue(file.isWritable());
        Assert.assertTrue(file.isRestrictedKey());
        byte[] edited = randomData(Chunk.LEGACY_SIZE + 2000);
        file.overwriteFile(AsyncReader.build(edited), edited.length, fromLink.network, crypto, x -> {}).join();
        byte[] shorter = randomData(1000);
        fromLink.getByPath("/notes.txt").join().get()
                .overwriteFile(AsyncReader.build(shorter), shorter.length, fromLink.network, crypto, x -> {}).join();

        PeergosNetworkUtils.checkFileContents(shorter, openLink(owner, link).getByPath("/notes.txt").join().get(), fromLink);
        UserContext fresh = signIn(owner.username);
        FileWrapper ownerView = fresh.getByPath(path).join().get();
        PeergosNetworkUtils.checkFileContents(shorter, ownerView, fresh);
        Assert.assertEquals("/" + unix(path), ownerView.getPath(fresh.network).join());
    }

    @Test
    public void writableHiddenLinkToADirectory() {
        UserContext owner = signIn(username("hd"));
        UserContext friend = signIn(username("hf"));
        PeergosNetworkUtils.friendBetweenGroups(List.of(owner), List.of(friend));
        Path home = PathUtil.get(owner.username);
        mkdir(owner, home, "work");
        mkdir(owner, home.resolve("work"), "drop");
        byte[] existing = randomData(500);
        upload(owner, home.resolve("work/drop"), "existing.txt", existing);
        upload(owner, home.resolve("work/drop"), "doomed.txt", randomData(50));
        String path = home.resolve("work/drop").toString();
        LinkProperties link = owner.createSecretLink(List.of(path), List.of(path), List.of(path),
                Optional.empty(), Optional.empty(), "", false).join();

        UserContext fromLink = openLink(owner, link);
        Assert.assertEquals(Set.of("drop"), topLevel(fromLink));
        FileWrapper drop = fromLink.getByPath("/drop").join().get();
        Assert.assertTrue(drop.isWritable());
        Assert.assertTrue(drop.retrieveParent(fromLink.network).join().isEmpty());

        byte[] added = randomData(Chunk.LEGACY_SIZE + 10);
        drop = drop.uploadOrReplaceFile("added.bin", AsyncReader.build(added), added.length, fromLink.network, crypto, () -> false, x -> {}).join();
        drop.mkdir("sub", fromLink.network, false, Optional.empty(), crypto).join();
        byte[] nested = randomData(700);
        fromLink.getByPath("/drop/sub").join().get()
                .uploadOrReplaceFile("nested.txt", AsyncReader.build(nested), nested.length, fromLink.network, crypto, () -> false, x -> {}).join();
        byte[] edited = randomData(800);
        fromLink.getByPath("/drop/existing.txt").join().get()
                .overwriteFile(AsyncReader.build(edited), edited.length, fromLink.network, crypto, x -> {}).join();
        drop = fromLink.getByPath("/drop").join().get();
        fromLink.getByPath("/drop/existing.txt").join().get().rename("renamed.txt", drop, PathUtil.get("/drop/existing.txt"), fromLink).join();
        drop = fromLink.getByPath("/drop").join().get();
        fromLink.getByPath("/drop/doomed.txt").join().get().remove(drop, PathUtil.get("/drop/doomed.txt"), fromLink).join();

        // everything made through the link is readable by the link and only its own things are restricted
        UserContext again = openLink(owner, link);
        Assert.assertEquals(Set.of("added.bin", "sub", "renamed.txt"), children(again.getByPath("/drop").join().get(), again.network).keySet());
        for (String name : List.of("/drop/added.bin", "/drop/sub", "/drop/sub/nested.txt", "/drop/renamed.txt")) {
            FileWrapper f = again.getByPath(name).join().get();
            Assert.assertTrue(name, f.isRestrictedKey());
            Assert.assertTrue(name, f.retrieveParent(again.network).join().isEmpty());
        }
        PeergosNetworkUtils.checkFileContents(added, again.getByPath("/drop/added.bin").join().get(), again);
        PeergosNetworkUtils.checkFileContents(nested, again.getByPath("/drop/sub/nested.txt").join().get(), again);
        PeergosNetworkUtils.checkFileContents(edited, again.getByPath("/drop/renamed.txt").join().get(), again);

        // the owner reads it all with full keys
        UserContext fresh = signIn(owner.username);
        Path drop2 = home.resolve("work/drop");
        Assert.assertEquals(Set.of("added.bin", "sub", "renamed.txt"), children(fresh.getByPath(drop2).join().get(), fresh.network).keySet());
        FileWrapper ownerAdded = fresh.getByPath(drop2.resolve("added.bin")).join().get();
        Assert.assertFalse(ownerAdded.isRestrictedKey());
        Assert.assertTrue(ownerAdded.isPending());
        PeergosNetworkUtils.checkFileContents(added, ownerAdded, fresh);
        PeergosNetworkUtils.checkFileContents(nested, fresh.getByPath(drop2.resolve("sub/nested.txt")).join().get(), fresh);
        FileWrapper ownerRenamed = fresh.getByPath(drop2.resolve("renamed.txt")).join().get();
        Assert.assertFalse(ownerRenamed.isPending());
        Assert.assertEquals("/" + unix(drop2) + "/renamed.txt", ownerRenamed.getPath(fresh.network).join());

        // the owner can add to and change what the link made
        byte[] ownerData = randomData(400);
        upload(fresh, drop2.resolve("sub"), "from-owner.txt", ownerData);
        fresh.getByPath(drop2.resolve("added.bin")).join().get()
                .overwriteFile(AsyncReader.build(ownerData), ownerData.length, fresh.network, crypto, x -> {}).join();
        PeergosNetworkUtils.checkFileContents(ownerData, openLink(owner, link).getByPath("/drop/sub/from-owner.txt").join().get(), again);
        PeergosNetworkUtils.checkFileContents(ownerData, openLink(owner, link).getByPath("/drop/added.bin").join().get(), again);

        // sharing something the link made gives it a real place first
        fresh.shareReadAccessWith(drop2.resolve("sub/nested.txt"), Set.of(friend.username)).join();
        UserContext afterShare = signIn(owner.username);
        Assert.assertFalse(afterShare.getByPath(drop2.resolve("sub")).join().get().isPending());
        FileWrapper adopted = afterShare.getByPath(drop2.resolve("sub/nested.txt")).join().get();
        Assert.assertFalse(adopted.isPending());
        Assert.assertEquals("/" + unix(drop2) + "/sub/nested.txt", adopted.getPath(afterShare.network).join());
        UserContext friendAgain = signIn(friend.username);
        FileWrapper friendView = friendAgain.getByPath(drop2.resolve("sub/nested.txt")).join().get();
        PeergosNetworkUtils.checkFileContents(nested, friendView, friendAgain);
        Assert.assertEquals("/" + unix(drop2) + "/sub/nested.txt", friendView.getPath(friendAgain.network).join());

        // and the link still reads and writes it
        UserContext last = openLink(owner, link);
        PeergosNetworkUtils.checkFileContents(nested, last.getByPath("/drop/sub/nested.txt").join().get(), last);
        last.getByPath("/drop/sub").join().get()
                .uploadOrReplaceFile("later.txt", AsyncReader.build(nested), nested.length, last.network, crypto, () -> false, x -> {}).join();
        PeergosNetworkUtils.checkFileContents(nested, signIn(owner.username).getByPath(drop2.resolve("sub/later.txt")).join().get(), last);
    }

    @Test
    public void manyChildrenThroughARestrictedKey() {
        UserContext owner = signIn(username("hm"));
        Path home = PathUtil.get(owner.username);
        mkdir(owner, home, "big");
        String path = home.resolve("big").toString();
        LinkProperties link = owner.createSecretLink(List.of(path), List.of(path), List.of(path),
                Optional.empty(), Optional.empty(), "", false).join();
        int max = CryptreeNode.getMaxChildLinksPerBlob();
        CryptreeNode.setMaxChildLinkPerBlob(3);
        try {
            UserContext fromLink = openLink(owner, link);
            for (int i = 0; i < 8; i++)
                fromLink.getByPath("/big").join().get()
                        .uploadOrReplaceFile("f" + i, AsyncReader.build(new byte[]{(byte) i}), 1, fromLink.network, crypto, () -> false, x -> {}).join();
            Set<String> expected = IntStream.range(0, 8).mapToObj(i -> "f" + i).collect(Collectors.toSet());
            UserContext again = openLink(owner, link);
            Assert.assertEquals(expected, children(again.getByPath("/big").join().get(), again.network).keySet());
            UserContext fresh = signIn(owner.username);
            Assert.assertEquals(expected, children(fresh.getByPath(home.resolve("big")).join().get(), fresh.network).keySet());
            upload(fresh, home.resolve("big"), "owners", new byte[]{1});
            Assert.assertEquals(9, children(openLink(owner, link).getByPath("/big").join().get(), again.network).size());
        } finally {
            CryptreeNode.setMaxChildLinkPerBlob(max);
        }
    }
}
