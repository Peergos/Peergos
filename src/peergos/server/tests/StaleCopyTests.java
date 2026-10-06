package peergos.server.tests;

import org.junit.*;
import peergos.server.*;
import peergos.server.util.*;
import peergos.shared.*;
import peergos.shared.user.*;
import peergos.shared.user.fs.*;
import peergos.shared.util.*;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.stream.*;

/** Callers, like the windows mount, retrieve a file or folder and then queue behind other writes before changing
 *  it. A change made with a copy that has since gone out of date must still apply to the current state.
 */
public class StaleCopyTests {
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

    private UserContext user() {
        String username = "sc" + Math.abs(random.nextInt() % 1_000_000);
        UserContext user = PeergosNetworkUtils.ensureSignedUp(username, "password", network(), crypto);
        user.getUserRoot().join().mkdir("dir", user.network, false, user.mirrorBatId(), crypto).join();
        return user;
    }

    private static Path dir(UserContext user) {
        return PathUtil.get(user.username, "dir");
    }

    private static FileWrapper get(UserContext user, Path p) {
        return user.getByPath(p).join().get();
    }

    private byte[] upload(UserContext user, Path dir, String name, int size) {
        byte[] data = new byte[size];
        random.nextBytes(data);
        get(user, dir).uploadOrReplaceFile(name, AsyncReader.build(data), data.length, user.network, crypto, () -> false, x -> {})
                .join();
        return data;
    }

    private static byte[] read(UserContext user, Path path) {
        FileWrapper file = get(user, path);
        return Serialize.readFully(file.getInputStream(user.network, crypto, x -> {}).join(), file.getSize()).join();
    }

    private UserContext fresh(UserContext user) {
        return PeergosNetworkUtils.ensureSignedUp(user.username, "password", network(), crypto);
    }

    private static Set<String> names(UserContext user, Path dir) {
        return get(user, dir).getChildren(crypto.hasher, user.network).join().stream()
                .map(FileWrapper::getName)
                .collect(Collectors.toSet());
    }

    private void assertNoDanglingLinks(UserContext user, Path dir) {
        FileWrapper folder = get(user, dir);
        Set<String> linked = folder.getChildrenCapabilities(crypto.hasher, user.network).join().stream()
                .map(c -> c.name.name)
                .collect(Collectors.toSet());
        Assert.assertEquals("links to children that can't be retrieved", linked, names(user, dir));
    }

    @Test
    public void renameWithStaleParent() {
        UserContext user = user();
        Path dir = dir(user);
        byte[] data = upload(user, dir, "X.mscz_saving", 3000);
        FileWrapper file = get(user, dir.resolve("X.mscz_saving"));
        FileWrapper staleParent = get(user, dir);
        upload(user, dir, "other", 3000);

        file.rename("X.mscz", staleParent, dir.resolve("X.mscz_saving"), user).join();

        UserContext fresh = fresh(user);
        Assert.assertEquals(Set.of("X.mscz", "other"), names(fresh, dir));
        Assert.assertArrayEquals(data, read(fresh, dir.resolve("X.mscz")));
        assertNoDanglingLinks(fresh, dir);
    }

    @Test
    public void renameWithStaleFile() {
        UserContext user = user();
        Path dir = dir(user);
        upload(user, dir, "a", 3000);
        FileWrapper staleFile = get(user, dir.resolve("a"));
        byte[] replaced = upload(user, dir, "a", 4000);

        staleFile.rename("b", get(user, dir), dir.resolve("a"), user).join();

        UserContext fresh = fresh(user);
        Assert.assertEquals(Set.of("b"), names(fresh, dir));
        Assert.assertArrayEquals("the rename put back the file's old properties", replaced, read(fresh, dir.resolve("b")));
    }

    @Test
    public void renameOntoANameAddedSinceIsRefused() {
        UserContext user = user();
        Path dir = dir(user);
        upload(user, dir, "a", 3000);
        FileWrapper file = get(user, dir.resolve("a"));
        FileWrapper staleParent = get(user, dir);
        upload(user, dir, "b", 3000);

        try {
            file.rename("b", staleParent, dir.resolve("a"), user).join();
            Assert.fail("renamed onto an existing name");
        } catch (Exception expected) {}
        Assert.assertEquals(Set.of("a", "b"), names(fresh(user), dir));
    }

    @Test
    public void moveWithStaleCopies() {
        UserContext user = user();
        Path dir = dir(user);
        Path target = PathUtil.get(user.username, "target");
        user.getUserRoot().join().mkdir("target", user.network, false, user.mirrorBatId(), crypto).join();
        byte[] data = upload(user, dir, "file", 3000);
        FileWrapper file = get(user, dir.resolve("file"));
        FileWrapper staleParent = get(user, dir);
        FileWrapper staleTarget = get(user, target);
        upload(user, dir, "left", 3000);
        upload(user, target, "there", 3000);

        file.moveTo(staleTarget, staleParent, dir.resolve("file"), user, () -> Futures.of(true)).join();

        UserContext fresh = fresh(user);
        Assert.assertEquals(Set.of("left"), names(fresh, dir));
        Assert.assertEquals(Set.of("there", "file"), names(fresh, target));
        Assert.assertArrayEquals(data, read(fresh, target.resolve("file")));
        assertNoDanglingLinks(fresh, dir);
        assertNoDanglingLinks(fresh, target);
    }

    @Test
    public void moveToAnotherWriterWithStaleCopies() {
        UserContext user = user();
        Path dir = dir(user);
        Path target = PathUtil.get(user.username, "team");
        user.getUserRoot().join().mkdir("team", user.network, false, user.mirrorBatId(), crypto).join();
        user.shareWriteAccessWith(target, new HashSet<>()).join();
        byte[] data = upload(user, dir, "file", 3000);
        FileWrapper file = get(user, dir.resolve("file"));
        FileWrapper staleParent = get(user, dir);
        FileWrapper staleTarget = get(user, target);
        Assert.assertNotEquals(staleParent.writer(), staleTarget.writer());
        upload(user, dir, "left", 3000);
        upload(user, target, "there", 3000);

        file.moveTo(staleTarget, staleParent, dir.resolve("file"), user, () -> Futures.of(true)).join();

        UserContext fresh = fresh(user);
        Assert.assertEquals(Set.of("left"), names(fresh, dir));
        Assert.assertEquals(Set.of("there", "file"), names(fresh, target));
        Assert.assertArrayEquals(data, read(fresh, target.resolve("file")));
        assertNoDanglingLinks(fresh, dir);
        assertNoDanglingLinks(fresh, target);
    }
}
