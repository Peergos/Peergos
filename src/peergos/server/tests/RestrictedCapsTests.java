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
}
