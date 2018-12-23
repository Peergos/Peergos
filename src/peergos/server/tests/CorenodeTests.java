package peergos.server.tests;
import java.util.logging.*;

import peergos.server.util.Args;
import peergos.server.util.Logging;

import org.junit.*;
import org.junit.runner.*;
import org.junit.runners.*;
import peergos.server.*;
import peergos.server.corenode.UsernameValidator;
import peergos.server.storage.*;
import peergos.shared.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.cid.*;
import peergos.shared.mutable.*;
import peergos.shared.storage.*;

import java.net.*;
import java.util.*;
import java.util.concurrent.*;

@RunWith(Parameterized.class)
public class CorenodeTests {
	private static final Logger LOG = Logging.LOG();

    private static Args args = UserTests.buildArgs().with("useIPFS", "true");

    public static int RANDOM_SEED = 666;
    private static Random random = new Random(RANDOM_SEED);

    private final NetworkAccess network;

    public CorenodeTests(Args args) throws Exception {
        this.network = NetworkAccess.buildJava(new URL("http://localhost:" + args.getInt("port"))).get();
    }

    @Parameterized.Parameters(name = "{index}: {0}")
    public static Collection<Object[]> parameters() {
        return Arrays.asList(new Object[][] {
                {args}
        });
    }

    @BeforeClass
    public static void init() {
        Main.PKI.main(args);
    }

    @Test
    public void writeThroughput() {
        Crypto crypto = Crypto.initJava();
        ForkJoinPool pool = new ForkJoinPool(10);

        List<Future<Long>> worstLatencies = new ArrayList<>();
        for (int t = 0; t < 10; t++)
            worstLatencies.add(pool.submit(() -> {
                SigningKeyPair owner = SigningKeyPair.random(crypto.random, crypto.signer);
                SigningKeyPair writer = SigningKeyPair.random(crypto.random, crypto.signer);
                PublicKeyHash ownerHash = ContentAddressedStorage.hashKey(owner.publicSigningKey);
                PublicKeyHash writerHash = ContentAddressedStorage.hashKey(owner.publicSigningKey);
                Transaction.run(ownerHash,
                        (ownerHash2, tid) -> network.dhtClient.putSigningKey(
                            owner.secretSigningKey.signatureOnly(owner.publicSigningKey.serialize()),
                                    ownerHash,
                            owner.publicSigningKey, tid),
                        network.dhtClient).get();
                Transaction.run(ownerHash,
                        (ownerHash2, tid) -> network.dhtClient.putSigningKey(
                        owner.secretSigningKey.signatureOnly(writer.publicSigningKey.serialize()),
                        ownerHash, writer.publicSigningKey, tid), network.dhtClient).get();

                byte[] data = new byte[10];

                MaybeMultihash current = MaybeMultihash.empty();
                long t1 = System.currentTimeMillis();
                int iterations = 100;
                long maxLatency = 0;
                for (int i = 0; i < iterations; i++) {
                    random.nextBytes(data);
                    Cid cid = RAMStorage.hashToCid(data, true);
                    HashCasPair cas = new HashCasPair(current, MaybeMultihash.of(cid));
                    byte[] signed = writer.signMessage(cas.serialize());
                    try {
                        long t3 = System.currentTimeMillis();
                        network.mutable.setPointer(ownerHash, writerHash, signed).get();
                        long latency = System.currentTimeMillis() - t3;
                        if (latency > maxLatency)
                            maxLatency = latency;
                        current = MaybeMultihash.of(cid);
                    } catch (Exception e) {
                        LOG.log(Level.WARNING, e.getMessage(), e);
                    }
                }
                long t2 = System.currentTimeMillis();
                System.out.printf("%d iterations took %d mS\n", iterations, t2 - t1);
                return maxLatency;
            }));
        long worstLatency = worstLatencies.stream().mapToLong(f -> {
            try {
                return f.get();
            } catch (Exception e) {
                return Long.MIN_VALUE;
            }
        }).max().getAsLong();
        LOG.info("Worst Latency: " + worstLatency);
        Assert.assertTrue("Worst latency < 1 second: " + worstLatency, worstLatency < 2000);
        pool.awaitQuiescence(5, TimeUnit.MINUTES);
    }

    @Test
    public void isValidUsernameTest() {
        List<String> areValid = Arrays.asList(
                "chris",
                "super_califragilistic_ex",
                "z",
                "ch_ris",
                "123456789012345678901234567890ab",
                "1337",
                "alpha-beta",
                "the-god-father");

        List<String> areNotValid = Arrays.asList(
                "123456789012345678901234567890abc",
                "",
                " ",
                "super_califragilistic_expialidocious",
                "\n",
                "\r",
                "\tted",
                "-ted",
                "_ted",
                "t__ed",
                "ted_",
                " ted",
                "<ted>",
                "ted-",
                "a-_b",
                "a_-b",
                "a--b",
                "fred--flinstone",
                "peter-_pan",
                "_hello",
                "hello.",
                "\b0");

        areValid.forEach(username -> Assert.assertTrue(username + " is valid", UsernameValidator.isValidUsername(username)));
        areNotValid.forEach(username -> Assert.assertFalse(username +" is not valid", UsernameValidator.isValidUsername(username)));
    }
}
