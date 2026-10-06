package peergos.server.tests;

import org.junit.*;
import peergos.server.*;
import peergos.server.util.*;
import peergos.shared.*;
import peergos.shared.user.*;
import peergos.shared.user.fs.*;
import peergos.shared.util.*;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

/** The windows mount looks a folder up, then queues behind other writes to it before deleting children. If the
 *  folder changed in between, the delete must not leave the folder linking to children whose data it removed.
 */
public class DeleteChildrenAtomicityTests {
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

    private static void upload(UserContext user, Path dir, String name, int size, Random r) {
        byte[] data = new byte[size];
        r.nextBytes(data);
        user.getByPath(dir).join().get()
                .uploadOrReplaceFile(name, AsyncReader.build(data), data.length, user.network, crypto, () -> false, x -> {})
                .join();
    }

    @Test
    public void deleteWithStaleParentLeavesNoDanglingLink() {
        String username = "dc" + Math.abs(random.nextInt() % 1_000_000);
        UserContext user = PeergosNetworkUtils.ensureSignedUp(username, "password", network(), crypto);
        user.getUserRoot().join().mkdir("dir", user.network, false, user.mirrorBatId(), crypto).join();
        Path dir = PathUtil.get(username, "dir");
        upload(user, dir, "keep.txt", 3000, random);
        upload(user, dir, "X.mscz_saving", 3000, random);

        FileWrapper staleParent = user.getByPath(dir).join().get();
        Set<FileWrapper> toDelete = staleParent.getChildren(Set.of("X.mscz_saving"), crypto.hasher, user.network, true).join();
        Assert.assertEquals(1, toDelete.size());

        // another write to the folder lands while the delete is queued
        upload(user, dir, "X.mscz", 3000, random);

        boolean deleteFailed = false;
        try {
            FileWrapper.deleteChildren(staleParent, toDelete, dir, user).join();
        } catch (Exception e) {
            deleteFailed = true;
        }

        // the next unrelated write to the same writer flushes whatever the delete left buffered
        upload(user, dir, "later.txt", 3000, random);

        UserContext fresh = PeergosNetworkUtils.ensureSignedUp(username, "password", network(), crypto);
        FileWrapper folder = fresh.getByPath(dir).join().get();
        Set<String> linked = folder.getChildrenCapabilities(crypto.hasher, fresh.network).join().stream()
                .map(c -> c.name.name)
                .collect(Collectors.toSet());
        Set<String> retrievable = folder.getChildren(Set.of("keep.txt", "X.mscz_saving", "X.mscz", "later.txt"),
                        crypto.hasher, fresh.network, true).join().stream()
                .map(FileWrapper::getName)
                .collect(Collectors.toSet());
        Set<String> dangling = new TreeSet<>(linked);
        dangling.removeAll(retrievable);

        Assert.assertTrue("delete failed=" + deleteFailed + ", folder links children whose data is gone: " + dangling
                + " (linked=" + linked + ", retrievable=" + retrievable + ")", dangling.isEmpty());
        Assert.assertTrue(retrievable.containsAll(Set.of("keep.txt", "X.mscz", "later.txt")));
    }

    @Test
    public void deleteIsAllOrNothing() {
        String username = "dn" + Math.abs(random.nextInt() % 1_000_000);
        UserContext user = PeergosNetworkUtils.ensureSignedUp(username, "password", network(), crypto);
        user.getUserRoot().join().mkdir("dir", user.network, false, user.mirrorBatId(), crypto).join();
        Path dir = PathUtil.get(username, "dir");
        upload(user, dir, "X.mscz_saving", 3000, random);

        FileWrapper staleParent = user.getByPath(dir).join().get();
        Set<FileWrapper> toDelete = staleParent.getChildren(Set.of("X.mscz_saving"), crypto.hasher, user.network, true).join();
        upload(user, dir, "X.mscz", 3000, random);
        try {
            FileWrapper.deleteChildren(staleParent, toDelete, dir, user).join();
        } catch (Exception e) {}

        UserContext fresh = PeergosNetworkUtils.ensureSignedUp(username, "password", network(), crypto);
        Set<String> onServer = fresh.getByPath(dir).join().get()
                .getChildren(Set.of("X.mscz_saving", "X.mscz"), crypto.hasher, fresh.network, true).join().stream()
                .map(FileWrapper::getName)
                .collect(Collectors.toSet());
        Assert.assertTrue("the server holds " + onServer,
                onServer.equals(Set.of("X.mscz_saving", "X.mscz")) || onServer.equals(Set.of("X.mscz")));

        Set<String> seenLocally = user.getByPath(dir).join().get()
                .getChildren(Set.of("X.mscz_saving", "X.mscz"), crypto.hasher, user.network, true).join().stream()
                .map(FileWrapper::getName)
                .collect(Collectors.toSet());
        Assert.assertEquals("the session that ran the delete disagrees with the server", onServer, seenLocally);
    }

    @Test
    public void failedUpdateDiscardsItsBufferedWrites() {
        String username = "df" + Math.abs(random.nextInt() % 1_000_000);
        UserContext user = PeergosNetworkUtils.ensureSignedUp(username, "password", network(), crypto);
        Path dir = PathUtil.get(username);
        upload(user, dir, "file", 3000, random);
        FileWrapper file = user.getByPath(dir.resolve("file")).join().get();

        try {
            user.network.synchronizer.applyComplexUpdate(file.owner(), file.signingPair(),
                    (v, c) -> file.truncate(v, c, 1000, user.network, crypto)
                            .thenCompose(s -> Futures.<Snapshot>errored(new RuntimeException("Simulated failure after a buffered commit"))))
                    .join();
            Assert.fail("the update should have failed");
        } catch (CompletionException expected) {}

        Assert.assertEquals("the failed update is visible to its own session",
                3000, user.getByPath(dir.resolve("file")).join().get().getSize());

        upload(user, dir, "later.txt", 3000, random);
        UserContext fresh = PeergosNetworkUtils.ensureSignedUp(username, "password", network(), crypto);
        Assert.assertEquals("a later write committed the failed update",
                3000, fresh.getByPath(dir.resolve("file")).join().get().getSize());
    }
}
