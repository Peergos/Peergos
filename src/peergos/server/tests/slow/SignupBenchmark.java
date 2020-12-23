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

@RunWith(Parameterized.class)
public class SignupBenchmark {

    private static int RANDOM_SEED = 666;
    private final NetworkAccess network;
    private final Crypto crypto = Main.initCrypto();

    private static Random random = new Random(RANDOM_SEED);

    public SignupBenchmark(String useIPFS, Random r) throws Exception {
        this.network = buildHttpNetworkAccess(useIPFS.equals("IPFS"), r);
    }

    private static NetworkAccess buildHttpNetworkAccess(boolean useIpfs, Random r) throws Exception {
        Args args = UserTests.buildArgs().with("useIPFS", "" + useIpfs);
        Main.PKI_INIT.main(args);
        return Builder.buildJavaNetworkAccess(new URL("http://localhost:" + args.getInt("port")), false).get();
    }

    @Parameterized.Parameters()
    public static Collection<Object[]> parameters() {
        return Arrays.asList(new Object[][] {
//                {"IPFS", new Random(0)}
                {"NOTIPFS", new Random(0)}
        });
    }

    // SIGNUP(10) duration: 6800 mS, best: 6748 mS, worst: 7109 mS, av: 6839 mS
    @Test
    public void signup() throws Exception {
        long worst = 0, best = Long.MAX_VALUE, start = System.currentTimeMillis();

        for (int i=0; i < 20; i++) {
            long t1 = System.currentTimeMillis();
            String username = generateUsername();
            String password = "test01";
            ensureSignedUp(username, password, network, crypto);
            long duration = System.currentTimeMillis() - t1;
            worst = Math.max(worst, duration);
            best = Math.min(best, duration);
            System.err.printf("SIGNUP(%d) duration: %d mS, best: %d mS, worst: %d mS, av: %d mS\n", i,
                    duration, best, worst, (t1 + duration - start) / (i + 1));
        }
    }

    private String generateUsername() {
        return "test" + (random.nextInt() % 10000);
    }

    public static UserContext ensureSignedUp(String username, String password, NetworkAccess network, Crypto crypto) throws Exception {
        return PeergosNetworkUtils.ensureSignedUp(username, password, network, crypto);
    }
}
