package peergos.server.tests.slow;

import org.junit.*;
import org.junit.runner.*;
import org.junit.runners.*;
import peergos.server.*;
import peergos.server.storage.*;
import peergos.server.tests.*;
import peergos.server.util.*;
import peergos.shared.*;
import peergos.shared.storage.*;
import peergos.shared.user.*;
import peergos.shared.user.fs.*;
import peergos.shared.util.*;

import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

@RunWith(Parameterized.class)
public class DeleteBenchmark {

    private static int RANDOM_SEED = 666;
    private final NetworkAccess network;
    private static final Crypto crypto = Main.initCrypto();
    private static final Random random = new Random(RANDOM_SEED);
    private static final Args args = UserTests.buildArgs().with("useIPFS", "false");

    public DeleteBenchmark() throws Exception {
        Main.PKI_INIT.main(args);
        this.network = buildHttpNetworkAccess();
    }

    private static NetworkAccess buildHttpNetworkAccess() throws Exception {
        NetworkAccess base = Builder.buildJavaNetworkAccess(new URL("http://localhost:" + args.getInt("port")), false).get()
                .withStorage(s -> new UnauthedCachingStorage(s, new RamBlockCache(1024*1204, 1000), crypto.hasher));
        int delayMillis = 50;
        return base.withStorage(s -> new DelayingStorage(s, delayMillis, delayMillis));
    }

    @Parameterized.Parameters()
    public static Collection<Object[]> parameters() {
        return Arrays.asList(new Object[][] {
                {}
        });
    }

    private String generateUsername() {
        return "test" + (random.nextInt() % 10000);
    }

    public static UserContext ensureSignedUp(String username, String password, NetworkAccess network, Crypto crypto) throws Exception {
        return PeergosNetworkUtils.ensureSignedUp(username, password, network, crypto);
    }

    // DELETE(100) duration: 15552 mS
    @Test
    public void deleteFolderOfSmallFiles() throws Exception {
        String username = generateUsername();
        String password = "test01";
        UserContext context = ensureSignedUp(username, password, network, crypto);
        FileWrapper userRoot = context.getUserRoot().get();
        List<String> names = new ArrayList<>();
        IntStream.range(0, 100).forEach(i -> names.add(randomString()));
        byte[] data = new byte[1024];
        random.nextBytes(data);

        List<FileWrapper.FileUploadProperties> files = names.stream()
                .map(n -> new FileWrapper.FileUploadProperties(n, AsyncReader.build(data), 0, data.length, false, false, x -> {}))
                .collect(Collectors.toList());
        String dirName = "folder";
        userRoot.uploadSubtree(Stream.of(new FileWrapper.FolderUploadProperties(Arrays.asList(dirName), files)),
                userRoot.mirrorBatId(), context.network, crypto, context.getTransactionService(), f -> Futures.of(false), () -> true).join();
        Path dirPath = PathUtil.get(username, dirName);
        FileWrapper folder = context.getByPath(dirPath).join().get();

        long start = System.currentTimeMillis();
        folder.remove(context.getUserRoot().join(), dirPath, context).join();
        long duration = System.currentTimeMillis() - start;
        System.err.printf("DELETE("+names.size()+") duration: %d mS\n", duration);
    }

    @Test
    public void deleteFolderOfSmallFilesWithEmptyCache() throws Exception {
        String username = generateUsername();
        String password = "test01";
        UserContext context = ensureSignedUp(username, password, network, crypto);
        FileWrapper userRoot = context.getUserRoot().get();
        List<String> names = new ArrayList<>();
        IntStream.range(0, 100).forEach(i -> names.add(randomString()));
        byte[] data = new byte[1024];
        random.nextBytes(data);

        List<FileWrapper.FileUploadProperties> files = names.stream()
                .map(n -> new FileWrapper.FileUploadProperties(n, AsyncReader.build(data), 0, data.length, false, false, x -> {}))
                .collect(Collectors.toList());
        String dirName = "folder";
        userRoot.uploadSubtree(Stream.of(new FileWrapper.FolderUploadProperties(Arrays.asList(dirName), files)),
                userRoot.mirrorBatId(), context.network, crypto, context.getTransactionService(), f -> Futures.of(false), () -> true).join();
        Path dirPath = PathUtil.get(username, dirName);
        FileWrapper folder = context.getByPath(dirPath).join().get();

        context = ensureSignedUp(username, password, buildHttpNetworkAccess(), crypto);
        System.out.println("Start DELETE");
        long start = System.currentTimeMillis();
        folder.remove(context.getUserRoot().join(), dirPath, context).join();
        long duration = System.currentTimeMillis() - start;
        System.err.printf("DELETE("+names.size()+") duration: %d mS\n", duration);
    }

    // DELETE_FILE(200) duration: 7445 mS old
    // DELETE_FILE(200) duration: 3058 mS new
    // DELETE_FILE(200) duration: 4058 mS parallel new
    @Test
    public void deleteLargeFile() throws Exception {
        String username = generateUsername();
        String password = "test01";
        UserContext context = ensureSignedUp(username, password, network, crypto);
        FileWrapper userRoot = context.getUserRoot().get();
        int size = 200*1024*1024;
        byte[] data = new byte[size];
        random.nextBytes(data);

        String filename = "file.bin";
        userRoot.uploadFileJS(filename, AsyncReader.build(data), 0, size, false,
                userRoot.mirrorBatId(), context.network, crypto, x -> {}, context.getTransactionService(), f -> Futures.of(false)).join();
        Path filePath = PathUtil.get(username, filename);
        FileWrapper file = context.getByPath(filePath).join().get();

        long start = System.currentTimeMillis();
        file.remove(context.getUserRoot().join(), filePath, context).join();
        long duration = System.currentTimeMillis() - start;
        System.err.printf("DELETE_FILE("+(size/1024/1024)+") duration: %d mS\n", duration);
    }

    private static String randomString() {
        return UUID.randomUUID().toString();
    }
}
