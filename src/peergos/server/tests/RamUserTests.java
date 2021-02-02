package peergos.server.tests;

import org.junit.*;
import org.junit.runner.*;
import org.junit.runners.*;
import peergos.server.*;
import peergos.server.util.*;
import peergos.shared.*;
import peergos.shared.social.*;
import peergos.shared.user.*;
import peergos.shared.user.fs.*;
import peergos.shared.util.*;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

@RunWith(Parameterized.class)
public class RamUserTests extends UserTests {
    private static Args args = buildArgs().with("useIPFS", "false");

    public RamUserTests(NetworkAccess network, UserService service) {
        super(network, service);
    }

    @Parameterized.Parameters()
    public static Collection<Object[]> parameters() throws Exception {
        UserService service = Main.PKI_INIT.main(args);
        WriteSynchronizer synchronizer = new WriteSynchronizer(service.mutable, service.storage, crypto.hasher);
        MutableTree mutableTree = new MutableTreeImpl(service.mutable, service.storage, crypto.hasher, synchronizer);
        // use actual http messager
        ServerMessager.HTTP serverMessager = new ServerMessager.HTTP(new JavaPoster(new URI("http://localhost:" + args.getArg("port")).toURL(), false));
        NetworkAccess network = new NetworkAccess(service.coreNode, service.social, service.storage,
                service.mutable, mutableTree, synchronizer, service.controller, service.usage,
                serverMessager, service.crypto.hasher,
                Arrays.asList("peergos"), false);
        return Arrays.asList(new Object[][] {
                {network, service}
        });
    }

    @AfterClass
    public static void cleanup() {
        try {Thread.sleep(2000);}catch (InterruptedException e) {}
        Path peergosDir = args.fromPeergosDir("", "");
        System.out.println("Deleting " + peergosDir);
        deleteFiles(peergosDir.toFile());
    }

    @Test
    public void publicWebHosting() throws Exception {
        String username = generateUsername();
        String password = "password";
        UserContext context = PeergosNetworkUtils.ensureSignedUp(username, password, network, crypto);
        String dirName = "website";
        context.getUserRoot().join().mkdir(dirName, context.network, false, crypto).join();
        byte[] data = "<html><body><h1>You are AWESOME!</h1></body></html>".getBytes();
        context.getByPath(username + "/" + dirName).join().get()
                .uploadOrReplaceFile("index.html", AsyncReader.build(data), data.length, network, crypto, x -> {},
                        crypto.random.randomBytes(32)).join();
        ProfilePaths.setWebRoot(context, "/" + username + "/" + dirName).join();
        ProfilePaths.publishWebroot(context).join();

        // start a gateway
        Args a = Args.parse(new String[]{
                "-peergos-url", "http://localhost:" + args.getInt("port"),
                "-port", "9000",
                "-domain", "localhost",
                "-domain-suffix", ".peergos.localhost:9000"
        });
        PublicGateway publicGateway = Main.startGateway(a);

        // retrieve website
        byte[] retrieved = get(new URI("http://" + username + ".peergos.localhost:9000").toURL());
        Assert.assertTrue(Arrays.equals(retrieved, data));

        publicGateway.shutdown();
    }

    private static byte[] get(URL target) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) target.openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Host", target.getHost());

        InputStream in = conn.getInputStream();
        ByteArrayOutputStream resp = new ByteArrayOutputStream();

        byte[] buf = new byte[4096];
        int r;
        while ((r = in.read(buf)) >= 0)
            resp.write(buf, 0, r);
        return resp.toByteArray();
    }

    @Test
    public void bufferedReaderTest() throws Exception {

        String username = "test";
        String password = "test01";
        UserContext context = PeergosNetworkUtils.ensureSignedUp(username, password, network, crypto);
        FileWrapper userRoot = context.getUserRoot().join();

        String filename = "sintel.mp4";
        Random random = new Random(666);
        byte[] fileData = new byte[14621544];
        random.nextBytes(fileData);

        FileWrapper userRoot2 = userRoot.uploadOrReplaceFile(filename, new AsyncReader.ArrayBacked(fileData), fileData.length,
                context.network, context.crypto, l -> {}, context.crypto.random.randomBytes(32)).join();

        FileWrapper file = context.getByPath(Paths.get(username, filename)).join().get();
        FileProperties props = file.getFileProperties();
        int sizeHigh = props.sizeHigh();
        int sizeLow = props.sizeLow();

        int seekHi = 0;
        //int seekLo = 0;
        //int length = 1048576;

        int seekLo = 786432;
        int length = 5242880;
        //file length = 14,621,544
        final int maxBlockSize = 1024 * 1024 * 5;

        List<byte[]> resultBytes = new ArrayList<>();
        boolean result = file.getBufferedInputStream(network, crypto, sizeHigh, sizeLow, 1, l -> {}).thenCompose(reader -> {
            return reader.seekJS(seekHi, seekLo).thenApply(seekReader -> {
                final int blockSize = length > maxBlockSize ? maxBlockSize : length;
                return pump(seekReader, length, blockSize, resultBytes);
            });
        }).join().get();

        List<byte[]> resultBytes2 = new ArrayList<>();
        boolean result2 = file.getInputStream(network, crypto, sizeHigh, sizeLow, l -> {}).thenCompose(reader -> {
            return reader.seekJS(seekHi, seekLo).thenApply(seekReader -> {
                final int blockSize = length > maxBlockSize ? maxBlockSize : length;
                return pump(seekReader, length, blockSize, resultBytes2);
            });
        }).join().get();
        compare(resultBytes, resultBytes2);
    }

    @Test
    public void testReuseOfAsyncReader() throws Exception {

        String username = "test";
        String password = "test01";
        UserContext context = PeergosNetworkUtils.ensureSignedUp(username, password, network, crypto);
        FileWrapper userRoot = context.getUserRoot().join();

        String filename = "sintel.mp4";
        Random random = new Random(666);
        byte[] fileData = new byte[14621544];
        random.nextBytes(fileData);
        FileWrapper userRoot2 = userRoot.uploadOrReplaceFile(filename, new AsyncReader.ArrayBacked(fileData), fileData.length,
                context.network, context.crypto, l -> {
                }, context.crypto.random.randomBytes(32)).join();

        FileWrapper file = context.getByPath(Paths.get(username, filename)).join().get();
        FileProperties props = file.getFileProperties();
        int sizeHigh = props.sizeHigh();
        int sizeLow = props.sizeLow();

        final int maxBlockSize = 1024 * 1024 * 5;
        final int fileLength = sizeLow;
        AsyncReader reader = file.getBufferedInputStream(network, crypto, sizeHigh, sizeLow, 2, l -> {}).join();
        int seekHi = 0;
        int seekLo = 0;
        int length = 1 * 1024 * 1024;
        reader = reuseExistingReader(reader, file, sizeHigh, sizeLow, seekHi, seekLo, length, maxBlockSize, false);

        seekLo = fileLength - (1024 * 1024 * 1);
        length = fileLength - seekLo;
        reader = reuseExistingReader(reader, file, sizeHigh, sizeLow, seekHi, seekLo, length, maxBlockSize, false);
        System.currentTimeMillis();

        seekHi = 0;
        seekLo = 0;
        length = fileLength;
        reader = reuseExistingReader(reader, file, sizeHigh, sizeLow, seekHi, seekLo, length, maxBlockSize, false);
        System.currentTimeMillis();
    }

    @Test
    public void testReuseOfAsyncReaderSerialRead() throws Exception {

        String username = "test";
        String password = "test01";
        UserContext context = PeergosNetworkUtils.ensureSignedUp(username, password, network, crypto);
        FileWrapper userRoot = context.getUserRoot().join();

        String filename = "sintel.mp4";
        Random random = new Random(666);
        byte[] fileData = new byte[14621544];
        random.nextBytes(fileData);

        FileWrapper userRoot2 = userRoot.uploadOrReplaceFile(filename, new AsyncReader.ArrayBacked(fileData), fileData.length,
                context.network, context.crypto, l -> {
                }, context.crypto.random.randomBytes(32)).join();

        FileWrapper file = context.getByPath(Paths.get(username, filename)).join().get();
        FileProperties props = file.getFileProperties();
        int sizeHigh = props.sizeHigh();
        int sizeLow = props.sizeLow();

        final int maxBlockSize = 1024 * 1024 * 5;
        AsyncReader reader = file.getBufferedInputStream(network, crypto, sizeHigh, sizeLow, 2, l -> {}).join();
        int seekHi = 0;
        int seekLo = 0;
        int length = maxBlockSize;
        reader = reuseExistingReader(reader, file, sizeHigh, sizeLow, seekHi, seekLo, length, maxBlockSize, false);

        seekLo = maxBlockSize;
        length = maxBlockSize;
        reader = reuseExistingReader(reader, file, sizeHigh, sizeLow, seekHi, seekLo, length, maxBlockSize, true);
        System.currentTimeMillis();
    }

    private AsyncReader reuseExistingReader(AsyncReader reader, FileWrapper file, int sizeHigh, int sizeLow,
                                           int seekHi, int seekLo, int length, int maxBlockSize, boolean serialAccess) throws Exception {
        List<AsyncReader> currentAsyncReader = new ArrayList<>();
        currentAsyncReader.add(reader);
        List<byte[]> resultBytes2 = new ArrayList<>();
        boolean result2 = file.getInputStream(network, crypto, sizeHigh, sizeLow, l -> {
        }).thenCompose(reader2 -> {
            return reader2.seekJS(seekHi, seekLo).thenApply(seekReader -> {
                final int blockSize = length > maxBlockSize ? maxBlockSize : length;
                return pump(seekReader, length, blockSize, resultBytes2);
            });
        }).join().get();

        List<byte[]> resultBytes3 = new ArrayList<>();

        boolean result3 = reader.seekJS(seekHi, seekLo).thenApply(seekReader -> {
            if(serialAccess && reader != seekReader) {
                throw new Error("Expecting reader reuse!");
            }
            currentAsyncReader.remove(0);
            currentAsyncReader.add(seekReader);
            final int blockSize = length > maxBlockSize ? maxBlockSize : length;
            return pump(currentAsyncReader.get(0), length, blockSize, resultBytes3);
        }).join().get();

        compare(resultBytes2, resultBytes3);
        return currentAsyncReader.get(0);
    }

    private void compare(List<byte[]> resultBytes, List<byte[]> resultBytes2 ) {
        if(resultBytes.size() != resultBytes2.size()) {
            throw new Error("wrong!");
        }
        for(int i=0; i < resultBytes.size(); i++) {
            byte[] result1 = resultBytes.get(i);
            byte[] result2 = resultBytes2.get(i);
            if(result1.length != result2.length) {
                throw new Error("wrong!");
            }
            for(int j=0; j < result1.length; j++) {
                if(result1[j] != result2[j]) {
                    throw new Error("wrong!");
                }
            }
        }
        System.currentTimeMillis();
    }

    private CompletableFuture<Boolean> pump(AsyncReader reader, Integer currentSize, Integer blockSize, List<byte[]> resultBytes) {
        final int maxBlockSize = 1024 * 1024 * 5;
        if(blockSize > 0) {
            byte[] data = new byte[blockSize];
            return reader.readIntoArray(data, 0, blockSize).thenCompose(read -> {
                int newCurrentSize = currentSize - read;
                int newBlockSize = newCurrentSize > maxBlockSize ? maxBlockSize : newCurrentSize;
                resultBytes.add(data);
                return pump(reader, newCurrentSize, newBlockSize, resultBytes);
            });
        } else {
            CompletableFuture<Boolean> future = Futures.incomplete();
            future.complete(true);
            return future;
        }
    }

    @Test
    public void revokeWriteAccessToTree() throws Exception {
        String username1 = generateUsername();
        String password = "test";
        UserContext user1 = PeergosNetworkUtils.ensureSignedUp(username1, password, network, crypto);
        FileWrapper user1Root = user1.getUserRoot().get();

        String folder1 = "folder1";
        user1Root.mkdir(folder1, user1.network, false, crypto).join();

        String folder11 = "folder1.1";
        user1.getByPath(Paths.get(username1, folder1)).join().get()
                .mkdir(folder11, user1.network, false, crypto).join();

        String filename = "somedata.txt";
        // write empty file
        byte[] data = new byte[0];
        user1.getByPath(Paths.get(username1, folder1, folder11)).join().get()
                .uploadOrReplaceFile(filename, new AsyncReader.ArrayBacked(data), data.length, user1.network,
                crypto, l -> {}, crypto.random.randomBytes(32)).get();

        // create 2nd user and friend user1
        String username2 = generateUsername();
        UserContext user2 = PeergosNetworkUtils.ensureSignedUp(username2, password, network, crypto);
        user2.sendInitialFollowRequest(username1).join();
        List<FollowRequestWithCipherText> incoming = user1.getSocialState().join().pendingIncoming;
        user1.sendReplyFollowRequest(incoming.get(0), true, true).join();
        user2.getSocialState().join();

        user1.shareWriteAccessWith(Paths.get(username1, folder1), Collections.singleton(username2)).join();

        user1.unShareWriteAccess(Paths.get(username1, folder1), username2).join();
        // check user1 can still log in
        UserContext freshUser1 = PeergosNetworkUtils.ensureSignedUp(username1, password, network, crypto);
    }
}
