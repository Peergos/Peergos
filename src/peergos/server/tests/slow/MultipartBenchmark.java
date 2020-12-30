package peergos.server.tests.slow;

import org.junit.*;
import org.junit.runner.*;
import org.junit.runners.*;
import peergos.server.*;
import peergos.server.tests.*;
import peergos.server.util.*;
import peergos.shared.*;
import peergos.shared.storage.*;
import peergos.shared.user.*;

import java.net.*;
import java.util.*;

@RunWith(Parameterized.class)
public class MultipartBenchmark {

    private static int RANDOM_SEED = 666;
    private final NetworkAccess network;
    private final Crypto crypto = Main.initCrypto();

    private static Random random = new Random(RANDOM_SEED);

    public MultipartBenchmark(String useIPFS, Random r) throws Exception {
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

    private String generateUsername() {
        return "test" + (random.nextInt() % 10000);
    }

    public static UserContext ensureSignedUp(String username, String password, NetworkAccess network, Crypto crypto) throws Exception {
        return PeergosNetworkUtils.ensureSignedUp(username, password, network, crypto);
    }

    // 70 KB/S throughput
    @Test
    public void smallFragments() throws Exception {
        testWriteThroughput(4096);
    }

    // 1500 KB/S throughput
    @Test
    public void largeFragments() throws Exception {
        testWriteThroughput(128*1024);
    }

    public void testWriteThroughput(int fragmentSize) throws Exception {
        String username = generateUsername();
        String password = "test01";
        UserContext context = ensureSignedUp(username, password, network, crypto);

        long worst = 0, best = Long.MAX_VALUE, start = System.currentTimeMillis();
        TransactionId tid = network.dhtClient.startTransaction(context.signer.publicKeyHash).join();
        byte[] data = new byte[fragmentSize];
        for (int i=0; i < 10000; i++) {
            random.nextBytes(data);
            long t1 = System.currentTimeMillis();
            network.dhtClient.put(context.signer.publicKeyHash, context.signer, data, network.hasher, tid).join();
            long duration = System.currentTimeMillis() - t1;
            worst = Math.max(worst, duration);
            best = Math.min(best, duration);
            System.err.printf("PUT(%d) duration: %d mS, best: %d mS, worst: %d mS, av: %d mS %d KB/S\n", i,
                    duration, best, worst, (t1 + duration - start) / (i + 1), data.length / duration);
        }
    }
}
