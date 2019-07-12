package peergos.server.tests.slow;

import org.junit.*;
import org.junit.runner.*;
import org.junit.runners.*;
import peergos.server.*;
import peergos.server.tests.*;
import peergos.server.util.*;
import peergos.shared.*;
import peergos.shared.user.*;
import peergos.shared.user.fs.*;
import peergos.shared.util.*;

import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

@RunWith(Parameterized.class)
public class MediumFileBenchmark {

    private static int RANDOM_SEED = 666;
    private final NetworkAccess network;
    private final Crypto crypto = Crypto.initJava();

    private static Random random = new Random(RANDOM_SEED);

    public MediumFileBenchmark(String useIPFS, Random r) throws Exception {
        this.network = buildHttpNetworkAccess(useIPFS.equals("IPFS"), r);
    }

    private static NetworkAccess buildHttpNetworkAccess(boolean useIpfs, Random r) throws Exception {
        Args args = UserTests.buildArgs().with("useIPFS", "" + useIpfs);
        Main.PKI_INIT.main(args);
        return NetworkAccess.buildJava(new URL("http://localhost:" + args.getInt("port"))).get();
    }

    @Parameterized.Parameters()
    public static Collection<Object[]> parameters() {
        return Arrays.asList(new Object[][] {
//                {"IPFS", new Random(0)}
                {"NOTIPFS", new Random(0)}
        });
    }

    private String generateUsername() {
        return "test" + (random.nextInt() % 10000);
    }

    public static UserContext ensureSignedUp(String username, String password, NetworkAccess network, Crypto crypto) throws Exception {
        return UserContext.ensureSignedUp(username, password, network, crypto).get();
    }

    // UPLOAD(0) duration: 8115 mS, best: 8115 mS, worst: 8115 mS, av: 8115 mS
    // to
    // UPLOAD(99) duration: 9864 mS, best: 7959 mS, worst: 10653 mS, av: 8974 mS or 1.1 MiB/s
    //
    // GetData(10) duration: 1037 mS, best: 868 mS, worst: 1167 mS, av: 944 mS or 10.5 MiB/s
    @Test
    public void mediumFiles() throws Exception {
        String username = generateUsername();
        String password = "test01";
        UserContext context = ensureSignedUp(username, password, network, crypto);
        FileWrapper userRoot = context.getUserRoot().get();
        List<String> names = new ArrayList<>();
        IntStream.range(0, 100).forEach(i -> names.add(randomString()));
        byte[] data = new byte[10*1024*1024];
        random.nextBytes(data);

        long worst = 0, best = Long.MAX_VALUE, start = System.currentTimeMillis();
        for (int i=0; i < names.size(); i++) {
            String filename = names.get(i);
            long t1 = System.currentTimeMillis();
            userRoot = userRoot.uploadOrOverwriteFile(filename, AsyncReader.build(data), data.length, context.network,
                    crypto, x-> {}, context.crypto.random.randomBytes(32)).join();
            long duration = System.currentTimeMillis() - t1;
            worst = Math.max(worst, duration);
            best = Math.min(best, duration);
            System.err.printf("UPLOAD(%d) duration: %d mS, best: %d mS, worst: %d mS, av: %d mS\n", i,
                    duration, best, worst, (t1 + duration - start) / (i + 1));
        }

        long worstRead = 0, bestRead = Long.MAX_VALUE, startRead = System.currentTimeMillis();
        for (int i=0; i < 100; i++) {
            long t1 = System.currentTimeMillis();
            FileWrapper file = context.getByPath(Paths.get(username, names.get(random.nextInt(names.size()))))
                    .join().get();
            AsyncReader reader = file.getInputStream(network, crypto.random, x -> {}).join();
            byte[] readData = Serialize.readFully(reader, data.length).join();
            long duration = System.currentTimeMillis() - t1;
            Assert.assertTrue(Arrays.equals(readData, data));
            worstRead = Math.max(worstRead, duration);
            bestRead = Math.min(bestRead, duration);
            System.err.printf("GetData(%d) duration: %d mS, best: %d mS, worst: %d mS, av: %d mS\n", i,
                    duration, bestRead, worstRead, (t1 + duration - startRead) / (i + 1));
        }
    }

    private static String randomString() {
        return UUID.randomUUID().toString();
    }
}
