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
    private final Crypto crypto = Crypto.initJava();

    private static Random random = new Random(RANDOM_SEED);

    public SignupBenchmark(String useIPFS, Random r) throws Exception {
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

    // SIGNUP(10) duration: 9447 mS, best: 9336 mS, worst: 9994 mS, av: 9646 mS
    //    scrypt: 1377 mS
    //    pointers.set: 15*75 mS = 1125 mS
    //    pointers.get: 1*45 mS = 45 mS
    //    and the other 7s  ???
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
        return UserContext.ensureSignedUp(username, password, network, crypto).get();
    }
}
