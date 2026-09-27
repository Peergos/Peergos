package peergos.server.tests;

import org.junit.*;
import peergos.server.*;
import peergos.server.util.*;
import peergos.shared.*;
import peergos.shared.crypto.*;
import peergos.shared.io.ipfs.*;
import peergos.shared.mutable.*;
import peergos.shared.storage.*;
import peergos.shared.user.*;
import peergos.shared.user.fs.*;
import peergos.shared.util.*;

import java.nio.file.*;
import java.util.*;

/** Revoking write access leaves the old key's signature valid, but nothing owns it any more, so the server must not
 *  take writes from it: they would land in space the owner can't reach but pays for.
 */
public class RevokedWriterTests {
    private static Args args = UserTests.useMemoryDbs(UserTests.buildArgs()).with("enable-gc", "false");
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

    private byte[] randomData(int size) {
        byte[] data = new byte[size];
        random.nextBytes(data);
        return data;
    }

    private static byte[] read(UserContext user, Path path) {
        FileWrapper file = user.getByPath(path).join().get();
        return Serialize.readFully(file.getInputStream(user.network, crypto, x -> {}).join(), file.getSize()).join();
    }

    private static void awaitUsageUpdate() {
        Threads.sleep(2_000);
    }

    private static void assertRefused(String what, Runnable write) {
        try {
            write.run();
        } catch (Exception expected) {
            return;
        }
        Assert.fail(what + " was accepted");
    }

    @Test
    public void revokedFileWriterCantWrite() {
        String suffix = "" + Math.abs(random.nextInt() % 1_000_000);
        UserContext owner = signIn("ro" + suffix);
        UserContext sharee = signIn("rs" + suffix);
        PeergosNetworkUtils.friendBetweenGroups(List.of(owner), List.of(sharee));
        owner.getUserRoot().join().mkdir("dir", owner.network, false, owner.mirrorBatId(), crypto).join();
        Path dir = PathUtil.get(owner.username, "dir");
        byte[] original = randomData(5000);
        owner.getByPath(dir).join().get()
                .uploadOrReplaceFile("file", AsyncReader.build(original), original.length, owner.network, crypto, () -> false, x -> {})
                .join();
        Path file = dir.resolve("file");
        owner.shareWriteAccessWith(file, Set.of(sharee.username)).join();

        // what the sharee holds before losing access
        UserContext kept = signIn(sharee.username);
        FileWrapper keptFolder = kept.getByPath(dir).join().get();
        FileWrapper keptFile = kept.getByPath(file).join().get();
        owner.unShareWriteAccessWith(file, Set.of(sharee.username)).join();
        awaitUsageUpdate();

        byte[] replacement = randomData(3000);
        assertRefused("A write with a revoked key", () -> keptFolder.uploadOrReplaceFile("file",
                AsyncReader.build(replacement), replacement.length, kept.network, crypto, () -> false, x -> {}).join());

        // moving the pointer needs no new blocks, so is checked on its own
        SigningPrivateKeyAndPublicHash oldKey = keptFile.signingPair();
        PointerUpdate current = service.mutable.getPointerTarget(keptFile.owner(), oldKey.publicKeyHash, service.storage).join();
        Assert.assertTrue("the old key still has a tree", current.updated.isPresent());
        PointerUpdate move = new PointerUpdate(current.updated, MaybeMultihash.empty(), current.sequence.map(s -> s + 1));
        boolean moved;
        try {
            moved = service.mutable.setPointer(keptFile.owner(), oldKey, move).join();
        } catch (Exception refused) {
            moved = false;
        }
        Assert.assertFalse("A pointer update with a revoked key was accepted", moved);
        Assert.assertEquals(current.updated,
                service.mutable.getPointerTarget(keptFile.owner(), oldKey.publicKeyHash, service.storage).join().updated);

        Assert.assertArrayEquals(original, read(signIn(owner.username), file));
    }

    @Test
    public void revokedFolderWriterCantWrite() {
        String suffix = "" + Math.abs(random.nextInt() % 1_000_000);
        UserContext owner = signIn("ro" + suffix);
        UserContext sharee = signIn("rs" + suffix);
        PeergosNetworkUtils.friendBetweenGroups(List.of(owner), List.of(sharee));
        owner.getUserRoot().join().mkdir("dir", owner.network, false, owner.mirrorBatId(), crypto).join();
        Path dir = PathUtil.get(owner.username, "dir");
        owner.shareWriteAccessWith(dir, Set.of(sharee.username)).join();

        UserContext kept = signIn(sharee.username);
        FileWrapper keptFolder = kept.getByPath(dir).join().get();
        owner.unShareWriteAccessWith(dir, Set.of(sharee.username)).join();
        awaitUsageUpdate();

        byte[] data = randomData(3000);
        assertRefused("A write with a revoked key", () -> keptFolder.uploadOrReplaceFile("added",
                AsyncReader.build(data), data.length, kept.network, crypto, () -> false, x -> {}).join());
        Assert.assertTrue(signIn(owner.username).getByPath(dir.resolve("added")).join().isEmpty());
    }
}
