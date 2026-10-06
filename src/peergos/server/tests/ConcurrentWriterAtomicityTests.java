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

/** One session buffers the writes of every update it is running, whichever writer each is for. An update must
 *  only ever commit its own writes, never those of another one still in progress.
 */
public class ConcurrentWriterAtomicityTests {
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
    public void anUpdateFinishingDoesNotCommitAnotherInProgress() throws Exception {
        String username = "cw" + Math.abs(random.nextInt() % 1_000_000);
        UserContext user = PeergosNetworkUtils.ensureSignedUp(username, "password", network(), crypto);
        Path home = PathUtil.get(username);
        Path shared = home.resolve("team");
        user.getUserRoot().join().mkdir("team", user.network, false, user.mirrorBatId(), crypto).join();
        user.shareWriteAccessWith(shared, new HashSet<>()).join();
        upload(user, shared, "file", 3000, random);
        FileWrapper file = user.getByPath(shared.resolve("file")).join().get();
        Assert.assertNotEquals("the file needs its own writer", user.getUserRoot().join().writer(), file.writer());

        CompletableFuture<Boolean> buffered = new CompletableFuture<>();
        CompletableFuture<Boolean> release = new CompletableFuture<>();
        CompletableFuture<Snapshot> inProgress = user.network.synchronizer.applyComplexUpdate(file.owner(), file.signingPair(),
                (v, c) -> file.truncate(v, c, 1000, user.network, crypto)
                        .thenCompose(s -> {
                            buffered.complete(true);
                            return release.thenCompose(x -> Futures.<Snapshot>errored(new RuntimeException("Simulated failure")));
                        }));
        buffered.get(30, TimeUnit.SECONDS);

        // a different writer's update starts and finishes while the first is still running
        upload(user, home, "other.txt", 3000, random);

        release.complete(true);
        try {
            inProgress.join();
            Assert.fail("the update should have failed");
        } catch (CompletionException expected) {}

        UserContext fresh = PeergosNetworkUtils.ensureSignedUp(username, "password", network(), crypto);
        Assert.assertEquals("another update committed the failed one's writes",
                3000, fresh.getByPath(shared.resolve("file")).join().get().getSize());
        Assert.assertTrue(fresh.getByPath(home.resolve("other.txt")).join().isPresent());
    }

    @Test
    public void anUpdateFinishingDoesNotDropAnotherInProgress() throws Exception {
        String username = "cd" + Math.abs(random.nextInt() % 1_000_000);
        UserContext user = PeergosNetworkUtils.ensureSignedUp(username, "password", network(), crypto);
        Path home = PathUtil.get(username);
        Path shared = home.resolve("team");
        user.getUserRoot().join().mkdir("team", user.network, false, user.mirrorBatId(), crypto).join();
        user.shareWriteAccessWith(shared, new HashSet<>()).join();
        upload(user, shared, "file", 3000, random);
        FileWrapper file = user.getByPath(shared.resolve("file")).join().get();

        CompletableFuture<Boolean> buffered = new CompletableFuture<>();
        CompletableFuture<Boolean> release = new CompletableFuture<>();
        CompletableFuture<Snapshot> inProgress = user.network.synchronizer.applyComplexUpdate(file.owner(), file.signingPair(),
                (v, c) -> file.truncate(v, c, 1000, user.network, crypto)
                        .thenCompose(s -> {
                            buffered.complete(true);
                            return release.thenApply(x -> s);
                        }));
        buffered.get(30, TimeUnit.SECONDS);

        upload(user, home, "other.txt", 3000, random);

        release.complete(true);
        inProgress.join();

        UserContext fresh = PeergosNetworkUtils.ensureSignedUp(username, "password", network(), crypto);
        Assert.assertEquals("an update in progress lost its writes to another finishing",
                1000, fresh.getByPath(shared.resolve("file")).join().get().getSize());
        Assert.assertTrue(fresh.getByPath(home.resolve("other.txt")).join().isPresent());
    }

    @Test
    public void anUpdateFailingDoesNotDropAnotherInProgress() throws Exception {
        String username = "cf" + Math.abs(random.nextInt() % 1_000_000);
        UserContext user = PeergosNetworkUtils.ensureSignedUp(username, "password", network(), crypto);
        Path home = PathUtil.get(username);
        Path shared = home.resolve("team");
        user.getUserRoot().join().mkdir("team", user.network, false, user.mirrorBatId(), crypto).join();
        user.shareWriteAccessWith(shared, new HashSet<>()).join();
        upload(user, shared, "file", 3000, random);
        upload(user, home, "mine", 3000, random);
        FileWrapper file = user.getByPath(shared.resolve("file")).join().get();
        FileWrapper mine = user.getByPath(home.resolve("mine")).join().get();

        CompletableFuture<Boolean> buffered = new CompletableFuture<>();
        CompletableFuture<Boolean> release = new CompletableFuture<>();
        CompletableFuture<Snapshot> inProgress = user.network.synchronizer.applyComplexUpdate(file.owner(), file.signingPair(),
                (v, c) -> file.truncate(v, c, 1000, user.network, crypto)
                        .thenCompose(s -> {
                            buffered.complete(true);
                            return release.thenApply(x -> s);
                        }));
        buffered.get(30, TimeUnit.SECONDS);

        try {
            user.network.synchronizer.applyComplexUpdate(mine.owner(), mine.signingPair(),
                    (v, c) -> mine.truncate(v, c, 1000, user.network, crypto)
                            .thenCompose(s -> Futures.<Snapshot>errored(new RuntimeException("Simulated failure")))).join();
            Assert.fail("the update should have failed");
        } catch (CompletionException expected) {}

        release.complete(true);
        inProgress.join();

        UserContext fresh = PeergosNetworkUtils.ensureSignedUp(username, "password", network(), crypto);
        Assert.assertEquals("an update in progress lost its writes to another failing",
                1000, fresh.getByPath(shared.resolve("file")).join().get().getSize());
        Assert.assertEquals(3000, fresh.getByPath(home.resolve("mine")).join().get().getSize());
    }
}
