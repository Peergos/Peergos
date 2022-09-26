package peergos.server.tests;

import org.junit.*;
import org.junit.runner.*;
import org.junit.runners.*;
import peergos.server.*;
import peergos.server.util.Args;
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
            .with("default-quota", Long.toString(2 * 1024 * 1024));

    private static int RANDOM_SEED = 666;
    private final NetworkAccess network;
    private final Crypto crypto = Main.initCrypto();

    private static Random random = new Random(RANDOM_SEED);

    public QuotaTests(Args args) throws Exception {
        this.network = Builder.buildJavaNetworkAccess(new URL("http://localhost:" + args.getInt("port")), false).get();
    }

    @Parameterized.Parameters()
    public static Collection<Object[]> parameters() {
        return Arrays.asList(new Object[][]{
                {args}
        });
    }

    @BeforeClass
    public static void init() {
        Main.PKI_INIT.main(args);
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
                network, crypto, x -> {}).get();

        try {
            newHome.uploadOrReplaceFile("file-2", new AsyncReader.ArrayBacked(data), data.length, network,
                    crypto, x -> {}).get();
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
                    network, crypto, x -> {}).get();
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
        int used = context.getSpaceUsage().join().intValue();
        // use within a few KiB of our quota, before deletion
        byte[] data = new byte[2 * 1024 * 1024 - used - 16 * 1024];
        random.nextBytes(data);
        String filename = "file-1";
        home = home.uploadOrReplaceFile(filename, new AsyncReader.ArrayBacked(data), data.length,
                network, crypto, x -> {}).join();
        Path filePath = PathUtil.get(username, filename);
        FileWrapper file = context.getByPath(filePath).join().get();
        file.remove(home, filePath, context).join();
    }

    @Test
    public void deletionAfterExceedingQuota() throws Exception {
        String username = generateUsername();
        String password = "badpassword";

        UserContext context = ensureSignedUp(username, password, network, crypto);
        FileWrapper home = context.getByPath(PathUtil.get(username).toString()).get().get();
        // signing up uses just under 32k and the quota is 2 MiB, so use close to our quota
        int used = context.getSpaceUsage().join().intValue();
        byte[] data = new byte[2 * 1024 * 1024 - used - 16 * 1024];
        random.nextBytes(data);
        String filename = "file-1";
        home = home.uploadOrReplaceFile(filename, new AsyncReader.ArrayBacked(data), data.length,
                network, crypto, x -> {}).get();
        Path filePath = PathUtil.get(username, filename);
        FileWrapper file = context.getByPath(filePath).get().get();
        try {
            home = home.uploadOrReplaceFile("file-2", new AsyncReader.ArrayBacked(data), data.length,
                    network, crypto, x -> {}).get();
            Assert.fail();
        } catch (Exception e) {}
        file.remove(home, filePath, context).get();
    }
}
