package peergos.server.tests;

import org.junit.*;
import org.junit.runner.*;
import org.junit.runners.*;
import peergos.server.*;
import peergos.server.util.Args;
import peergos.server.util.Threads;
import peergos.shared.*;
import peergos.shared.storage.auth.*;
import peergos.shared.user.*;
import peergos.shared.user.fs.*;
import peergos.shared.util.*;

import java.net.*;
import java.nio.file.*;
import java.util.*;

import static peergos.server.tests.PeergosNetworkUtils.ensureSignedUp;

@RunWith(Parameterized.class)
public class QuotaTests {

    private static Args args = UserTests.buildArgs()
            .with("useIPFS", "false")
            .with("quota-upload-limit-seconds", "1")
            .with("default-quota", Long.toString(2 * 1024 * 1024));

    private static int RANDOM_SEED = 666;
    private final NetworkAccess network;
    private final Crypto crypto = Main.initCrypto();
    private static ServerProcesses server;

    private static Random random = new Random(RANDOM_SEED);

    public QuotaTests(Args args) throws Exception {
        this.network = Builder.buildJavaNetworkAccess(new URL("http://localhost:" + args.getInt("port")), false, Optional.empty(), Optional.empty()).get();
    }

    @Parameterized.Parameters()
    public static Collection<Object[]> parameters() {
        return Arrays.asList(new Object[][]{
                {args}
        });
    }

    @BeforeClass
    public static void init() {
        server = Main.PKI_INIT.main(args);
    }

    private String generateUsername() {
        return "test" + Math.abs(random.nextInt() % 10000);
    }

    @Test
    public void quota() throws Exception {
        String username = generateUsername();
        String password = "badpassword";

        UserContext context = ensureSignedUp(username, password, network, crypto);
        FileWrapper home = context.getByPath(PathUtil.get(username).toString()).get().get();
        byte[] data = new byte[1024*1024];
        random.nextBytes(data);
        FileWrapper newHome = home.uploadOrReplaceFile("file-1", new AsyncReader.ArrayBacked(data), data.length,
                network, crypto, () -> false, x -> {}).get();

        try {
            byte[] bigger = new byte[3 * 1024 * 1024];
            newHome.uploadOrReplaceFile("file-2", new AsyncReader.ArrayBacked(bigger), bigger.length, network,
                    crypto, () -> false, x -> {}).get();
            Assert.fail("Quota wasn't enforced");
        } catch (Exception e) {}
    }

    @Test
    public void deletionsReduceUsage() throws Exception {
        String username = generateUsername();
        String password = "badpassword";

        UserContext context = ensureSignedUp(username, password, network, crypto);
        byte[] data = new byte[1024 * 1024];
        random.nextBytes(data);
        for (int i=0; i < 5; i++) {
            String filename = "file-1";
            context.getUserRoot().join().uploadOrReplaceFile(filename, new AsyncReader.ArrayBacked(data), data.length,
                    network, crypto, () -> false, x -> {}).get();
            Path filePath = PathUtil.get(username, filename);
            FileWrapper file = context.getByPath(filePath).get().get();
            file.remove(context.getUserRoot().join(), filePath, context).get();
            Thread.sleep(2_000);
        }
    }

    @Test
    public void deletionAtQuota() throws Exception {
        String username = generateUsername();
        String password = "badpassword";

        UserContext context = ensureSignedUp(username, password, network, crypto);
        FileWrapper home = context.getByPath(PathUtil.get(username).toString()).get().get();
        int used = context.getSpaceUsage(false).join().intValue();
        // use within a few KiB of our quota, before deletion
        byte[] data = new byte[2 * 1024 * 1024 - used - 16 * 1024];
        random.nextBytes(data);
        String filename = "file-1";
        home = home.uploadOrReplaceFile(filename, new AsyncReader.ArrayBacked(data), data.length,
                network, crypto, () -> false, x -> {}).join();
        Path filePath = PathUtil.get(username, filename);
        FileWrapper file = context.getByPath(filePath).join().get();
        Thread.sleep(2_000);
        file.remove(home, filePath, context).join();
    }

    /** Committing a delete is itself a write, so charging it only the bytes it adds would leave a user
     *  who is over quota unable to get back under. A commit carries its blocks and its pointer updates
     *  together, so the server can see that this one frees more than it writes and let it through.
     */
    @Test
    public void deleteWhenOverQuota() throws Exception {
        String username = generateUsername();
        String password = "badpassword";

        UserContext context = ensureSignedUp(username, password, network, crypto);
        FileWrapper home = context.getByPath(PathUtil.get(username).toString()).join().get();
        long quota = 2 * 1024 * 1024;
        int used = context.getSpaceUsage(false).join().intValue();
        byte[] data = new byte[(int) quota - used - 16 * 1024];
        random.nextBytes(data);
        home = home.uploadOrReplaceFile("file-1", new AsyncReader.ArrayBacked(data), data.length,
                network, crypto, () -> false, x -> {}).join();
        Path filePath = PathUtil.get(username, "file-1");
        Threads.sleep(2_000);

        // A write that doesn't fit is refused outright, so the way past the quota is the tolerance:
        // once a user has been refused once, writes are allowed until they are 1 MiB over. How much
        // of the refused upload lands isn't fixed, so keep adding small files until they are over.
        try {
            home.uploadOrReplaceFile("file-2", new AsyncReader.ArrayBacked(data), data.length,
                    network, crypto, () -> false, x -> {}).join();
        } catch (Exception overQuota) {}
        Threads.sleep(2_000);
        byte[] small = new byte[256 * 1024];
        random.nextBytes(small);
        long stored = context.getSpaceUsage(false).join();
        for (int i = 0; stored <= quota && i < 3; i++) {
            try {
                context.getUserRoot().join().uploadOrReplaceFile("filler-" + i, new AsyncReader.ArrayBacked(small),
                        small.length, network, crypto, () -> false, x -> {}).join();
            } catch (Exception overQuota) {}
            Threads.sleep(2_000);
            stored = context.getSpaceUsage(false).join();
        }
        Assert.assertTrue("they are over quota: " + stored + " of " + quota, stored > quota);

        // the delete frees far more than it writes, so it is allowed through
        FileWrapper file = context.getByPath(filePath).join().get();
        file.remove(context.getUserRoot().join(), filePath, context).join();
        Assert.assertTrue("the file is gone", context.getByPath(filePath).join().isEmpty());
    }

    @Ignore // Can always just increae their quota for now
    @Test
    public void deletionAfterExceedingQuota() throws Exception {
        String username = generateUsername();
        String password = "badpassword";

        UserContext context = ensureSignedUp(username, password, network, crypto);
        FileWrapper home = context.getByPath(PathUtil.get(username).toString()).get().get();
        // signing up uses just under 32k and the quota is 2 MiB, so use close to our quota
        int used = context.getSpaceUsage(false).join().intValue();
        byte[] data = new byte[2 * 1024 * 1024 - used - 16 * 1024];
        random.nextBytes(data);
        String filename = "file-1";
        home = home.uploadOrReplaceFile(filename, new AsyncReader.ArrayBacked(data), data.length,
                network, crypto, () -> false, x -> {}).get();
        Path filePath = PathUtil.get(username, filename);
        FileWrapper file = context.getByPath(filePath).get().get();
        Threads.sleep(2_000);
        try {
            home = home.uploadOrReplaceFile("file-2", new AsyncReader.ArrayBacked(data), data.length,
                    network, crypto, () -> false, x -> {}).get();
            Assert.fail();
        } catch (Exception e) {}
        if (server.localApi.gc != null) {
            server.localApi.gc.collect(x -> Futures.of(true));
            server.localApi.gc.collect(x -> Futures.of(true));
        }
        file.remove(home, filePath, context).get();
    }
}
