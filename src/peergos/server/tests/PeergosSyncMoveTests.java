package peergos.server.tests;

import org.junit.*;
import peergos.server.*;
import peergos.server.sync.*;
import peergos.server.util.*;
import peergos.shared.*;
import peergos.shared.storage.*;
import peergos.shared.user.*;
import peergos.shared.user.fs.*;
import peergos.shared.util.*;

import java.nio.file.*;
import java.util.*;

/** Moving a file to another folder kept its old name in Peergos, so a local move that also renamed left the remote
 *  copy misnamed, and a second file with the same old name moved into the same folder failed with a name clash.
 */
public class PeergosSyncMoveTests {
    private static final Args args = UserTests.useMemoryDbs(UserTests.buildArgs()).with("enable-gc", "false");
    private static UserService service;
    private static final Crypto crypto = Main.initCrypto();
    private final Random random = new Random();

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

    private static void sync(LocalFileSystem local, PeergosSyncFS remote, SyncState state) throws Exception {
        DirectorySync.syncDir(local, remote, true, true, null, null, state, 32, 5,
                Files.createTempDirectory("peergos-sync"), crypto, () -> false, DirectorySync::log);
    }

    private void write(Path base, String relPath, byte[] data) throws Exception {
        Path p = base.resolve(relPath);
        Files.createDirectories(p.getParent());
        Files.write(p, data);
    }

    private byte[] randomBytes() {
        byte[] data = new byte[1024];
        random.nextBytes(data);
        return data;
    }

    private static void assertRemote(UserContext context, Path remoteRoot, String relPath, byte[] expected) {
        Optional<FileWrapper> f = context.getByPath(remoteRoot.resolve(relPath)).join();
        Assert.assertTrue(relPath, f.isPresent());
        Assert.assertEquals(relPath, expected.length, f.get().getSize());
    }

    private static void assertNoRemote(UserContext context, Path remoteRoot, String relPath) {
        Assert.assertTrue(relPath, context.getByPath(remoteRoot.resolve(relPath)).join().isEmpty());
    }

    @Test
    public void moveWithRename() throws Exception {
        UserContext context = signIn("sm" + Math.abs(random.nextInt() % 1_000_000));
        context.getUserRoot().join().mkdir("sync", context.network, false, context.mirrorBatId(), crypto).join();
        Path remoteRoot = PathUtil.get(context.username, "sync");
        LinkProperties link = context.createSecretLink(remoteRoot.toString(), true, Optional.empty(), Optional.<Integer>empty(), "", false).join();
        PeergosSyncFS remote = DirectorySync.buildRemote(link.toLinkString(context.signer.publicKeyHash), context.network, crypto);
        Path base = Files.createTempDirectory("peergos-sync");
        LocalFileSystem local = new LocalFileSystem(base, crypto.hasher);
        SyncState state = new JdbcTreeState(":memory:");

        // two copies of a file, moved into a folder that already has a different file with their old name
        byte[] dupe = randomBytes();
        write(base, "a/doc.pdf", dupe);
        write(base, "b/doc.pdf", dupe);
        byte[] existing = new byte[]{1};
        write(base, "t/doc.pdf", existing);
        // the new name is taken by another file in the old folder
        byte[] second = randomBytes(), secondNeighbour = new byte[]{2, 2};
        write(base, "c/x.pdf", second);
        write(base, "c/y.pdf", secondNeighbour);
        // the new name is taken in the old folder and the old name in the new folder
        byte[] third = randomBytes(), thirdNeighbour = new byte[]{3, 3, 3}, thirdTarget = new byte[]{4, 4, 4, 4};
        write(base, "d/p.pdf", third);
        write(base, "d/q.pdf", thirdNeighbour);
        write(base, "e/p.pdf", thirdTarget);
        sync(local, remote, state);

        Files.move(base.resolve("a/doc.pdf"), base.resolve("t/doc 1.pdf"));
        Files.move(base.resolve("b/doc.pdf"), base.resolve("t/doc 2.pdf"));
        Files.move(base.resolve("c/x.pdf"), base.resolve("t/y.pdf"));
        Files.move(base.resolve("d/p.pdf"), base.resolve("e/q.pdf"));
        sync(local, remote, state);

        assertRemote(context, remoteRoot, "t/doc 1.pdf", dupe);
        assertRemote(context, remoteRoot, "t/doc 2.pdf", dupe);
        assertRemote(context, remoteRoot, "t/doc.pdf", existing);
        assertNoRemote(context, remoteRoot, "a/doc.pdf");
        assertNoRemote(context, remoteRoot, "b/doc.pdf");

        assertRemote(context, remoteRoot, "t/y.pdf", second);
        assertRemote(context, remoteRoot, "c/y.pdf", secondNeighbour);
        assertNoRemote(context, remoteRoot, "c/x.pdf");
        assertNoRemote(context, remoteRoot, "t/x.pdf");

        assertRemote(context, remoteRoot, "e/q.pdf", third);
        assertRemote(context, remoteRoot, "e/p.pdf", thirdTarget);
        assertRemote(context, remoteRoot, "d/q.pdf", thirdNeighbour);
        assertNoRemote(context, remoteRoot, "d/p.pdf");
        Assert.assertEquals(Set.of("p.pdf", "q.pdf"), context.getByPath(remoteRoot.resolve("e")).join().get()
                .getChildren(crypto.hasher, context.network).join().stream()
                .map(FileWrapper::getName).collect(java.util.stream.Collectors.toSet()));
    }

    private Path upload(UserContext user, Path dir, String name, byte[] data) {
        user.getByPath(dir).join().get()
                .uploadOrReplaceFile(name, AsyncReader.build(data), data.length, user.network, crypto, () -> false, x -> {}).join();
        return dir.resolve(name);
    }

    private void moveAndRename(UserContext user, Path file, Path targetDir, String newName) {
        UserContext fresh = signIn(user.username);
        fresh.getByPath(file).join().get().moveTo(fresh.getByPath(targetDir).join().get(), fresh.getByPath(file.getParent()).join().get(),
                file, newName, fresh, () -> Futures.of(true)).join();
    }

    @Test
    public void moveWithRenameIntoAnotherWriter() {
        String suffix = "" + Math.abs(random.nextInt() % 1_000_000);
        UserContext owner = signIn("mo" + suffix);
        UserContext friend = signIn("mf" + suffix);
        PeergosNetworkUtils.friendBetweenGroups(List.of(owner), List.of(friend));
        for (String dir : List.of("a", "w"))
            owner.getUserRoot().join().mkdir(dir, owner.network, false, owner.mirrorBatId(), crypto).join();
        Path a = PathUtil.get(owner.username, "a"), w = PathUtil.get(owner.username, "w");
        // write access gives the folder a writer of its own, so the move has to copy
        owner.shareWriteAccessWith(w, Set.of(friend.username)).join();
        byte[] data = randomBytes();
        Path file = upload(owner, a, "doc.pdf", data);
        upload(owner, w, "doc.pdf", new byte[]{1});

        moveAndRename(owner, file, w, "doc 1.pdf");

        UserContext fresh = signIn(owner.username);
        FileWrapper moved = fresh.getByPath(w.resolve("doc 1.pdf")).join().get();
        Assert.assertEquals("doc 1.pdf", moved.getName());
        Assert.assertEquals(data.length, moved.getSize());
        Assert.assertEquals(1, fresh.getByPath(w.resolve("doc.pdf")).join().get().getSize());
        Assert.assertTrue(fresh.getByPath(file).join().isEmpty());
    }

    @Test
    public void moveWithRenameKeepsShares() {
        String suffix = "" + Math.abs(random.nextInt() % 1_000_000);
        UserContext owner = signIn("ko" + suffix);
        UserContext friend = signIn("kf" + suffix);
        PeergosNetworkUtils.friendBetweenGroups(List.of(owner), List.of(friend));
        for (String dir : List.of("a", "t"))
            owner.getUserRoot().join().mkdir(dir, owner.network, false, owner.mirrorBatId(), crypto).join();
        Path a = PathUtil.get(owner.username, "a"), t = PathUtil.get(owner.username, "t");
        byte[] data = randomBytes();
        Path file = upload(owner, a, "doc.pdf", data);
        owner.shareReadAccessWith(file, Set.of(friend.username)).join();

        moveAndRename(owner, file, t, "doc 1.pdf");

        UserContext fresh = signIn(owner.username);
        FileWrapper moved = fresh.getByPath(t.resolve("doc 1.pdf")).join().get();
        Assert.assertEquals("doc 1.pdf", moved.getName());
        Assert.assertEquals(Set.of(friend.username), fresh.sharedWith(t.resolve("doc 1.pdf")).join().readAccess);
        Assert.assertTrue(fresh.sharedWith(t.resolve("doc.pdf")).join().readAccess.isEmpty());
        Assert.assertTrue(signIn(friend.username).getByPath(t.resolve("doc 1.pdf")).join().isPresent());
    }
}
