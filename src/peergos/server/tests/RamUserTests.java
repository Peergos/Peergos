package peergos.server.tests;

import com.eatthepath.otp.*;
import org.junit.*;
import org.junit.runner.*;
import org.junit.runners.*;
import peergos.server.*;
import peergos.server.tests.util.*;
import peergos.server.util.*;
import peergos.shared.*;
import peergos.shared.cbor.*;
import peergos.shared.login.mfa.*;
import peergos.shared.social.*;
import peergos.shared.user.*;
import peergos.shared.user.fs.*;
import peergos.shared.user.fs.transaction.*;
import peergos.shared.util.*;

import javax.crypto.spec.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.stream.*;

@RunWith(Parameterized.class)
public class RamUserTests extends UserTests {
    private static Args args = buildArgs().with("useIPFS", "false");

    public RamUserTests(NetworkAccess network, UserService service) {
        super(network, service);
    }

    @Parameterized.Parameters()
    public static Collection<Object[]> parameters() throws Exception {
        UserService service = Main.PKI_INIT.main(args).localApi;
        WriteSynchronizer synchronizer = new WriteSynchronizer(service.mutable, service.storage, crypto.hasher);
        MutableTree mutableTree = new MutableTreeImpl(service.mutable, service.storage, crypto.hasher, synchronizer);
        // use actual http messager
        ServerMessager.HTTP serverMessager = new ServerMessager.HTTP(new JavaPoster(new URI("http://localhost:" + args.getArg("port")).toURL(), false));
        NetworkAccess network = new NetworkAccess(service.coreNode, service.account, service.social, service.storage,
                service.bats, Optional.empty(), service.mutable, mutableTree, synchronizer, service.controller, service.usage,
                serverMessager, service.crypto.hasher,
                Arrays.asList("peergos"), false);
        return Arrays.asList(new Object[][] {
                {network, service}
        });
    }

    @Override
    public Args getArgs() {
        return args;
    }

    @AfterClass
    public static void cleanup() {
        try {Thread.sleep(2000);}catch (InterruptedException e) {}
        Path peergosDir = args.fromPeergosDir("", "");
        System.out.println("Deleting " + peergosDir);
        deleteFiles(peergosDir.toFile());
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().startsWith("windows");
    }
    private static boolean isMacos() {
        return System.getProperty("os.name").toLowerCase().startsWith("mac");
    }

    @Test
    public void mfa() throws Throwable {
        String username = generateUsername();
        String password = "password";
        UserContext context = PeergosNetworkUtils.ensureSignedUp(username, password, network, crypto);
        Assert.assertTrue(context.network.account.getSecondAuthMethods(username, context.signer).join().isEmpty());

        TimeBasedOneTimePasswordGenerator totp = new TimeBasedOneTimePasswordGenerator(Duration.ofSeconds(30L), 6, TotpKey.ALGORITHM);
        TotpKey key = addTotpKey(context, totp);

        List<MultiFactorAuthMethod> enabled = context.network.account.getSecondAuthMethods(username, context.signer).join();
        Assert.assertTrue(enabled.size() == 1 && enabled.get(0).enabled);

        // now try logging in again, now with mfa
        testLoginRequiresTotp(username, password, network, totp, key);

        // Now delete the second factor and login again without MFA
        context.network.account.deleteSecondFactor(username, enabled.get(0).credentialId, context.signer).join();
        Assert.assertTrue(context.network.account.getSecondAuthMethods(username, context.signer).join().isEmpty());
        context = PeergosNetworkUtils.ensureSignedUp(username, password, network, crypto);

        // now add a new totp key
        TotpKey key2 = addTotpKey(context, totp);
        testLoginRequiresTotp(username, password, network, totp, key2);

        // Now add a 3rd which should delete the old one
        TotpKey key3 = addTotpKey(context, totp);
        testLoginRequiresTotp(username, password, network, totp, key3);
        // logging in with old totp key should fail
        try {
            testLoginRequiresTotp(username, password, network, totp, key2);
            throw new Throwable("Shouldn't get here!");
        } catch (Exception e) {
            e.printStackTrace();
        }
        // test that the old totp is deleted when new one is enabled
        Assert.assertTrue(context.network.account.getSecondAuthMethods(username, context.signer).join().size() == 1);
    }

    private static void testLoginRequiresTotp(String username,
                                              String password,
                                              NetworkAccess network,
                                              TimeBasedOneTimePasswordGenerator totp,
                                              TotpKey totpKey) {
        AtomicBoolean usedMfa = new AtomicBoolean(false);
        UserContext freshLogin = UserContext.signIn(username, password, req -> {
            List<MultiFactorAuthMethod> totps = req.methods.stream().filter(m -> m.type == MultiFactorAuthMethod.Type.TOTP).collect(Collectors.toList());
            if (totps.isEmpty())
                throw new IllegalStateException("No supported 2 factor auth method! " + req.methods);
            MultiFactorAuthMethod method = totps.get(totps.size() - 1);
            usedMfa.set(true);
            try {
                return Futures.of(new MultiFactorAuthResponse(method.credentialId, Either.a(totp.generateOneTimePasswordString(new SecretKeySpec(totpKey.key, TotpKey.ALGORITHM), Instant.now()))));
            } catch (InvalidKeyException e) {
                throw new RuntimeException(e);
            }
        }, network, crypto).join();
        Assert.assertTrue(usedMfa.get());
    }

    private static TotpKey addTotpKey(UserContext context, TimeBasedOneTimePasswordGenerator totp) throws Exception {
        TotpKey totpKey = context.network.account.addTotpFactor(context.username, context.signer).join();
        // User stores totp key in authenticator app via QR code

        List<MultiFactorAuthMethod> disabled = context.network.account.getSecondAuthMethods(context.username, context.signer)
                .join()
                .stream()
                .filter(t -> !t.enabled)
                .collect(Collectors.toList());
        Assert.assertTrue(disabled.isEmpty());

        // need to verify once to enable the second factor
        // (to guard against things like google authenticator which silently ignore the algorithm)
        Key key = new SecretKeySpec(totpKey.key, TotpKey.ALGORITHM);

        Instant now = Instant.now();
        String clientCode = totp.generateOneTimePasswordString(key, now);
        context.network.account.enableTotpFactor(context.username, totpKey.credentialId, clientCode, context.signer).join();
        return totpKey;
    }

    @Test
    public void publicWebHosting() throws Exception {
        if (isWindows() || isMacos()) // Windows/MacOS doesn't allow localhost domains natively
            return;
        String username = generateUsername();
        String password = "password";
        UserContext context = PeergosNetworkUtils.ensureSignedUp(username, password, network, crypto);
        String dirName = "website";
        context.getUserRoot().join().mkdir(dirName, context.network, false, context.mirrorBatId(), crypto).join();
        byte[] data = "<html><body><h1>You are AWESOME!</h1></body></html>".getBytes();
        context.getByPath(username + "/" + dirName).join().get()
                .uploadOrReplaceFile("index.html", AsyncReader.build(data), data.length, network, crypto, x -> {}).join();
        ProfilePaths.setWebRoot(context, "/" + username + "/" + dirName).join();
        ProfilePaths.publishWebroot(context).join();

        // start a gateway
        Args a = Args.parse(new String[]{
                "-peergos-url", "http://localhost:" + args.getInt("port"),
                "-port", "9002",
                "-domain", "localhost",
                "-domain-suffix", ".peergos.localhost:9002"
        });
        PublicGateway publicGateway = Main.startGateway(a);

        // retrieve website
        byte[] retrieved = get(new URI("http://" + username + ".peergos.localhost:9002").toURL());
        Assert.assertTrue(Arrays.equals(retrieved, data));

        publicGateway.shutdown();
    }

    @Test
    public void cleanupFailedUploads() throws Exception {
        String username = generateUsername();
        String password = "terriblepassword";
        UserContext context = PeergosNetworkUtils.ensureSignedUp(username, password, network, crypto);
        FileWrapper userRoot = context.getUserRoot().join();
        long initialUsage = context.getSpaceUsage().join();
        int size = 100*1024*1024;
        byte[] data = new byte[size];
        AsyncReader thrower = new ThrowingStream(data, size/2);
        FileWrapper txnDir = context.getByPath(Paths.get(username, UserContext.TRANSACTIONS_DIR_NAME)).join().get();
        TransactionService txns = new NonClosingTransactionService(network, crypto, txnDir);
        try {
            FileWrapper.FileUploadProperties fileUpload = new FileWrapper.FileUploadProperties("somefile", thrower, 0, size, false, false, x -> {});
            FileWrapper.FolderUploadProperties dirUploads = new FileWrapper.FolderUploadProperties(Arrays.asList(username), Arrays.asList(fileUpload));
            userRoot.uploadSubtree(Stream.of(dirUploads), context.mirrorBatId(), network, crypto, txns, f -> Futures.of(false), () -> true).join();
        } catch (Exception e) {}
        try {
            context.getUserRoot().join().uploadFileJS("anotherfile", thrower, 0, size, false,
                    context.mirrorBatId(), network, crypto, x -> {}, txns, f -> Futures.of(false)).join();
        } catch (Exception e) {}
        long usageAfterFail = context.getSpaceUsage().join();
        if (usageAfterFail <= size / 2) { // give server a chance to recalculate usage
            Thread.sleep(2_000);
            usageAfterFail = context.getSpaceUsage().join();
        }
        Assert.assertTrue(usageAfterFail > size / 2);
        context.cleanPartialUploads(t -> true).join();
        long usageAfterCleanup = context.getSpaceUsage().join();
        while (usageAfterCleanup >= initialUsage + 5000) {
            Thread.sleep(1_000);
            usageAfterCleanup = context.getSpaceUsage().join();
        }
        Assert.assertTrue(usageAfterCleanup < initialUsage + 5000); // TODO: investigate why 5000 more (open transactions in db referencing blocks?)
    }

    @Test
    public void cleanupFailedUploadsInDifferentWritingSpace() throws Exception {
        String username = generateUsername();
        String password = "terriblepassword";
        UserContext context = PeergosNetworkUtils.ensureSignedUp(username, password, network, crypto);
        FileWrapper userRoot = context.getUserRoot().join();
        String subdir = "subdir";
        userRoot.mkdir(subdir, network, false, Optional.empty(), crypto).join();
        Path subdirPath = PathUtil.get(username, subdir);
        FileWrapper subdirectory = context.getByPath(subdirPath).join().get();
        // put sub directory in a new writing space
        context.shareWriteAccessWith(subdirPath, Collections.emptySet()).join();

        userRoot = context.getUserRoot().join();

        long initialUsage = context.getSpaceUsage().join();
        int size = 100*1024*1024;
        byte[] data = new byte[size];
        AsyncReader thrower = new ThrowingStream(data, size/2);
        FileWrapper txnDir = context.getByPath(Paths.get(username, UserContext.TRANSACTIONS_DIR_NAME)).join().get();
        TransactionService txns = new NonClosingTransactionService(network, crypto, txnDir);
        try {
            FileWrapper.FileUploadProperties fileUpload = new FileWrapper.FileUploadProperties("somefile", thrower, 0, size, false, false, x -> {});
            FileWrapper.FolderUploadProperties dirUploads = new FileWrapper.FolderUploadProperties(Arrays.asList(subdir), Arrays.asList(fileUpload));
            userRoot.uploadSubtree(Stream.of(dirUploads), context.mirrorBatId(), network, crypto, txns, f -> Futures.of(false), () -> true).join();
        } catch (Exception e) {}
        long usageAfterFail = context.getSpaceUsage().join();
        if (usageAfterFail <= size / 2) { // give server a chance to recalculate usage
            Thread.sleep(2_000);
            usageAfterFail = context.getSpaceUsage().join();
        }
        Assert.assertTrue(usageAfterFail > size / 2);

        // delete the new writing space
        FileWrapper sub = context.getByPath(subdirPath).join().get();

        sub.remove(context.getUserRoot().get(), subdirPath, context).join();
        long usageAfterDelete = context.getSpaceUsage().join();
        while (usageAfterDelete >= size / 2) { // give server a chance to recalculate usage
            Thread.sleep(2_000);
            usageAfterDelete = context.getSpaceUsage().join();
        }
        Assert.assertTrue(usageAfterDelete < initialUsage);

        // clean the partial upload
        context.cleanPartialUploads(t -> true).join();
        long usageAfterCleanup = context.getSpaceUsage().join();
        Assert.assertTrue(usageAfterCleanup < usageAfterDelete);
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
                context.network, context.crypto, l -> {}).join();

        FileWrapper file = context.getByPath(PathUtil.get(username, filename)).join().get();
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
        boolean result = file.getBufferedInputStream(network, crypto, sizeHigh, sizeLow, 4, l -> {}).thenCompose(reader -> {
            return reader.seekJS(seekHi, seekLo).thenApply(seekReader -> {
                final int blockSize = length > maxBlockSize ? maxBlockSize : length;
                return pump(seekReader, length, blockSize, resultBytes);
            });
        }).join().join();

        List<byte[]> resultBytes2 = new ArrayList<>();
        boolean result2 = file.getInputStream(network, crypto, sizeHigh, sizeLow, l -> {}).thenCompose(reader -> {
            return reader.seekJS(seekHi, seekLo).thenApply(seekReader -> {
                final int blockSize = length > maxBlockSize ? maxBlockSize : length;
                return pump(seekReader, length, blockSize, resultBytes2);
            });
        }).join().join();
        compare(resultBytes, resultBytes2);
    }

    @Test
    public void bufferedReaderSeek() {
        String username = generateUsername();
        String password = "test";
        UserContext context = PeergosNetworkUtils.ensureSignedUp(username, password, network, crypto);
        FileWrapper userRoot = context.getUserRoot().join();

        String filename = "data.bin";
        Random random = new Random(666);
        byte[] fileData = new byte[20*1024*1024];
        random.nextBytes(fileData);

        FileWrapper userRoot2 = userRoot.uploadOrReplaceFile(filename, new AsyncReader.ArrayBacked(fileData), fileData.length,
                context.network, context.crypto, l -> {}).join();

        FileWrapper file = context.getByPath(PathUtil.get(username, filename)).join().get();
        FileProperties props = file.getFileProperties();
        int sizeHigh = props.sizeHigh();
        int sizeLow = props.sizeLow();

        int seekHi = 0;
        int seekLo = 10*1024*1024;
        int length = 5242880;
        final int maxBlockSize = 1024 * 1024 * 5;

        List<byte[]> resultBytes = new ArrayList<>();
        AsyncReader reader = file.getBufferedInputStream(network, crypto, sizeHigh, sizeLow, 4, l -> {}).join();
        reader.readIntoArray(new byte[1024*1024], 0, 1024*1024).join();
        reader.seekJS(seekHi, seekLo).thenApply(seekReader -> {
            final int blockSize = length > maxBlockSize ? maxBlockSize : length;
            return pump(seekReader, length, blockSize, resultBytes);
        }).join();

        List<byte[]> resultBytes2 = new ArrayList<>();
        resultBytes2.add(Arrays.copyOfRange(fileData, seekLo, seekLo + maxBlockSize));
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
                context.network, context.crypto, l -> {}).join();

        FileWrapper file = context.getByPath(PathUtil.get(username, filename)).join().get();
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
                context.network, context.crypto, l -> {}).join();

        FileWrapper file = context.getByPath(PathUtil.get(username, filename)).join().get();
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
        }).join().join();

        List<byte[]> resultBytes3 = new ArrayList<>();

        boolean result3 = reader.seekJS(seekHi, seekLo).thenApply(seekReader -> {
            if(serialAccess && reader != seekReader) {
                throw new Error("Expecting reader reuse!");
            }
            currentAsyncReader.remove(0);
            currentAsyncReader.add(seekReader);
            final int blockSize = length > maxBlockSize ? maxBlockSize : length;
            return pump(currentAsyncReader.get(0), length, blockSize, resultBytes3);
        }).join().join();

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
        FileWrapper user1Root = user1.getUserRoot().join();

        String folder1 = "folder1";
        user1Root.mkdir(folder1, user1.network, false, user1.mirrorBatId(), crypto).join();

        String folder11 = "folder1.1";
        user1.getByPath(PathUtil.get(username1, folder1)).join().get()
                .mkdir(folder11, user1.network, false, user1.mirrorBatId(), crypto).join();

        String filename = "somedata.txt";
        // write empty file
        byte[] data = new byte[0];
        user1.getByPath(PathUtil.get(username1, folder1, folder11)).join().get()
                .uploadOrReplaceFile(filename, new AsyncReader.ArrayBacked(data), data.length, user1.network,
                crypto, l -> {}).join();

        // create 2nd user and friend user1
        String username2 = generateUsername();
        UserContext user2 = PeergosNetworkUtils.ensureSignedUp(username2, password, network, crypto);
        user2.sendInitialFollowRequest(username1).join();
        List<FollowRequestWithCipherText> incoming = user1.getSocialState().join().pendingIncoming;
        user1.sendReplyFollowRequest(incoming.get(0), true, true).join();
        user2.getSocialState().join();

        user1.shareWriteAccessWith(PathUtil.get(username1, folder1), Collections.singleton(username2)).join();

        user1.unShareWriteAccess(PathUtil.get(username1, folder1), username2).join();
        // check user1 can still log in
        UserContext freshUser1 = PeergosNetworkUtils.ensureSignedUp(username1, password, network, crypto);
    }

    @Test
    public void secretLinkV2() throws Exception {
        String username = generateUsername();
        String password = "test";
        UserContext user = PeergosNetworkUtils.ensureSignedUp(username, password, network, crypto);
        FileWrapper userRoot = user.getUserRoot().join();

        String filename = "somedata.txt";
        // write empty file
        byte[] data = new byte[1025*1024*5];
        userRoot.uploadOrReplaceFile(filename, new AsyncReader.ArrayBacked(data), data.length, user.network,
                        crypto, l -> {}).join();

        Path filePath = PathUtil.get(username, filename);

        boolean writable = false;
        Optional<LocalDateTime> expiry = Optional.of(LocalDateTime.now().plusDays(1));
        Optional<Integer> maxRetrievals = Optional.of(2);

        String userPassword = "youre-terrible-muriel";
        LinkProperties linkProps = user.createSecretLink(filePath.toString(), writable, expiry, maxRetrievals, userPassword).join();
        SecretLink link = linkProps.toLink(userRoot.owner());

        EncryptedCapability retrieved = network.getSecretLink(link).join();
        AbsoluteCapability cap = retrieved.decryptFromPassword(link.labelString(), link.linkPassword + userPassword, crypto).join();
        FileWrapper resolvedFile = network.getFile(cap, username).join().get();
        Assert.assertTrue(resolvedFile.isWritable() == writable);

        SharedWithState sharingState = user.getDirectorySharingState(Paths.get(username)).join();
        Assert.assertTrue(sharingState.hasLink(filename));
        LinkProperties props = sharingState.get(filename).links.stream().findFirst().get();

        // try changing the password
        String newPass = "different";
        user.updateSecretLink(filePath.toString(), new LinkProperties(props.label, props.linkPassword, newPass, writable, props.maxRetrievals, props.expiry, props.existing)).join();

        UserContext.fromSecretLinkV2(link.toLink(), () -> Futures.of(newPass), network, crypto).join();
        try {
            UserContext.fromSecretLinkV2(link.toLink(), () -> Futures.of(newPass), network, crypto).join();
            throw new RuntimeException("Shouldn't get here");
        } catch (IllegalStateException expected) {}

        user.deleteSecretLink(link.label, filePath, writable).join();

        try {
            network.getSecretLink(link).join();
            throw new RuntimeException("Shouldn't get here");
        } catch (IllegalStateException expected) {}

        // now a writable secret link
        String wpass = "modifyme";
        LinkProperties writeLink = user.createSecretLink(filePath.toString(), true, Optional.empty(), Optional.empty(), wpass).join();
        UserContext writableContext = UserContext.fromSecretLinkV2(writeLink.toLinkString(userRoot.owner()), () -> Futures.of(wpass), network, crypto).join();
        FileWrapper wf = writableContext.getByPath(filePath).join().get();
        Assert.assertTrue(wf.isWritable());
    }
}
