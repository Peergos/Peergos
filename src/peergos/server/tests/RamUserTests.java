package peergos.server.tests;

import com.eatthepath.otp.*;
import org.junit.*;
import org.junit.runner.*;
import org.junit.runners.*;
import peergos.server.*;
import peergos.server.cli.CLI;
import peergos.server.crypto.hash.ScryptJava;
import peergos.server.tests.util.*;
import peergos.server.util.*;
import peergos.shared.*;
import peergos.shared.io.ipfs.Cid;
import peergos.shared.login.mfa.*;
import peergos.shared.social.*;
import peergos.shared.storage.BlockCache;
import peergos.shared.storage.UnauthedCachingStorage;
import peergos.shared.storage.auth.BatId;
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
import java.util.function.Supplier;
import java.util.stream.*;

@RunWith(Parameterized.class)
public class RamUserTests extends UserTests {
    private static Args args = buildArgs().with("useIPFS", "false");
    private final NetworkAccess alternativeNet1, alternativeNet2;

    public RamUserTests(NetworkAccess network, UserService service, NetworkAccess alternativeNet1, NetworkAccess alternativeNet2) {
        super(network, service);
        this.alternativeNet1 = alternativeNet1;
        this.alternativeNet2 = alternativeNet2;
    }

    @Parameterized.Parameters()
    public static Collection<Object[]> parameters() throws Exception {
        UserService service = Main.PKI_INIT.main(args).localApi;
        // use actual http messager
        ServerMessager.HTTP serverMessager = new ServerMessager.HTTP(new JavaPoster(new URI("http://localhost:" + args.getArg("port")).toURL(), false));
        NetworkAccess network = NetworkAccess.buildBuffered(service.storage, service.bats, service.coreNode, service.account, service.mutable,
                        5_000, service.social, service.controller, service.usage, serverMessager, crypto.hasher, Arrays.asList("peergos"), false)
                .withStorage(s -> new UnauthedCachingStorage(s, new NoopCache(), crypto.hasher));
        NetworkAccess altNetwork1 = NetworkAccess.buildBuffered(service.storage, service.bats, service.coreNode, service.account, service.mutable,
                        0, service.social, service.controller, service.usage, serverMessager, crypto.hasher, Arrays.asList("peergos"), false)
                .withStorage(s -> new UnauthedCachingStorage(s, new NoopCache(), crypto.hasher));
        NetworkAccess altNetwork2 = NetworkAccess.buildBuffered(service.storage, service.bats, service.coreNode, service.account, service.mutable,
                        0, service.social, service.controller, service.usage, serverMessager, crypto.hasher, Arrays.asList("peergos"), false)
                .withStorage(s -> new UnauthedCachingStorage(s, new NoopCache(), crypto.hasher));
        return Arrays.asList(new Object[][] {
                {network, service, altNetwork1, altNetwork2}
        });
    }

    public static class NoopCache implements BlockCache {
        @Override
        public CompletableFuture<Boolean> put(Cid hash, byte[] data) {
            return CompletableFuture.supplyAsync(() -> true);
        }

        @Override
        public CompletableFuture<Optional<byte[]>> get(Cid hash) {
            return CompletableFuture.supplyAsync(Optional::empty);
        }

        @Override
        public boolean hasBlock(Cid hash) {
            return false;
        }

        @Override
        public CompletableFuture<Boolean> clear() {
            return Futures.of(true);
        }

        @Override
        public long getMaxSize() {
            return 0;
        }

        @Override
        public void setMaxSize(long maxSizeBytes) {

        }
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


    @Test
    public void copybug() throws Exception {
        String username = generateUsername();
        String password = "test01";
        UserContext context = PeergosNetworkUtils.ensureSignedUp(username, password, network.clear(), crypto);
        Path remoteRelativeDir = Paths.get("pandoc","assets");
        String filename = "data.dat";
        CLI.ProgressCreator progressCreator = (a, b, c) -> x -> {};
        long fileSize1 = 31*1024*1024;
        AsyncReader.ArrayBacked data1 = new AsyncReader.ArrayBacked(new byte[(int)fileSize1]);

        FileWrapper.FileUploadProperties props1 = new FileWrapper.FileUploadProperties(filename, () -> data1,
                (int) (fileSize1 >> 32), (int) fileSize1, Optional.empty(), Optional.empty(), true, true,
                progressCreator.create(remoteRelativeDir, filename, Math.max(4096, fileSize1)));

        String filename2 = "index.html";
        long fileSize2 = 4294;
        AsyncReader.ArrayBacked data2 = new AsyncReader.ArrayBacked(new byte[(int)fileSize2]);

        FileWrapper.FileUploadProperties props2 = new FileWrapper.FileUploadProperties(filename2, () -> data2,
                (int) (fileSize2 >> 32), (int) fileSize2, Optional.empty(), Optional.empty(), true, true,
                progressCreator.create(remoteRelativeDir, filename2, Math.max(4096, fileSize2)));


        List<FileWrapper.FileUploadProperties> files = new ArrayList<>();
        files.add(props2);
        files.add(props1);
        FileWrapper.FolderUploadProperties folderProps = new FileWrapper.FolderUploadProperties(convert(remoteRelativeDir), files);
        List<FileWrapper.FolderUploadProperties> folders = new ArrayList<>();
        folders.add(folderProps);
        context.getUserRoot().join().uploadSubtree(folders.stream(), context.mirrorBatId(), context.network, crypto, context.getTransactionService(), x -> Futures.of(true), () -> true).join();

        String appName = "pandoc";
        String installAppFromFolder = context.username + "/" + appName;
        peergos.shared.user.App.init(context, appName).join();
        boolean result = copyAssetsFolder(context, appName, installAppFromFolder).join();
        Assert.assertTrue(result);
    }
    private static CompletableFuture<Boolean> copyAssetsFolder(UserContext context, String appName, String installAppFromFolder) {
        CompletableFuture<Boolean> future = peergos.shared.util.Futures.incomplete();
        String appFolderPath = "/" + context.username + "/.apps/" + appName;
        context.getByPath(installAppFromFolder + "/assets").thenApply(srcAssetsDirOpt -> {
            if (srcAssetsDirOpt.isPresent()) {
                context.getByPath(appFolderPath).thenApply(destAppDirOpt -> {
                    srcAssetsDirOpt.get().copyTo(destAppDirOpt.get(), context)
                            .thenApply(res -> {
                                future.complete(true);
                                return true;
                            }).exceptionally(throwable -> {
                                System.out.println("unable to copy app assets. error: " + throwable.getMessage());
                                future.complete(false);
                                return false;
                            });
                    return null;
                });
            }else {
                future.complete(false);
            }
            return null;
        });
        return future;
    }

    private static List<String> convert(Path p) {
        List<String> res = new ArrayList<>();
        for (int i=0; i < p.getNameCount(); i++)
            res.add(p.getName(i).toString());
        return res;
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
    public void concurrentModification() throws Exception {
        String username = generateUsername();
        String password = "password";
        UserContext context1 = PeergosNetworkUtils.ensureSignedUp(username, password, alternativeNet1, crypto);
        Optional<BatId> mirrorBat = context1.mirrorBatId();
        UserContext context2 = PeergosNetworkUtils.ensureSignedUp(username, password, alternativeNet2, crypto);

        context1.getUserRoot().join().mkdir("dir1", context1.network, false, mirrorBat, crypto).join();
        context1.getUserRoot().join().mkdir("dir2", context1.network, false, mirrorBat, crypto).join();

        FileWrapper dir1 = context1.getByPath(Paths.get(username, "dir1")).join().get();

        FileWrapper dir2 = context2.getByPath(Paths.get(username, "dir2")).join().get();

        int KB = 1024;
        dir1.uploadOrReplaceFile("file1", AsyncReader.build(new byte[KB]), KB, context1.network,
                crypto, () -> false, x -> {}).join();

        dir2.uploadOrReplaceFile("file2", AsyncReader.build(new byte[KB]), KB, context1.network,
                crypto, () -> false, x -> {}).join();

        FileWrapper file1 = context1.getByPath(Paths.get(username, "dir1", "file1")).join().get();
        FileWrapper file2 = context2.getByPath(Paths.get(username, "dir2", "file2")).join().get();

        int MB = 1024 * 1024;
        CompletableFuture<FileWrapper> future = CompletableFuture.supplyAsync(() -> file1.overwriteFile(AsyncReader.build(new byte[MB]), MB, context1.network, crypto, x -> {Threads.sleep(1_000);}).join());
        FileWrapper f2 = file2.overwriteFile(AsyncReader.build(new byte[MB]), MB, context2.network, crypto, x -> {Threads.sleep(1_000);}).join();
        FileWrapper f1 = future.join();

        FileWrapper updatedFile1 = context1.getByPath(username + "/dir1/file1", f1.version).join().get();
        FileWrapper updatedFile2 = context2.getByPath(username + "/dir2/file2", f2.version).join().get();
        Assert.assertEquals(MB, updatedFile1.getSize());
        Assert.assertEquals(MB, updatedFile2.getSize());
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
                .uploadOrReplaceFile("index.html", AsyncReader.build(data), data.length, network, crypto, () -> false, x -> {}).join();
        ProfilePaths.setWebRoot(context, "/" + username + "/" + dirName).join();
        ProfilePaths.publishWebroot(context).join();

        // start a gateway
        Args a = Args.parse(new String[]{
                "-peergos-url", "http://localhost:" + args.getInt("port"),
                "-port", "9002",
                "-listen-host", "localhost",
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
        long initialUsage = context.getSpaceUsage(false).join();
        int size = 100*1024*1024;
        byte[] data = new byte[size];
        int bufferSize = 20*1024*1024;
        int throwAtIndex = size / bufferSize / 2 * bufferSize; // needs to be a multiple of the buffer size
        AsyncReader thrower = new ThrowingStream(data, throwAtIndex);
        FileWrapper txnDir = context.getByPath(Paths.get(username, UserContext.TRANSACTIONS_DIR_NAME)).join().get();
        TransactionService txns = new NonClosingTransactionService(network, crypto, txnDir);
        try {
            FileWrapper.FileUploadProperties fileUpload = new FileWrapper.FileUploadProperties("somefile", () -> thrower, 0, size, Optional.empty(), Optional.empty(), false, false, x -> {});
            FileWrapper.FolderUploadProperties dirUploads = new FileWrapper.FolderUploadProperties(Arrays.asList(username), Arrays.asList(fileUpload));
            userRoot.uploadSubtree(Stream.of(dirUploads), context.mirrorBatId(), network, crypto, txns, f -> Futures.of(false), () -> true).join();
        } catch (Exception e) {}
        try {
            context.getUserRoot().join().uploadFileJS("anotherfile", thrower, 0, size, false,
                    context.mirrorBatId(), network, crypto, x -> {}, txns, f -> Futures.of(false)).join();
        } catch (Exception e) {}
        long usageAfterFail = context.getSpaceUsage(false).join();
        while (usageAfterFail <= throwAtIndex) { // give server a chance to recalculate usage
            Thread.sleep(2_000);
            usageAfterFail = context.getSpaceUsage(false).join();
        }
        Assert.assertTrue(usageAfterFail > throwAtIndex);
        context.cleanPartialUploads(t -> true).join();
        long usageAfterCleanup = context.getSpaceUsage(false).join();
        while (usageAfterCleanup >= initialUsage + 5000) {
            Thread.sleep(1_000);
            usageAfterCleanup = context.getSpaceUsage(false).join();
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

        long initialUsage = context.getSpaceUsage(false).join();
        int size = 100*1024*1024;
        byte[] data = new byte[size];
        int bufferSize = 20*1024*1024;
        int throwAtIndex = size / bufferSize / 2 * bufferSize; // needs to be a multiple of the buffer size
        AsyncReader thrower = new ThrowingStream(data, throwAtIndex);
        FileWrapper txnDir = context.getByPath(Paths.get(username, UserContext.TRANSACTIONS_DIR_NAME)).join().get();
        TransactionService txns = new NonClosingTransactionService(network, crypto, txnDir);
        try {
            FileWrapper.FileUploadProperties fileUpload = new FileWrapper.FileUploadProperties("somefile", () -> thrower, 0, size, Optional.empty(), Optional.empty(), false, false, x -> {});
            FileWrapper.FolderUploadProperties dirUploads = new FileWrapper.FolderUploadProperties(Arrays.asList(subdir), Arrays.asList(fileUpload));
            userRoot.uploadSubtree(Stream.of(dirUploads), context.mirrorBatId(), network, crypto, txns, f -> Futures.of(false), () -> true).join();
        } catch (Exception e) {}
        long usageAfterFail = context.getSpaceUsage(false).join();
        if (usageAfterFail <= throwAtIndex) { // give server a chance to recalculate usage
            Thread.sleep(2_000);
            usageAfterFail = context.getSpaceUsage(false).join();
        }
        Assert.assertTrue(usageAfterFail > throwAtIndex);

        // delete the new writing space
        FileWrapper sub = context.getByPath(subdirPath).join().get();

        sub.remove(context.getUserRoot().get(), subdirPath, context).join();
        long usageAfterDelete = context.getSpaceUsage(false).join();
        while (usageAfterDelete >= throwAtIndex) { // give server a chance to recalculate usage
            Thread.sleep(2_000);
            usageAfterDelete = context.getSpaceUsage(false).join();
        }
        Assert.assertTrue(usageAfterDelete < initialUsage);

        // clean the partial upload
        context.cleanPartialUploads(t -> true).join();
        long usageAfterCleanup = context.getSpaceUsage(false).join();
        Assert.assertTrue(usageAfterCleanup < usageAfterDelete);
    }

    @Test
    public void moveToDescendant() throws Exception {
        String username = generateUsername();
        String password = "terriblepassword";
        UserContext context = PeergosNetworkUtils.ensureSignedUp(username, password, network, crypto);
        FileWrapper userRoot = context.getUserRoot().join();
        String parentName = "parent";
        userRoot.mkdir(parentName, network, false, context.mirrorBatId(), crypto).join();
        Path parentPath = Paths.get(username, parentName);
        FileWrapper parent = context.getByPath(parentPath).join().get();
        String childName = "child";
        parent.mkdir(childName, network, false, context.mirrorBatId(), crypto).join();
        parent = context.getByPath(parentPath).join().get();
        FileWrapper child = context.getByPath(parentPath.resolve(childName)).join().get();
        try {
            parent.moveTo(child, parent, parentPath, context, () -> Futures.of(true)).join();
            throw new RuntimeException("Should fail before here");
        } catch (CompletionException e) {}
        context.getByPath(parentPath.resolve(childName)).join().get();
    }

    @Test
    public void duplicateNameCutAndPaste() throws Exception {
        String username = generateUsername();
        String password = "terriblepassword";
        UserContext context = PeergosNetworkUtils.ensureSignedUp(username, password, network, crypto);
        FileWrapper userRoot = context.getUserRoot().join();
        String targetName = "target";
        userRoot.mkdir(targetName, network, false, context.mirrorBatId(), crypto).join();
        Path targetPath = Paths.get(username, targetName);
        FileWrapper target = context.getByPath(targetPath).join().get();
        byte[] orig = "Some words are here".getBytes();
        String filename = "test.txt";
        target.uploadOrReplaceFile(filename, AsyncReader.build(orig), orig.length, network, crypto, () -> false, x -> {}).join();

        String sourceName = "source";
        context.getUserRoot().join().mkdir(sourceName, network, false, context.mirrorBatId(), crypto).join();
        FileWrapper source = context.getByPath(Paths.get(username, sourceName)).join().get();
        byte[] different = "hi".getBytes();
        source.uploadOrReplaceFile(filename, AsyncReader.build(different), different.length, network, crypto, () -> false, x -> {}).join();

        FileWrapper toMove = context.getByPath(Paths.get(username, sourceName, filename)).join().get();
        try {
            target = context.getByPath(targetPath).join().get();
            FileWrapper parent = context.getByPath(Paths.get(username, sourceName)).join().get();
            toMove.moveTo(target, parent, Paths.get(username, sourceName, filename), context, () -> Futures.of(true)).join();
            throw new RuntimeException("Should fail before here");
        } catch (CompletionException e) {}
        target = context.getByPath(targetPath).join().get();
        Set<FileWrapper> kids = target.getChildren(crypto.hasher, network).join();
        Assert.assertTrue(kids.size() == 1);
        byte[] data = Serialize.readFully(kids.stream().findFirst().get(), crypto, network).join();
        Assert.assertArrayEquals(data, orig);
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
                context.network, context.crypto, () -> false, l -> {}).join();

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
                context.network, context.crypto, () -> false, l -> {}).join();

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

        String username = generateUsername();
        String password = "test01";
        UserContext context = PeergosNetworkUtils.ensureSignedUp(username, password, network, crypto);
        FileWrapper userRoot = context.getUserRoot().join();

        String filename = "sintel.mp4";
        Random random = new Random(666);
        byte[] fileData = new byte[14621544];
        random.nextBytes(fileData);
        FileWrapper userRoot2 = userRoot.uploadOrReplaceFile(filename, new AsyncReader.ArrayBacked(fileData), fileData.length,
                context.network, context.crypto, () -> false, l -> {}).join();

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

        String username = generateUsername();
        String password = "test01";
        UserContext context = PeergosNetworkUtils.ensureSignedUp(username, password, network, crypto);
        FileWrapper userRoot = context.getUserRoot().join();

        String filename = "sintel.mp4";
        Random random = new Random(666);
        byte[] fileData = new byte[14621544];
        random.nextBytes(fileData);

        FileWrapper userRoot2 = userRoot.uploadOrReplaceFile(filename, new AsyncReader.ArrayBacked(fileData), fileData.length,
                context.network, context.crypto, () -> false, l -> {}).join();

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
                crypto, () -> false, l -> {}).join();

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
        boolean writable = false;
        String filename = "somedata.txt";
        Path filePath = null;
        SecretLink link = null;

        for (int i=0; i < 3; i++) {
            FileWrapper userRoot = user.getUserRoot().join();

            String subdir1 = "subdir" + i;
            userRoot.mkdir(subdir1, network, false, user.mirrorBatId(), crypto).join();

            // write empty file
            byte[] data = new byte[1025 * 1024 * 5];
            user.getByPath(Paths.get(username, subdir1)).join().get().uploadOrReplaceFile(filename, new AsyncReader.ArrayBacked(data), data.length, user.network,
                    crypto, () -> false, l -> {}).join();

            filePath = PathUtil.get(username, subdir1, filename);

            Optional<LocalDateTime> expiry = Optional.of(LocalDateTime.now().plusDays(1));
            Optional<Integer> maxRetrievals = Optional.of(2);

            String userPassword = "youre-terrible-muriel";
            LinkProperties linkProps = user.createSecretLink(filePath.toString(), writable, expiry, maxRetrievals, userPassword, false).join();
            link = linkProps.toLink(userRoot.owner());

            EncryptedCapability retrieved = network.getSecretLink(link).join();
            AbsoluteCapability cap = retrieved.decryptFromPassword(link.labelString(), link.linkPassword + userPassword, crypto).join();
            FileWrapper resolvedFile = network.getFile(cap, username).join().get();
            Assert.assertTrue(resolvedFile.isWritable() == writable);
        }

        SharedWithState sharingState = user.getDirectorySharingState(filePath.getParent()).join();
        Assert.assertTrue(sharingState.hasLink(filename));
        LinkProperties props = sharingState.get(filename).links.stream().findFirst().get();

        // try changing the password
        String newPass = "different";
        user.updateSecretLink(filePath.toString(), new LinkProperties(props.label, props.linkPassword, newPass, writable, props.maxRetrievals, props.expiry, props.open, props.existing)).join();

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
        LinkProperties writeLink = user.createSecretLink(filePath.toString(), true, Optional.empty(), Optional.empty(), wpass, false).join();
        UserContext writableContext = UserContext.fromSecretLinkV2(writeLink.toLinkString(user.signer.publicKeyHash), () -> Futures.of(wpass), network, crypto).join();
        FileWrapper wf = writableContext.getByPath(filePath).join().get();
        Assert.assertTrue(wf.isWritable());

        // test creating a secret link from a fresh login
        LinkProperties dirlink = PeergosNetworkUtils.ensureSignedUp(username, password, network, crypto)
                .createSecretLink(filePath.getParent().toString(), writable, Optional.empty(), Optional.empty(), "", false).join();
    }
}
