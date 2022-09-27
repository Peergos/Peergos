package peergos.server.tests.slow;

import org.junit.*;
import org.junit.runner.*;
import org.junit.runners.*;
import peergos.server.*;
import peergos.server.storage.*;
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
    private final Crypto crypto = Main.initCrypto();

    private static Random random = new Random(RANDOM_SEED);

    public SmallFileBenchmark(String useIPFS, Random r) throws Exception {
        this.network = buildHttpNetworkAccess(useIPFS.equals("IPFS"), r);
    }

    private static NetworkAccess buildHttpNetworkAccess(boolean useIpfs, Random r) throws Exception {
        Args args = UserTests.buildArgs().with("useIPFS", "" + useIpfs);
        Main.PKI_INIT.main(args);
        NetworkAccess base = Builder.buildJavaNetworkAccess(new URL("http://localhost:" + args.getInt("port")), false).get();
        int delayMillis = 50;
        return base.withStorage(s -> new DelayingStorage(s, delayMillis, delayMillis));
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

    // UPLOAD(100) duration: 6137 mS, av: 61 mS
    @Test
    public void smallFilesBulk() throws Exception {
        String username = generateUsername();
        String password = "test01";
        UserContext context = ensureSignedUp(username, password, network, crypto);
        FileWrapper userRoot = context.getUserRoot().get();
        List<String> names = new ArrayList<>();
        IntStream.range(0, 100).forEach(i -> names.add(randomString()));
        byte[] data = new byte[1024];
        random.nextBytes(data);

        long start = System.currentTimeMillis();
        List<Long> progressReceived = new ArrayList<>();
        ProgressConsumer<Long> progressCounter = (num) -> {
            progressReceived.add(num);
        };
        List<FileWrapper.FileUploadProperties> files = names.stream()
                .map(n -> new FileWrapper.FileUploadProperties(n, AsyncReader.build(data), 0, data.length, false, false, progressCounter))
                .collect(Collectors.toList());
        userRoot.uploadSubtree(Stream.of(new FileWrapper.FolderUploadProperties(Collections.emptyList(), files)),
                userRoot.mirrorBatId(), context.network, crypto, context.getTransactionService(), f -> Futures.of(false), () -> true).join();
        long duration = System.currentTimeMillis() - start;
        System.err.printf("UPLOAD("+names.size()+") duration: %d mS, av: %d mS\n", duration, (duration) / names.size());
        Assert.assertTrue("Correct progress", progressReceived.stream().mapToLong(i -> i).sum() == data.length * names.size());
    }

    // UPLOAD(0) duration: 1085 mS, best: 1085 mS, worst: 1085 mS, av: 1085 mS
    // to
    // UPLOAD(99) duration: 1240 mS, best: 1015 mS, worst: 1655 mS, av: 1230 mS
    //
    // GetData(99) duration: 28 mS, best: 27 mS, worst: 43 mS, av: 29 mS
    // ****** delayed ******
    // UPLOAD(0) duration: 1209 mS, best: 1209 mS, worst: 1209 mS, av: 1209 mS
    // to
    // UPLOAD(99) duration: 1598 mS, best: 1130 mS, worst: 2101 mS, av: 1610 mS
    //
    // GetData(99) duration: 70 mS, best: 60 mS, worst: 114 mS, av: 69 mS
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
            userRoot = userRoot.uploadOrReplaceFile(filename, AsyncReader.build(data), data.length, context.network,
                    crypto, x-> {}).join();
            long duration = System.currentTimeMillis() - t1;
            worst = Math.max(worst, duration);
            best = Math.min(best, duration);
            System.err.printf("UPLOAD(%d) duration: %d mS, best: %d mS, worst: %d mS, av: %d mS\n", i,
                    duration, best, worst, (t1 + duration - start) / (i + 1));
        }

        long worstRead = 0, bestRead = Long.MAX_VALUE, startRead = System.currentTimeMillis();
        for (int i=0; i < 100; i++) {
            long t1 = System.currentTimeMillis();
            FileWrapper file = context.getByPath(PathUtil.get(username, names.get(random.nextInt(names.size()))))
                    .join().get();
            AsyncReader reader = file.getInputStream(network, crypto, x -> {}).join();
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
