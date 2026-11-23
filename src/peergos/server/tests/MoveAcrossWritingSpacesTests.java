package peergos.server.tests;

import org.junit.*;
import peergos.server.*;
import peergos.server.util.*;
import peergos.shared.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.storage.*;
import peergos.shared.user.*;
import peergos.shared.user.fs.*;
import peergos.shared.util.*;

import java.nio.file.*;
import java.util.*;

/** A folder whose parent once had a writable link is in a writing space of its own, and moving it out re-encrypted
 *  and uploaded every file again, which for a large folder ran for hours and then failed.
 */
public class MoveAcrossWritingSpacesTests {
    private static final Args args = UserTests.useMemoryDbs(UserTests.buildArgs()).with("enable-gc", "false");
    private static UserService service;
    private static final Crypto crypto = Main.initCrypto();
    private static final Random random = new Random();

    @BeforeClass
    public static void init() {
        service = Main.PKI_INIT.main(args).localApi;
    }

    private UserContext signIn(String username) {
        NetworkAccess network = NetworkAccess.buildBuffered(new CachingStorage(service.storage, 1_000, 50 * 1024),
                service.bats, service.coreNode, service.account, service.mutable, 0, service.social,
                service.controller, service.usage, service.serverMessages, crypto.hasher, Arrays.asList("peergos"), false);
        return PeergosNetworkUtils.ensureSignedUp(username, "password", network, crypto);
    }

    private static String suffix() {
        return "" + Math.abs(random.nextInt() % 1_000_000);
    }

    private static void mkdir(UserContext user, Path parent, String name) {
        user.getByPath(parent).join().get().mkdir(name, user.network, false, user.mirrorBatId(), crypto).join();
    }

    private static byte[] upload(UserContext user, Path dir, String name, int size) {
        byte[] data = new byte[size];
        random.nextBytes(data);
        user.getByPath(dir).join().get()
                .uploadOrReplaceFile(name, AsyncReader.build(data), data.length, user.network, crypto, () -> false, x -> {}).join();
        return data;
    }

    private static byte[] read(UserContext user, Path path) {
        FileWrapper file = user.getByPath(path).join()
                .orElseThrow(() -> new AssertionError(user.username + " can't see " + path));
        AsyncReader in = file.getInputStream(user.network, crypto, x -> {}).join();
        return Serialize.readFully(in, file.getFileProperties().size).join();
    }

    private static void move(UserContext user, Path from, Path targetDir, String newName) {
        move(user, from, targetDir, newName, true);
    }

    private static void move(UserContext user, Path from, Path targetDir, String newName, boolean keepAccess) {
        user.getByPath(from).join().get().moveTo(user.getByPath(targetDir).join().get(),
                user.getByPath(from.getParent()).join().get(), from, newName, user, () -> Futures.of(keepAccess)).join();
    }

    private static ByteArrayWrapper dataKey(UserContext user, Path path) {
        FileWrapper f = user.getByPath(path).join().get();
        return new ByteArrayWrapper(f.getPointer().fileAccess.getDataKey(f.getPointer().capability.rBaseKey).getKey());
    }

    private static PublicKeyHash writer(UserContext user, Path path) {
        return user.getByPath(path).join().get().writer();
    }

    private static ByteArrayWrapper mapKey(UserContext user, Path path) {
        return new ByteArrayWrapper(user.getByPath(path).join().get().getLocation().getMapKey());
    }

    @Test
    public void folderMovesOutOfARevokedWritableLinkWithoutCopying() {
        UserContext owner = signIn("mw" + suffix());
        Path home = PathUtil.get(owner.username);
        mkdir(owner, home, "p");
        mkdir(owner, home, "t");
        Path p = home.resolve("p"), t = home.resolve("t");
        mkdir(owner, p, "big");
        Path big = p.resolve("big");
        mkdir(owner, big, "s");
        byte[] multiChunk = upload(owner, big, "large.bin", 2 * Chunk.LEGACY_SIZE + 1);
        byte[] small = upload(owner, big.resolve("s"), "doc.pdf", 1024);
        LinkProperties link = owner.createSecretLink(p.toString(), true, Optional.empty(), Optional.empty(), "", false).join();
        owner.deleteSecretLink(link.label, p, true).join();
        Assert.assertNotEquals(writer(owner, home), writer(owner, p));

        ByteArrayWrapper bigKey = mapKey(owner, big), fileKey = mapKey(owner, big.resolve("large.bin"));
        move(owner, big, t, "moved");

        UserContext fresh = signIn(owner.username);
        Path moved = t.resolve("moved");
        Assert.assertTrue(fresh.getByPath(big).join().isEmpty());
        Assert.assertEquals("moved", fresh.getByPath(moved).join().get().getName());
        Assert.assertArrayEquals(multiChunk, read(fresh, moved.resolve("large.bin")));
        Assert.assertArrayEquals(small, read(fresh, moved.resolve("s/doc.pdf")));
        Assert.assertEquals(writer(fresh, t), writer(fresh, moved));
        Assert.assertEquals(writer(fresh, t), writer(fresh, moved.resolve("s/doc.pdf")));
        // a copy would have new map keys
        Assert.assertEquals(bigKey, mapKey(fresh, moved));
        Assert.assertEquals(fileKey, mapKey(fresh, moved.resolve("large.bin")));
        // the old writing space keeps nothing of the move
        Assert.assertTrue(fresh.getByPath(p).join().get().getChildren(crypto.hasher, fresh.network).join().isEmpty());
    }

    @Test
    public void writingSpaceNestedInTheMovedFolderKeepsItsShareAndOutlivesTheOldOne() {
        UserContext owner = signIn("mn" + suffix());
        UserContext friend = signIn("mf" + suffix());
        PeergosNetworkUtils.friendBetweenGroups(List.of(owner), List.of(friend));
        Path home = PathUtil.get(owner.username);
        mkdir(owner, home, "p");
        mkdir(owner, home, "t");
        Path p = home.resolve("p"), t = home.resolve("t");
        mkdir(owner, p, "big");
        Path big = p.resolve("big");
        mkdir(owner, big, "n");
        Path nested = big.resolve("n");
        byte[] data = upload(owner, nested, "doc.pdf", 1024);
        LinkProperties link = owner.createSecretLink(p.toString(), true, Optional.empty(), Optional.empty(), "", false).join();
        owner.deleteSecretLink(link.label, p, true).join();
        owner.shareWriteAccessWith(nested, Set.of(friend.username)).join();
        PublicKeyHash nestedWriter = writer(owner, nested);

        move(owner, big, t, "big");

        Path moved = t.resolve("big");
        owner.getByPath(p).join().get().remove(owner.getUserRoot().join(), p, owner).join();
        UserContext fresh = signIn(owner.username);
        Assert.assertEquals(writer(fresh, t), writer(fresh, moved));
        Assert.assertEquals(nestedWriter, writer(fresh, moved.resolve("n")));
        Assert.assertArrayEquals(data, read(fresh, moved.resolve("n/doc.pdf")));
        Assert.assertEquals(Set.of(friend.username), fresh.sharedWith(moved.resolve("n")).join().writeAccess);

        UserContext friendFresh = signIn(friend.username);
        Assert.assertArrayEquals(data, read(friendFresh, moved.resolve("n/doc.pdf")));
        byte[] fromFriend = upload(friendFresh, moved.resolve("n"), "reply.pdf", 512);
        Assert.assertArrayEquals(fromFriend, read(signIn(owner.username), moved.resolve("n/reply.pdf")));
    }

    @Test
    public void moveWithoutKeepingAccessRevokesSharesButKeepsTheData() {
        UserContext owner = signIn("mr" + suffix());
        UserContext friend = signIn("mrf" + suffix());
        PeergosNetworkUtils.friendBetweenGroups(List.of(owner), List.of(friend));
        Path home = PathUtil.get(owner.username);
        mkdir(owner, home, "a");
        mkdir(owner, home, "t");
        Path a = home.resolve("a"), t = home.resolve("t");
        mkdir(owner, a, "big");
        Path big = a.resolve("big");
        mkdir(owner, big, "s");
        byte[] multiChunk = upload(owner, big, "large.bin", 2 * Chunk.LEGACY_SIZE + 1);
        byte[] small = upload(owner, big.resolve("s"), "doc.pdf", 1024);
        owner.shareReadAccessWith(big.resolve("s/doc.pdf"), Set.of(friend.username)).join();
        Assert.assertTrue(signIn(friend.username).getByPath(big.resolve("s/doc.pdf")).join().isPresent());
        ByteArrayWrapper fileKey = mapKey(owner, big.resolve("large.bin"));
        ByteArrayWrapper data = dataKey(owner, big.resolve("large.bin"));

        move(owner, big, t, "moved", false);

        UserContext fresh = signIn(owner.username);
        Path moved = t.resolve("moved");
        Assert.assertTrue(fresh.getByPath(big).join().isEmpty());
        Assert.assertArrayEquals(multiChunk, read(fresh, moved.resolve("large.bin")));
        Assert.assertArrayEquals(small, read(fresh, moved.resolve("s/doc.pdf")));
        Assert.assertNotEquals(fileKey, mapKey(fresh, moved.resolve("large.bin")));
        // a copy would have encrypted the data again under a new key
        Assert.assertEquals(data, dataKey(fresh, moved.resolve("large.bin")));
        Assert.assertTrue(fresh.sharedWith(moved.resolve("s/doc.pdf")).join().readAccess.isEmpty());
        UserContext friendFresh = signIn(friend.username);
        Assert.assertTrue(friendFresh.getByPath(moved.resolve("s/doc.pdf")).join().isEmpty());
        Assert.assertTrue(friendFresh.getByPath(big.resolve("s/doc.pdf")).join().isEmpty());
    }

    @Test
    public void moveOutOfAWritingSpaceWithoutKeepingAccessRevokesANestedWriteShare() {
        UserContext owner = signIn("mx" + suffix());
        UserContext friend = signIn("mxf" + suffix());
        PeergosNetworkUtils.friendBetweenGroups(List.of(owner), List.of(friend));
        Path home = PathUtil.get(owner.username);
        mkdir(owner, home, "p");
        mkdir(owner, home, "t");
        Path p = home.resolve("p"), t = home.resolve("t");
        mkdir(owner, p, "big");
        Path big = p.resolve("big");
        mkdir(owner, big, "n");
        Path nested = big.resolve("n");
        byte[] multiChunk = upload(owner, nested, "large.bin", 2 * Chunk.LEGACY_SIZE + 1);
        LinkProperties link = owner.createSecretLink(p.toString(), true, Optional.empty(), Optional.empty(), "", false).join();
        owner.deleteSecretLink(link.label, p, true).join();
        owner.shareWriteAccessWith(nested, Set.of(friend.username)).join();
        PublicKeyHash nestedWriter = writer(owner, nested);
        ByteArrayWrapper data = dataKey(owner, nested.resolve("large.bin"));

        move(owner, big, t, "big", false);

        Path moved = t.resolve("big");
        owner.getByPath(p).join().get().remove(owner.getUserRoot().join(), p, owner).join();
        UserContext fresh = signIn(owner.username);
        Assert.assertEquals(writer(fresh, t), writer(fresh, moved));
        Assert.assertNotEquals(nestedWriter, writer(fresh, moved.resolve("n")));
        Assert.assertArrayEquals(multiChunk, read(fresh, moved.resolve("n/large.bin")));
        Assert.assertEquals(data, dataKey(fresh, moved.resolve("n/large.bin")));
        Assert.assertTrue(fresh.sharedWith(moved.resolve("n")).join().writeAccess.isEmpty());
        Assert.assertTrue(signIn(friend.username).getByPath(moved.resolve("n")).join().isEmpty());
        byte[] more = upload(fresh, moved.resolve("n"), "more.pdf", 512);
        Assert.assertArrayEquals(more, read(signIn(owner.username), moved.resolve("n/more.pdf")));
    }

    @Test
    public void movingAPublicFolderTakesItOutOfPublicView() {
        UserContext owner = signIn("mp" + suffix());
        Path home = PathUtil.get(owner.username);
        mkdir(owner, home, "a");
        mkdir(owner, home, "t");
        Path a = home.resolve("a"), t = home.resolve("t");
        byte[] multiChunk = upload(owner, a, "large.bin", 2 * Chunk.LEGACY_SIZE + 1);
        owner.makePublic(owner.getByPath(a).join().get()).join();
        Assert.assertTrue(owner.getPublicFile(a.resolve("large.bin")).join().isPresent());
        ByteArrayWrapper data = dataKey(owner, a.resolve("large.bin"));

        move(owner, a.resolve("large.bin"), t, "large.bin", true);

        UserContext fresh = signIn(owner.username);
        Path moved = t.resolve("large.bin");
        Assert.assertArrayEquals(multiChunk, read(fresh, moved));
        Assert.assertEquals(data, dataKey(fresh, moved));
        Assert.assertTrue(fresh.getPublicFile(a.resolve("large.bin")).join().isEmpty());
        Assert.assertTrue(fresh.getPublicFile(moved).join().isEmpty());
    }
}
