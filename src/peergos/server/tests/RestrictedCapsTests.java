package peergos.server.tests;

import org.junit.*;
import peergos.server.*;
import peergos.server.util.*;
import peergos.shared.*;
import peergos.shared.crypto.symmetric.*;
import peergos.shared.storage.*;
import peergos.shared.user.*;
import peergos.shared.user.fs.*;
import peergos.shared.user.fs.cryptree.*;
import peergos.shared.util.*;

import java.nio.file.*;
import java.util.*;
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
        Assert.assertEquals("/" + path, ownerView.getPath(fresh.network).join());
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
        Assert.assertEquals("/" + drop2 + "/renamed.txt", ownerRenamed.getPath(fresh.network).join());

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
        Assert.assertEquals("/" + drop2 + "/sub/nested.txt", adopted.getPath(afterShare.network).join());
        UserContext friendAgain = signIn(friend.username);
        FileWrapper friendView = friendAgain.getByPath(drop2.resolve("sub/nested.txt")).join().get();
        PeergosNetworkUtils.checkFileContents(nested, friendView, friendAgain);
        Assert.assertEquals("/" + drop2 + "/sub/nested.txt", friendView.getPath(friendAgain.network).join());

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
