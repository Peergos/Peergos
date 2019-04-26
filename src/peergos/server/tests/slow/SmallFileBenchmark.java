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
public class SmallFileBenchmark {

    private static int RANDOM_SEED = 666;
    private final NetworkAccess network;
    private final Crypto crypto = Crypto.initJava();

    private static Random random = new Random(RANDOM_SEED);

    public SmallFileBenchmark(String useIPFS, Random r) throws Exception {
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

    // UPLOAD(0) duration: 1246 mS, best: 1246 mS, worst: 1246 mS, av: 1246 mS
    // to
    // UPLOAD(99) duration: 1772 mS, best: 1226 mS, worst: 2132 mS, av: 1638 mS
    //
    // GetData(99) duration: 816 mS, best: 801 mS, worst: 1136 mS, av: 868 mS
    @Test
    public void smallFiles() throws Exception {
        String username = generateUsername();
        String password = "test01";
        UserContext context = ensureSignedUp(username, password, network, crypto);
        FileWrapper userRoot = context.getUserRoot().get();
        List<String> names = new ArrayList<>();
        IntStream.range(0, 100).forEach(i -> names.add(randomString()));
        byte[] data = new byte[1024];
        random.nextBytes(data);

        long worst = 0, best = Long.MAX_VALUE, start = System.currentTimeMillis();
        for (int i=0; i < names.size(); i++) {
            String filename = names.get(i);
            long t1 = System.currentTimeMillis();
            userRoot = userRoot.uploadOrOverwriteFile(filename, AsyncReader.build(data), data.length, context.network,
                    crypto.random, crypto.hasher, x-> {},
                    userRoot.generateChildLocationsFromSize(data.length, crypto.random)).join();
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
