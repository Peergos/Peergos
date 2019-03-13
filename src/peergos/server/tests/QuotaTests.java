package peergos.server.tests;

import org.junit.*;
import org.junit.runner.*;
import org.junit.runners.*;
import peergos.server.*;
import peergos.server.util.Args;
import peergos.shared.*;
import peergos.shared.user.*;
import peergos.shared.user.fs.*;

import java.net.*;
import java.nio.file.*;
import java.util.*;

import static peergos.server.util.PeergosNetworkUtils.ensureSignedUp;

@RunWith(Parameterized.class)
public class QuotaTests {

    private static Args args = UserTests.buildArgs()
            .with("useIPFS", "false")
            .with("default-quota", Long.toString(2 * 1024 * 1024));

    private static int RANDOM_SEED = 666;
    private final NetworkAccess network;
    private final Crypto crypto = Crypto.initJava();

    private static Random random = new Random(RANDOM_SEED);

    public QuotaTests(Args args) throws Exception {
        this.network = NetworkAccess.buildJava(new URL("http://localhost:" + args.getInt("port"))).get();
    }

    @Parameterized.Parameters()
    public static Collection<Object[]> parameters() {
        return Arrays.asList(new Object[][]{
                {args}
        });
    }

    @BeforeClass
    public static void init() {
        Main.PKI.main(args);
    }

    private String generateUsername() {
        return "test" + Math.abs(random.nextInt() % 10000);
    }

    @Test
    public void quota() throws Exception {
        String username = generateUsername();
        String password = "badpassword";

        UserContext context = ensureSignedUp(username, password, network, crypto);
        FileWrapper home = context.getByPath(Paths.get(username).toString()).get().get();
        byte[] data = new byte[1024*1024];
        random.nextBytes(data);
        FileWrapper newHome = home.uploadOrOverwriteFile("file-1", new AsyncReader.ArrayBacked(data), data.length,
                network, crypto.random, crypto.hasher, x -> {}, context.fragmenter(),
                home.generateChildLocationsFromSize(data.length, crypto.random)).get();

        try {
            newHome.uploadOrOverwriteFile("file-2", new AsyncReader.ArrayBacked(data), data.length, network,
                    crypto.random, crypto.hasher, x -> {}, context.fragmenter(),
                    newHome.generateChildLocationsFromSize(data.length, crypto.random)).get();
            Assert.fail("Quota wasn't enforced");
        } catch (Exception e) {}
    }

    @Test
    public void deletionsReduceUsage() throws Exception {
        String username = generateUsername();
        String password = "badpassword";

        UserContext context = ensureSignedUp(username, password, network, crypto);
        FileWrapper home = context.getByPath(Paths.get(username).toString()).get().get();
        byte[] data = new byte[1024 * 1024];
        random.nextBytes(data);
        for (int i=0; i < 5; i++) {
            String filename = "file-1";
            home = home.uploadOrOverwriteFile(filename, new AsyncReader.ArrayBacked(data), data.length,
                    network, crypto.random, crypto.hasher, x -> {}, context.fragmenter(),
                    home.generateChildLocationsFromSize(data.length, crypto.random)).get();
            FileWrapper file = context.getByPath("/" + username + "/" + filename).get().get();
            home = file.remove(home, network, crypto.hasher).get();
        }
    }

    @Test
    public void deletionAtQuota() throws Exception {
        String username = generateUsername();
        String password = "badpassword";

        UserContext context = ensureSignedUp(username, password, network, crypto);
        FileWrapper home = context.getByPath(Paths.get(username).toString()).get().get();
        int used = context.getTotalSpaceUsed(context.signer.publicKeyHash, context.signer.publicKeyHash).get().intValue();
        // use within a few KiB of our quota, before deletion
        byte[] data = new byte[2 * 1024 * 1024 - used - 4 * 1024];
        random.nextBytes(data);
        String filename = "file-1";
        home = home.uploadOrOverwriteFile(filename, new AsyncReader.ArrayBacked(data), data.length,
                network, crypto.random, crypto.hasher, x -> {}, context.fragmenter(),
                home.generateChildLocationsFromSize(data.length, crypto.random)).get();
        FileWrapper file = context.getByPath("/" + username + "/" + filename).get().get();
        file.remove(home, network, crypto.hasher).get();
    }

    @Test
    public void deletionAfterExceedingQuota() throws Exception {
        String username = generateUsername();
        String password = "badpassword";

        UserContext context = ensureSignedUp(username, password, network, crypto);
        FileWrapper home = context.getByPath(Paths.get(username).toString()).get().get();
        // signing up uses just over 14k and the quota is 2 MiB, so use within 1 KiB of our quota
        byte[] data = new byte[2 * 1024 * 1024 - 19 * 1024];
        random.nextBytes(data);
        String filename = "file-1";
        home = home.uploadOrOverwriteFile(filename, new AsyncReader.ArrayBacked(data), data.length,
                network, crypto.random, crypto.hasher, x -> {}, context.fragmenter(),
                home.generateChildLocationsFromSize(data.length, crypto.random)).get();
        FileWrapper file = context.getByPath("/" + username + "/" + filename).get().get();
        try {
            home = home.uploadOrOverwriteFile("file-2", new AsyncReader.ArrayBacked(data), data.length,
                    network, crypto.random, crypto.hasher, x -> {}, context.fragmenter(),
                    home.generateChildLocationsFromSize(data.length, crypto.random)).get();
            Assert.fail();
        } catch (Exception e) {}
        file.remove(home, network, crypto.hasher).get();
    }
}
