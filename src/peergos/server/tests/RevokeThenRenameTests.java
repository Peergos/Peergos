package peergos.server.tests;

import org.junit.*;
import peergos.server.*;
import peergos.server.util.*;
import peergos.shared.*;
import peergos.shared.storage.*;
import peergos.shared.user.*;
import peergos.shared.user.fs.*;
import peergos.shared.util.*;

import java.nio.file.*;
import java.util.*;

/** Revoking read access rotates keys without replacing the signer. For a file or folder in a writing space of its own
 *  that dropped the link from its parent's writing space, and pointed it at its parent as if they shared a writer, so a
 *  later rename failed to commit.
 */
public class RevokeThenRenameTests {
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

    private void check(boolean revoke) {
        String suffix = "" + Math.abs(random.nextInt() % 1_000_000);
        UserContext owner = signIn("wo" + suffix);
        UserContext sharee = signIn("ws" + suffix);
        PeergosNetworkUtils.friendBetweenGroups(List.of(owner), List.of(sharee));
        owner.getUserRoot().join().mkdir("dir", owner.network, false, owner.mirrorBatId(), crypto).join();
        Path dir = PathUtil.get(owner.username, "dir");
        byte[] data = new byte[200];
        random.nextBytes(data);
        owner.getByPath(dir).join().get()
                .uploadOrReplaceFile("file", AsyncReader.build(data), data.length, owner.network, crypto, () -> false, x -> {}).join();
        Path file = dir.resolve("file");
        owner.shareWriteAccessWith(file, Set.of(sharee.username)).join();
        if (revoke)
            owner.unShareWriteAccessWith(file, Set.of(sharee.username)).join();

        UserContext fresh = signIn(owner.username);
        FileWrapper parent = fresh.getByPath(dir).join().get();
        fresh.getByPath(file).join().get().rename("renamed", parent, file, fresh).join();
        Assert.assertTrue(signIn(owner.username).getByPath(dir.resolve("renamed")).join().isPresent());
    }

    @Test
    public void renameAfterRevokingWriteThenRead() {
        String suffix = "" + Math.abs(random.nextInt() % 1_000_000);
        UserContext owner = signIn("wo" + suffix);
        UserContext writer = signIn("ww" + suffix);
        UserContext reader = signIn("wr" + suffix);
        PeergosNetworkUtils.friendBetweenGroups(List.of(owner), List.of(writer, reader));
        owner.getUserRoot().join().mkdir("dir", owner.network, false, owner.mirrorBatId(), crypto).join();
        Path dir = PathUtil.get(owner.username, "dir");
        Path file = dir.resolve("file");
        upload(owner, dir, 200);
        owner.shareWriteAccessWith(file, Set.of(writer.username)).join();
        upload(owner, dir, 3000);
        owner.shareReadAccessWith(file, Set.of(reader.username)).join();
        owner.unShareWriteAccessWith(file, Set.of(writer.username)).join();
        owner.unShareReadAccessWith(file, Set.of(reader.username)).join();
        upload(owner, dir, 6000);

        UserContext fresh = signIn(owner.username);
        FileWrapper parent = fresh.getByPath(dir).join().get();
        fresh.getByPath(file).join().get().rename("renamed", parent, file, fresh).join();
        Assert.assertTrue(signIn(owner.username).getByPath(dir.resolve("renamed")).join().isPresent());
    }

    @Test
    public void folderSharedToWriteKeepsWorkingAfterRevokingAReader() {
        String suffix = "" + Math.abs(random.nextInt() % 1_000_000);
        UserContext owner = signIn("wo" + suffix);
        UserContext writer = signIn("ww" + suffix);
        UserContext reader = signIn("wr" + suffix);
        PeergosNetworkUtils.friendBetweenGroups(List.of(owner), List.of(writer, reader));
        owner.getUserRoot().join().mkdir("team", owner.network, false, owner.mirrorBatId(), crypto).join();
        Path team = PathUtil.get(owner.username, "team");
        owner.shareWriteAccessWith(team, Set.of(writer.username)).join();
        owner.shareReadAccessWith(team, Set.of(reader.username)).join();
        upload(writer, team, 3000);
        owner.unShareReadAccessWith(team, Set.of(reader.username)).join();

        // the writer can still add to it, and the owner can still rename it
        upload(writer, team, 5000);
        UserContext fresh = signIn(owner.username);
        FileWrapper root = fresh.getUserRoot().join();
        fresh.getByPath(team).join().get().rename("renamed", root, team, fresh).join();
        Path renamed = PathUtil.get(owner.username, "renamed");
        Assert.assertEquals(5000, signIn(owner.username).getByPath(renamed.resolve("file")).join().get().getSize());
        Assert.assertTrue(signIn(reader.username).getByPath(renamed.resolve("file")).join().isEmpty());
    }

    private void upload(UserContext user, Path dir, int size) {
        byte[] data = new byte[size];
        random.nextBytes(data);
        UserContext fresh = signIn(user.username);
        fresh.getByPath(dir).join().get()
                .uploadOrReplaceFile("file", AsyncReader.build(data), data.length, fresh.network, crypto, () -> false, x -> {}).join();
    }

    @Test
    public void renameSharedFile() {
        check(false);
    }

    @Test
    public void renameFileAfterRevokingItsShare() {
        check(true);
    }
}
