package peergos.server.tests.slow;

import org.junit.*;
import org.junit.runner.*;
import org.junit.runners.*;
import peergos.server.*;
import peergos.server.tests.*;
import peergos.server.util.*;
import peergos.shared.*;
import peergos.shared.user.*;

import java.net.*;
import java.util.*;
import java.util.stream.*;

@RunWith(Parameterized.class)
public class SocialBenchmark {

    private static int RANDOM_SEED = 666;
    private final NetworkAccess network;
    private final Crypto crypto = Crypto.initJava();

    private static Random random = new Random(RANDOM_SEED);

    public SocialBenchmark(String useIPFS, Random r) throws Exception {
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

    // SendFollowRequest(10) duration: 2967 mS, best: 2296 mS, worst: 2967 mS, av: 2572 mS
    //    pointers.get: 19 * 45 mS = 855 mS
    //    pointers.set: 4 * 80 mS = 320 mS
    @Test
    public void social() {
        String username = generateUsername();
        String password = "test01";
        UserContext context = ensureSignedUp(username, password, network, crypto);
        List<String> names = new ArrayList<>();
        IntStream.range(0, 20).forEach(i -> names.add(generateUsername()));
        names.forEach(name -> ensureSignedUp(name, password, network, crypto));

        long worst = 0, best = Long.MAX_VALUE, start = System.currentTimeMillis();

        for (int i = 0; i < 20; i++) {
            long t1 = System.currentTimeMillis();
            context.sendInitialFollowRequest(names.get(i)).join();
            long duration = System.currentTimeMillis() - t1;
            worst = Math.max(worst, duration);
            best = Math.min(best, duration);
            System.err.printf("SendFollowRequest(%d) duration: %d mS, best: %d mS, worst: %d mS, av: %d mS\n", i,
                    duration, best, worst, (t1 + duration - start) / (i + 1));
        }
    }

    private String generateUsername() {
        return "test" + (random.nextInt() % 10000);
    }

    public static UserContext ensureSignedUp(String username, String password, NetworkAccess network, Crypto crypto) {
        return UserContext.ensureSignedUp(username, password, network, crypto).join();
    }
}
