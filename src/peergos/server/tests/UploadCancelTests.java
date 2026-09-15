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
import java.util.concurrent.atomic.*;
import java.util.stream.*;

import static peergos.server.tests.PeergosNetworkUtils.checkFileContents;
import static peergos.server.tests.PeergosNetworkUtils.ensureSignedUp;
import static peergos.server.tests.PeergosNetworkUtils.generateUsername;

public class UploadCancelTests {

    private static final Crypto crypto = Main.initCrypto();
    private static final Args args = UserTests.buildArgs().with("useIPFS", "false");
    private static final long RESIDUE_TOLERANCE = 100_000;
    private static UserService service;
    private static NetworkAccess network;
    private static final Random random = new Random(29);

    @BeforeClass
    public static void init() {
        service = Main.PKI_INIT.main(args).localApi;
        network = NetworkAccess.buildBuffered(service.storage, service.bats, service.coreNode, service.account,
                service.mutable, 0, service.social, service.controller, service.usage, service.serverMessages,
                crypto.hasher, Arrays.asList("peergos"), false);
    }

    @Test
    public void cancelPartWayThroughALargeFile() throws Exception {
        String password = "password";
        UserContext context = ensureSignedUp(generateUsername(random), password, network, crypto);
        String username = context.username;
        long initialUsage = context.getSpaceUsage(false).join();

        byte[] small1 = randomData(1024);
        byte[] small2 = randomData(100 * 1024);
        // past the 20MiB the network buffers before committing, as a browser upload is when it is cancelled
        byte[] large = randomData(24 * Chunk.MAX_SIZE);
        AtomicBoolean cancelled = new AtomicBoolean(false);
        AtomicLong largeProgress = new AtomicLong(0);
        List<FileWrapper.FileUploadProperties> files = Arrays.asList(
                props("small1", small1, x -> {}),
                props("small2", small2, x -> {}),
                props("large", large, n -> {
                    if (largeProgress.addAndGet(n) >= 8L * Chunk.MAX_SIZE)
                        cancelled.set(true);
                }));
        try {
            upload(context, "dir", files, cancelled::get, f -> Futures.of(false));
            Assert.fail("A cancelled upload completed");
        } catch (Exception expected) {}
        Assert.assertTrue(cancelled.get());

        UserContext fresh = UserContext.signIn(username, password, UserTests::noMfa, network, crypto).join();
        Path dir = PathUtil.get(username, "dir");
        checkFileContents(small1, fresh.getByPath(dir.resolve("small1")).join().get(), fresh);
        checkFileContents(small2, fresh.getByPath(dir.resolve("small2")).join().get(), fresh);
        Assert.assertFalse("The file stopped part way was added", fresh.getByPath(dir.resolve("large")).join().isPresent());
        Assert.assertEquals("Upload transactions left open", 0, openTransactions(fresh));
        awaitUsageBelow(fresh, initialUsage + small1.length + small2.length + RESIDUE_TOLERANCE);

        // the same file again is a new upload, with nothing to resume
        AtomicBoolean askedToResume = new AtomicBoolean(false);
        upload(fresh, "dir", Arrays.asList(props("large", large, x -> {})), () -> false, t -> {
            askedToResume.set(true);
            return Futures.of(false);
        });
        Assert.assertFalse(askedToResume.get());
        checkFileContents(large, fresh.getByPath(dir.resolve("large")).join().get(), fresh);
    }

    @Test
    public void cancelBetweenSmallFiles() throws Exception {
        String password = "password";
        UserContext context = ensureSignedUp(generateUsername(random), password, network, crypto);
        String username = context.username;

        int count = 30;
        List<byte[]> contents = IntStream.range(0, count)
                .mapToObj(i -> randomData(1024 * 1024))
                .collect(Collectors.toList());
        AtomicBoolean cancelled = new AtomicBoolean(false);
        AtomicLong progress = new AtomicLong(0);
        List<FileWrapper.FileUploadProperties> files = IntStream.range(0, count)
                .mapToObj(i -> props("file" + i, contents.get(i), n -> {
                    if (progress.addAndGet(n) >= 20L * 1024 * 1024)
                        cancelled.set(true);
                }))
                .collect(Collectors.toList());
        try {
            upload(context, "many", files, cancelled::get, f -> Futures.of(false));
            Assert.fail("A cancelled upload completed");
        } catch (Exception expected) {}
        Assert.assertTrue(cancelled.get());

        UserContext fresh = UserContext.signIn(username, password, UserTests::noMfa, network, crypto).join();
        Path dir = PathUtil.get(username, "many");
        Set<String> present = fresh.getByPath(dir).join().get().getChildren(crypto.hasher, fresh.network).join()
                .stream()
                .map(f -> f.getName())
                .collect(Collectors.toSet());
        Assert.assertTrue("Nothing was kept from before the cancel", present.size() > 0);
        Assert.assertTrue("Nothing was stopped", present.size() < count);
        for (int i = 0; i < count; i++)
            if (present.contains("file" + i))
                checkFileContents(contents.get(i), fresh.getByPath(dir.resolve("file" + i)).join().get(), fresh);

        // the session that cancelled can still write, and what it writes is committed
        byte[] after = randomData(3 * Chunk.MAX_SIZE);
        upload(context, "many", Arrays.asList(props("after", after, x -> {})), () -> false, f -> Futures.of(false));
        UserContext again = UserContext.signIn(username, password, UserTests::noMfa, network, crypto).join();
        checkFileContents(after, again.getByPath(dir.resolve("after")).join().get(), again);
    }

    private static void upload(UserContext context,
                               String dirName,
                               List<FileWrapper.FileUploadProperties> files,
                               java.util.function.Supplier<Boolean> isCancelled,
                               java.util.function.Function<peergos.shared.user.fs.transaction.FileUploadTransaction, java.util.concurrent.CompletableFuture<Boolean>> resume) {
        FileWrapper.FolderUploadProperties folder = new FileWrapper.FolderUploadProperties(Arrays.asList(dirName), files);
        context.getUserRoot().join().uploadSubtree(Stream.of(folder), context.mirrorBatId(), context.network, crypto,
                context.getTransactionService(), resume, f -> Futures.of(true), () -> true, isCancelled).join();
    }

    private static FileWrapper.FileUploadProperties props(String name, byte[] data, ProgressConsumer<Long> monitor) {
        return new FileWrapper.FileUploadProperties(name, () -> AsyncReader.build(data), 0, data.length,
                Optional.empty(), Optional.empty(), false, false, monitor);
    }

    private static int openTransactions(UserContext context) {
        return context.getByPath(PathUtil.get(context.username, UserContext.TRANSACTIONS_DIR_NAME)).join().get()
                .getChildren(crypto.hasher, context.network).join().size();
    }

    private static void awaitUsageBelow(UserContext context, long limit) throws InterruptedException {
        long usage = context.getSpaceUsage(false).join();
        for (int i = 0; i < 60 && usage >= limit; i++) {
            Thread.sleep(1_000);
            usage = context.getSpaceUsage(false).join();
        }
        Assert.assertTrue("usage=" + usage + " limit=" + limit, usage < limit);
    }

    private static byte[] randomData(int length) {
        byte[] data = new byte[length];
        random.nextBytes(data);
        return data;
    }
}
