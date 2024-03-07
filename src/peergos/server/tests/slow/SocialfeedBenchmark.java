package peergos.server.tests.slow;

import org.junit.*;
import org.junit.runner.*;
import org.junit.runners.*;
import peergos.server.*;
import peergos.server.storage.*;
import peergos.server.tests.*;
import peergos.server.util.*;
import peergos.shared.*;
import peergos.shared.display.*;
import peergos.shared.social.*;
import peergos.shared.user.*;
import peergos.shared.user.fs.*;
import peergos.shared.util.*;

import java.net.*;
import java.util.*;
import java.util.function.*;
import java.util.stream.*;

@RunWith(Parameterized.class)
public class SocialfeedBenchmark {

    private static int RANDOM_SEED = 666;
    private final UserService service;
    private final NetworkAccess network;
    private final Crypto crypto = Main.initCrypto();

    private static Random random = new Random(RANDOM_SEED);

    public SocialfeedBenchmark(String useIPFS, Random r) throws Exception {
        Pair<UserService, NetworkAccess> pair = buildHttpNetworkAccess(useIPFS.equals("IPFS"), r);
        this.service = pair.left;
        this.network = pair.right;
    }

    private static Pair<UserService, NetworkAccess> buildHttpNetworkAccess(boolean useIpfs, Random r) throws Exception {
        Args args = UserTests.buildArgs().with("useIPFS", "" + useIpfs);
        UserService service = Main.PKI_INIT.main(args).localApi;
        NetworkAccess net = Builder.buildJavaNetworkAccess(new URL("http://localhost:" + args.getInt("port")), false).join();
        int delayMillis = 50;
        NetworkAccess delayed = net.withStorage(s -> new DelayingStorage(s, delayMillis, delayMillis));
        return new Pair<>(service, delayed);
    }

    @Parameterized.Parameters()
    public static Collection<Object[]> parameters() {
        return Arrays.asList(new Object[][] {
//                {"IPFS", new Random(0)}
                {"NOTIPFS", new Random(0)}
        });
    }

    // UpdateFeed(0) duration: 148704 mS, best: 148704 mS, worst: 148704 mS, av: 355258 mS
    // UpdateFeed(1) duration: 60547 mS, best: 60547 mS, worst: 148704 mS, av: 222145 mS => 55s
    // UpdateFeed(3) duration: 62495 mS, best: 60456 mS, worst: 148704 mS, av: 156035 mS => 57s
    // UpdateFeed(10) duration: 69855 mS, best: 62662 mS, worst: 156153 mS, av: 125725 mS
    // UpdateFeed(19) duration: 74355 mS, best: 62662 mS, worst: 156153 mS, av: 118671 mS
    @Test
    public void social() {
        String password = "test01";
        List<Pair<String, String>> logins = IntStream.range(0, 20)
                .mapToObj(i -> new Pair<>(generateUsername(), password))
                .collect(Collectors.toList());
        List<UserContext> users = logins.stream()
                .map(p -> ensureSignedUp(p.left, p.right, network.clear(), crypto))
                .collect(Collectors.toList());

        UserContext user = users.get(0);
        List<UserContext> friends = users.stream().skip(1).collect(Collectors.toList());
        PeergosNetworkUtils.friendBetweenGroups(List.of(user), friends);

        long worst = 0, best = Long.MAX_VALUE, start = System.currentTimeMillis();

        for (int i = 0; i < 4; i++) {
            // send 1 post from each friend
            friends.stream().forEach(f -> f.getSocialFeed().join()
                    .createNewPost(SocialPost.createInitialPost(f.username, List.of(new Text("Msg " + System.currentTimeMillis())), SocialPost.Resharing.Friends)).join());
            long t1 = System.currentTimeMillis();

            user.getSocialFeed().join().update().join();

            long duration = System.currentTimeMillis() - t1;
            worst = Math.max(worst, duration);
            best = Math.min(best, duration);
            System.err.printf("UpdateFeed(%d) duration: %d mS, best: %d mS, worst: %d mS, av: %d mS\n", i,
                    duration, best, worst, (t1 + duration - start) / (i + 1));
        }
    }



    private String generateUsername() {
        return "test" + (random.nextInt() % 10000);
    }

    public static UserContext ensureSignedUp(String username, String password, NetworkAccess network, Crypto crypto) {
        return PeergosNetworkUtils.ensureSignedUp(username, password, network, crypto);
    }

    public static <V> Pair<V, Long> time(Supplier<V> work) {
        long t0 = System.currentTimeMillis();
        V res = work.get();
        long t1 = System.currentTimeMillis();
        return new Pair<>(res, t1 - t0);
    }
}
