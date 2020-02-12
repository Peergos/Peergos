package peergos.server.tests.slow;

import org.junit.*;
import org.junit.runner.*;
import org.junit.runners.*;
import peergos.server.*;
import peergos.server.tests.*;
import peergos.server.util.*;
import peergos.shared.*;
import peergos.shared.social.*;
import peergos.shared.user.*;

import java.net.*;
import java.util.*;
import java.util.stream.*;

@RunWith(Parameterized.class)
public class SocialBenchmark {

    private static int RANDOM_SEED = 666;
    private final NetworkAccess network;
    private final Crypto crypto = Main.initCrypto();

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

    // SendFollowRequest(19) duration: 2340 mS, best: 1519 mS, worst: 2340 mS, av: 1734 mS
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

    // ReplyToFollowRequest(19) duration: 4291 mS, best: 3392 mS, worst: 5239 mS, av: 3898 mS
    @Test
    public void replyToFollowRequest() {
        String username = generateUsername();
        String password = "test01";
        UserContext context = ensureSignedUp(username, password, network, crypto);
        List<String> names = new ArrayList<>();
        IntStream.range(0, 20).forEach(i -> names.add(generateUsername()));
        List<UserContext> users = names.stream()
                .map(name -> ensureSignedUp(name, password, network, crypto))
                .collect(Collectors.toList());

        for (int i = 0; i < 20; i++) {
            users.get(i).sendInitialFollowRequest(username).join();
        }

        List<FollowRequestWithCipherText> pending = context.getSocialState().join().pendingIncoming;
        long worst = 0, best = Long.MAX_VALUE, start = System.currentTimeMillis();
        // Profile accepting the requests
        for (int i = 0; i < 20; i++) {
            FollowRequestWithCipherText req = pending.get(i);
            long t1 = System.currentTimeMillis();
            context.sendReplyFollowRequest(req, true, true).join();
            long duration = System.currentTimeMillis() - t1;
            worst = Math.max(worst, duration);
            best = Math.min(best, duration);
            System.err.printf("ReplyToFollowRequest(%d) duration: %d mS, best: %d mS, worst: %d mS, av: %d mS\n", i,
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
