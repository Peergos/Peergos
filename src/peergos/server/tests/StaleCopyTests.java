package peergos.server.tests;

import org.junit.*;
import peergos.server.*;
import peergos.server.fuse.*;
import peergos.server.util.*;
import peergos.shared.*;
import peergos.shared.user.*;
import peergos.shared.user.fs.*;
import peergos.shared.util.*;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
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

    @Test
    public void removeChildWithStaleParent() {
        UserContext user = user();
        Path dir = dir(user);
        upload(user, dir, "a", 3000);
        FileWrapper staleParent = get(user, dir);
        FileWrapper a = get(user, dir.resolve("a"));
        upload(user, dir, "b", 3000);

        staleParent.removeChild(a, user.network, crypto.random, crypto.hasher).join();

        Assert.assertEquals(Set.of("b"), names(fresh(user), dir));
    }

    @Test
    public void setPropertiesOfAChangedFileIsRefused() {
        UserContext user = user();
        Path dir = dir(user);
        upload(user, dir, "a", 3000);
        FileWrapper stale = get(user, dir.resolve("a"));
        byte[] replaced = upload(user, dir, "a", 4000);

        FileProperties hidden = get(user, dir.resolve("a")).getFileProperties();
        hidden = new FileProperties(hidden.name, false, false, hidden.mimeType, hidden.size, hidden.modified, hidden.created,
                true, hidden.thumbnail, hidden.streamSecret, hidden.treeHash, hidden.chunkSize);
        try {
            stale.setProperties(hidden, crypto.hasher, user.network, Optional.of(get(user, dir))).join();
            Assert.fail("applied properties from a copy of a file that has since changed");
        } catch (Exception expected) {}

        UserContext fresh = fresh(user);
        Assert.assertFalse(get(fresh, dir.resolve("a")).getFileProperties().isHidden);
        Assert.assertArrayEquals(replaced, read(fresh, dir.resolve("a")));
    }

    @Test
    public void setSameNamePropertiesOfAChangedFileIsRefused() {
        UserContext user = user();
        Path dir = dir(user);
        upload(user, dir, "a", 3000);
        FileWrapper stale = get(user, dir.resolve("a"));
        byte[] replaced = upload(user, dir, "a", 4000);

        LocalDateTime when = LocalDateTime.of(2020, 1, 2, 3, 4, 5);
        try {
            stale.setSameNameProperties(stale.getFileProperties().withModified(when), user.network).join();
            Assert.fail("applied properties from a copy of a file that has since changed");
        } catch (Exception expected) {}

        UserContext fresh = fresh(user);
        Assert.assertNotEquals(when, get(fresh, dir.resolve("a")).getFileProperties().modified);
        Assert.assertArrayEquals(replaced, read(fresh, dir.resolve("a")));
    }

    @Test
    public void setSameNamePropertiesOnAStaleFolder() {
        UserContext user = user();
        Path dir = dir(user);
        FileWrapper stale = get(user, dir);
        upload(user, dir, "a", 3000);

        LocalDateTime when = LocalDateTime.of(2020, 1, 2, 3, 4, 5);
        stale.setSameNameProperties(stale.getFileProperties().withModified(when), user.network).join();

        UserContext fresh = fresh(user);
        Assert.assertEquals(when, get(fresh, dir).getFileProperties().modified);
        Assert.assertEquals("updating a folder's properties dropped a child", Set.of("a"), names(fresh, dir));
    }

    @Test
    public void mkdirInStaleFolder() {
        UserContext user = user();
        Path dir = dir(user);
        FileWrapper stale = get(user, dir);
        upload(user, dir, "a", 3000);

        stale.mkdir("sub", user.network, false, user.mirrorBatId(), crypto).join();

        Assert.assertEquals(Set.of("a", "sub"), names(fresh(user), dir));
    }

    @Test
    public void mkdirOfANameAddedSinceIsRefused() {
        UserContext user = user();
        Path dir = dir(user);
        FileWrapper stale = get(user, dir);
        upload(user, dir, "sub", 3000);

        try {
            stale.mkdir("sub", user.network, false, user.mirrorBatId(), crypto).join();
            Assert.fail("made a folder with the name of an existing file");
        } catch (Exception expected) {}
        Assert.assertEquals(Set.of("sub"), names(fresh(user), dir));
    }

    private static HashTree hash(byte[] data) {
        return HashTree.build(AsyncReader.build(data), 0, data.length, Chunk.LEGACY_SIZE, crypto.hasher).join();
    }

    private byte[] uploadWithHash(UserContext user, Path dir, String name, int size) {
        byte[] data = new byte[size];
        random.nextBytes(data);
        get(user, dir).uploadFileWithHash(name, AsyncReader.build(data), data.length, Optional.of(hash(data)),
                Optional.empty(), Optional.empty(), user.network, crypto, x -> {}).join();
        return data;
    }

    @Test
    public void overwriteFileWithStaleCopy() {
        UserContext user = user();
        Path dir = dir(user);
        upload(user, dir, "a", 3000);
        FileWrapper stale = get(user, dir.resolve("a"));
        upload(user, dir, "other", 3000);

        byte[] replacement = new byte[5000];
        random.nextBytes(replacement);
        stale.overwriteFile(AsyncReader.build(replacement), replacement.length, user.network, crypto, x -> {}).join();

        UserContext fresh = fresh(user);
        Assert.assertArrayEquals(replacement, read(fresh, dir.resolve("a")));
        Assert.assertEquals(Set.of("a", "other"), names(fresh, dir));
    }

    @Test
    public void overwriteChangedChunksComparesWithTheCurrentContents() {
        UserContext user = user();
        Path dir = dir(user);
        byte[] original = uploadWithHash(user, dir, "a", 3000);
        FileWrapper stale = get(user, dir.resolve("a"));
        byte[] other = new byte[3000];
        random.nextBytes(other);
        get(user, dir.resolve("a")).overwriteChangedChunks(AsyncReader.build(other), other.length, Optional.of(hash(other)),
                Optional.empty(), user.network, crypto, x -> {}).join();

        // our copy's chunk hashes match what we write, but the file no longer holds it
        stale.overwriteChangedChunks(AsyncReader.build(original), original.length, Optional.of(hash(original)),
                Optional.empty(), user.network, crypto, x -> {}).join();

        Assert.assertArrayEquals("skipped a chunk that only our copy says is unchanged",
                original, read(fresh(user), dir.resolve("a")));
    }

    @Test
    public void overwriteSetsHashAndModifiedTime() {
        UserContext user = user();
        Path dir = dir(user);
        uploadWithHash(user, dir, "a", 3000);
        byte[] replacement = new byte[4000];
        random.nextBytes(replacement);
        HashTree h = hash(replacement);
        LocalDateTime when = LocalDateTime.of(2021, 2, 3, 4, 5, 6);

        get(user, dir.resolve("a")).overwriteChangedChunks(AsyncReader.build(replacement), replacement.length,
                Optional.of(h), Optional.of(when), user.network, crypto, x -> {}).join();

        FileWrapper now = get(fresh(user), dir.resolve("a"));
        Assert.assertArrayEquals(replacement, read(fresh(user), dir.resolve("a")));
        Assert.assertEquals(h.rootHash, now.getFileProperties().treeHash.get().rootHash);
        Assert.assertEquals(when, now.getFileProperties().modified);
    }

    private byte[] appendToChild(UserContext user, FileWrapper folder, String name, long expectedSize, byte[] contents) {
        byte[] tail = new byte[50];
        random.nextBytes(tail);
        folder.appendToChild(name, expectedSize, tail, false, user.mirrorBatId(), user.network, crypto, x -> {}).join();
        byte[] res = Arrays.copyOf(contents, contents.length + tail.length);
        System.arraycopy(tail, 0, res, contents.length, tail.length);
        return res;
    }

    @Test
    public void appendToChildWithStaleFolder() {
        UserContext user = user();
        Path dir = dir(user);
        byte[] contents = upload(user, dir, "log", 100);
        FileWrapper stale = get(user, dir);
        contents = appendToChild(user, get(user, dir), "log", contents.length, contents);

        // our copy of the folder agrees with the size we expect, but the file has grown since
        try {
            appendToChild(user, stale, "log", 100, contents);
            Assert.fail("appended over an append since");
        } catch (CompletionException expected) {}
        Assert.assertArrayEquals(contents, read(fresh(user), dir.resolve("log")));

        // and the size the file has now is the one to expect
        contents = appendToChild(user, stale, "log", contents.length, contents);
        Assert.assertArrayEquals(contents, read(fresh(user), dir.resolve("log")));
    }

    /** The mount, with its stats of a folder and a file in it */
    private static class Mount extends PeergosFS {
        Mount(UserContext user) {
            super(user);
        }

        int truncate(FileWrapper folder, FileWrapper file, long size) {
            return truncate(new PeergosStat(folder, folder.getFileProperties()), new PeergosStat(file, file.getFileProperties()), size);
        }
    }

    @Test
    public void growByTruncateWithStaleStat() {
        Assume.assumeTrue("the mount needs libfuse", System.getProperty("os.name").toLowerCase().startsWith("linux"));
        UserContext user = user();
        Path dir = dir(user);
        Path path = dir.resolve("a");
        byte[] contents = upload(user, dir, "a", 1000);
        FileWrapper stale = get(user, path);
        byte[] tail = new byte[500];
        random.nextBytes(tail);
        get(user, path).overwriteSectionJS(AsyncReader.build(tail), 0, 1000, 0, 1500, Optional.empty(),
                user.network, crypto, x -> {}).join();
        contents = Arrays.copyOf(contents, 1500);
        System.arraycopy(tail, 0, contents, 1000, tail.length);
        Mount mount = new Mount(user);
        int chunkSize = stale.getFileProperties().chunkSize;

        // from the size the file has now, not the one our stat has; then past the chunk after next
        for (long size : new long[]{2000, 2L * chunkSize + 5}) {
            Assert.assertEquals(0, mount.truncate(get(user, dir), stale, size));

            contents = Arrays.copyOf(contents, (int) size);
            Assert.assertArrayEquals("grown to " + size, contents, read(fresh(user), path));
            Optional<HashBranch> stored = get(fresh(user), path).getFileProperties().treeHash;
            HashTree now = HashTree.build(AsyncReader.build(contents), 0, contents.length, chunkSize, crypto.hasher).join();
            Assert.assertTrue("stored " + stored, stored.isEmpty() || stored.get().equals(now.branch(0)));
        }
    }
}
