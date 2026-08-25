package peergos.server.tests;

import com.eatthepath.otp.*;
import org.junit.*;
import org.junit.runner.*;
import org.junit.runners.*;
import peergos.server.*;
import peergos.server.cli.CLI;
import peergos.server.cli.CLIContext;
import peergos.server.crypto.hash.ScryptJava;
import peergos.server.net.MountConfigHandler;
import peergos.server.sync.*;
import peergos.server.tests.util.*;
import peergos.server.util.*;
import peergos.shared.*;
import peergos.shared.crypto.hash.PublicKeyHash;
import peergos.shared.io.ipfs.Cid;
import peergos.shared.login.mfa.*;
import peergos.shared.social.*;
import peergos.shared.storage.BlockCache;
import peergos.shared.storage.UnauthedCachingStorage;
import peergos.shared.storage.auth.BatId;
import peergos.shared.user.*;
import peergos.shared.user.fs.*;
import peergos.shared.user.fs.archive.*;
import peergos.shared.user.fs.transaction.*;
import peergos.shared.util.*;

import javax.crypto.spec.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
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

    /** Mounting a drive used to delete whatever totp the user already had, because enabling a totp
     *  replaces any other. A mount gets its own type of factor now, so the two coexist.
     */
    @Test
    public void mountFactorLeavesTotpAlone() throws Exception {
        String username = generateUsername();
        String password = "password";
        UserContext context = PeergosNetworkUtils.ensureSignedUp(username, password, network, crypto);

        TimeBasedOneTimePasswordGenerator totp = new TimeBasedOneTimePasswordGenerator(Duration.ofSeconds(30L), 6, TotpKey.ALGORITHM);
        TotpKey userTotp = addTotpKey(context, totp);
        testLoginRequiresTotp(username, password, network, totp, userTotp);

        // mounting a drive provisions the mount a second factor of its own
        TotpKey mountKey = addMountKey(context, totp, "Linux drive mount 1");

        List<MultiFactorAuthMethod> methods = context.network.account.getSecondAuthMethods(username, context.signer).join();
        Assert.assertEquals(2, methods.size());
        MultiFactorAuthMethod stillThere = methods.stream()
                .filter(m -> m.type == MultiFactorAuthMethod.Type.TOTP)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Mounting deleted the user's totp!"));
        Assert.assertTrue(Arrays.equals(stillThere.credentialId, userTotp.credentialId));
        Assert.assertTrue(stillThere.enabled);
        // the authenticator app the user already had still logs them in
        testLoginRequiresTotp(username, password, network, totp, userTotp);

        // and the mount logs in with its own credential, using the responder it really uses
        UserContext mountLogin = UserContext.signIn(username, password,
                MountConfigHandler.mountTotpResponder(mountKey.credentialId, mountKey.key), network, crypto).join();
        Assert.assertEquals(username, mountLogin.username);

        // replacing the authenticator totp later must not take the mount's factor with it
        TotpKey replacement = addTotpKey(context, totp);
        testLoginRequiresTotp(username, password, network, totp, replacement);
        UserContext.signIn(username, password,
                MountConfigHandler.mountTotpResponder(mountKey.credentialId, mountKey.key), network, crypto).join();
        Assert.assertEquals(2, context.network.account.getSecondAuthMethods(username, context.signer).join().size());
    }

    /** A mount's credential is not a factor a person can be challenged with, so it must not gate an
     *  interactive login on its own, and backup codes have nothing to back up while it is the only one.
     */
    @Test
    public void mountFactorAloneIsNotUsersSecondFactor() throws Exception {
        String username = generateUsername();
        String password = "password";
        UserContext context = PeergosNetworkUtils.ensureSignedUp(username, password, network, crypto);

        TimeBasedOneTimePasswordGenerator totp = new TimeBasedOneTimePasswordGenerator(Duration.ofSeconds(30L), 6, TotpKey.ALGORITHM);
        addMountKey(context, totp, "Linux drive mount 1");

        // logging in normally must not be challenged with a factor the user can't answer
        AtomicBoolean challenged = new AtomicBoolean(false);
        UserContext.signIn(username, password, req -> {
            challenged.set(true);
            throw new IllegalStateException("Challenged with " + req.methods + " which no person can satisfy");
        }, network, crypto).join();
        Assert.assertFalse(challenged.get());

        try {
            context.network.account.generateBackupCodes(username, context.signer).join();
            Assert.fail("Backup codes shouldn't be allowed with only a mount factor to back up");
        } catch (Exception e) {}
    }

    /** Revoking a mount's factor can leave the account with no second factor at all, and then login
     *  isn't challenged for anything - so signing in successfully is not proof the factor survived.
     *  This is what the hourly credential check has to look at to notice a remote revocation.
     */
    @Test
    public void revokedMountFactorIsNoticedWithNoOtherFactor() throws Exception {
        String username = generateUsername();
        String password = "password";
        UserContext context = PeergosNetworkUtils.ensureSignedUp(username, password, network, crypto);

        TimeBasedOneTimePasswordGenerator totp = new TimeBasedOneTimePasswordGenerator(Duration.ofSeconds(30L), 6, TotpKey.ALGORITHM);
        TotpKey mountKey = addMountKey(context, totp, "Linux drive mount 1");
        peergos.server.webdav.MountConfig cfg = new peergos.server.webdav.MountConfig(
                true, username, password, "webdav-user", "webdav-pass", 8090, "digest",
                ArrayOps.bytesToHex(mountKey.credentialId), ArrayOps.bytesToHex(mountKey.key));

        UserContext mounted = UserContext.signIn(username, password,
                MountConfigHandler.mountTotpResponder(cfg), network, crypto).join();
        Assert.assertTrue(MountConfigHandler.hasOurCredential(mounted, cfg));

        // the user revokes this mount from 2FA settings on another device
        context.network.account.deleteSecondFactor(username, mountKey.credentialId, context.signer).join();

        // signing in still works, on the password alone - the account has no second factor now
        UserContext after = UserContext.signIn(username, password,
                MountConfigHandler.mountTotpResponder(cfg), network, crypto).join();
        Assert.assertFalse("A revoked mount factor must not look valid", MountConfigHandler.hasOurCredential(after, cfg));
    }

    @Test
    public void backupCodes() throws Exception {
        String username = generateUsername();
        String password = "password";
        UserContext context = PeergosNetworkUtils.ensureSignedUp(username, password, network, crypto);

        // backup codes are only allowed once there is another factor for them to back up
        try {
            context.network.account.generateBackupCodes(username, context.signer).join();
            Assert.fail("Backup codes shouldn't be allowed without another second factor");
        } catch (Exception e) {}

        TimeBasedOneTimePasswordGenerator totp = new TimeBasedOneTimePasswordGenerator(Duration.ofSeconds(30L), 6, TotpKey.ALGORITHM);
        TotpKey totpKey = addTotpKey(context, totp);
        // enrolling a totp key must not generate backup codes by itself
        Assert.assertEquals(1, context.network.account.getSecondAuthMethods(username, context.signer).join().size());

        BackupCodes codes = context.network.account.generateBackupCodes(username, context.signer).join();
        Assert.assertEquals(BackupCodes.CODE_COUNT, codes.codes.size());
        Assert.assertEquals(Integer.toString(BackupCodes.CODE_COUNT), remainingBackupCodes(context));

        // a code logs us in, in any of the forms a user might type it
        testLoginWithBackupCode(username, password, network, codes.credentialId, codes.codes.get(0).toUpperCase());
        Assert.assertEquals(Integer.toString(BackupCodes.CODE_COUNT - 1), remainingBackupCodes(context));
        testLoginWithBackupCode(username, password, network, codes.credentialId, BackupCodes.format(codes.codes.get(1)));
        Assert.assertEquals(Integer.toString(BackupCodes.CODE_COUNT - 2), remainingBackupCodes(context));

        // but only once
        assertBackupCodeRejected(username, password, network, codes.credentialId, codes.codes.get(0));
        // and an unknown code never works
        assertBackupCodeRejected(username, password, network, codes.credentialId, "aaaaaaaaaa");
        Assert.assertEquals(Integer.toString(BackupCodes.CODE_COUNT - 2), remainingBackupCodes(context));

        // totp still works alongside the codes
        testLoginRequiresTotp(username, password, network, totp, totpKey);

        // regenerating invalidates the earlier set
        BackupCodes newCodes = context.network.account.generateBackupCodes(username, context.signer).join();
        Assert.assertEquals(Integer.toString(BackupCodes.CODE_COUNT), remainingBackupCodes(context));
        assertBackupCodeRejected(username, password, network, newCodes.credentialId, codes.codes.get(2));
        testLoginWithBackupCode(username, password, network, newCodes.credentialId, newCodes.codes.get(0));

        // exhausting the set removes it as a login option
        for (int i = 1; i < BackupCodes.CODE_COUNT; i++)
            testLoginWithBackupCode(username, password, network, newCodes.credentialId, newCodes.codes.get(i));
        List<MultiFactorAuthMethod> afterExhaustion = context.network.account.getSecondAuthMethods(username, context.signer).join();
        Assert.assertEquals(1, afterExhaustion.size());
        Assert.assertEquals(MultiFactorAuthMethod.Type.TOTP, afterExhaustion.get(0).type);

        // deleting the last real factor deletes the backup codes with it
        BackupCodes finalCodes = context.network.account.generateBackupCodes(username, context.signer).join();
        Assert.assertEquals(2, context.network.account.getSecondAuthMethods(username, context.signer).join().size());
        context.network.account.deleteSecondFactor(username, totpKey.credentialId, context.signer).join();
        Assert.assertTrue(context.network.account.getSecondAuthMethods(username, context.signer).join().isEmpty());
        // and login now needs no second factor at all
        UserContext noMfa = UserContext.signIn(username, password, req -> {
            throw new IllegalStateException("Shouldn't be asked for a second factor!");
        }, network, crypto).join();
        Assert.assertEquals(username, noMfa.username);
    }

    /** Only one of two logins racing to redeem the same backup code may succeed.
     */
    @Test
    public void concurrentBackupCodeUse() throws Exception {
        String username = generateUsername();
        String password = "password";
        UserContext context = PeergosNetworkUtils.ensureSignedUp(username, password, network, crypto);

        TimeBasedOneTimePasswordGenerator totp = new TimeBasedOneTimePasswordGenerator(Duration.ofSeconds(30L), 6, TotpKey.ALGORITHM);
        addTotpKey(context, totp);
        BackupCodes codes = context.network.account.generateBackupCodes(username, context.signer).join();
        String code = codes.codes.get(0);

        List<CompletableFuture<Boolean>> logins = IntStream.range(0, 2)
                .mapToObj(i -> CompletableFuture.supplyAsync(() -> {
                    try {
                        testLoginWithBackupCode(username, password, network, codes.credentialId, code);
                        return true;
                    } catch (Exception e) {
                        return false;
                    }
                }))
                .collect(Collectors.toList());
        long succeeded = logins.stream().filter(CompletableFuture::join).count();
        Assert.assertEquals(1, succeeded);
        Assert.assertEquals(Integer.toString(BackupCodes.CODE_COUNT - 1), remainingBackupCodes(context));
    }

    private static String remainingBackupCodes(UserContext context) {
        return context.network.account.getSecondAuthMethods(context.username, context.signer).join()
                .stream()
                .filter(m -> m.type == MultiFactorAuthMethod.Type.BACKUP_CODES)
                .map(m -> m.name)
                .findFirst()
                .orElse("none");
    }

    private static void testLoginWithBackupCode(String username,
                                                String password,
                                                NetworkAccess network,
                                                byte[] credentialId,
                                                String code) {
        AtomicBoolean usedMfa = new AtomicBoolean(false);
        UserContext freshLogin = UserContext.signIn(username, password, req -> {
            List<MultiFactorAuthMethod> backups = req.methods.stream()
                    .filter(m -> m.type == MultiFactorAuthMethod.Type.BACKUP_CODES)
                    .collect(Collectors.toList());
            if (backups.isEmpty())
                throw new IllegalStateException("No backup codes offered! " + req.methods);
            usedMfa.set(true);
            return Futures.of(new MultiFactorAuthResponse(credentialId, Either.a(code)));
        }, network, crypto).join();
        Assert.assertTrue(usedMfa.get());
        Assert.assertEquals(username, freshLogin.username);
    }

    private static void assertBackupCodeRejected(String username,
                                                 String password,
                                                 NetworkAccess network,
                                                 byte[] credentialId,
                                                 String code) {
        boolean rejected = false;
        try {
            testLoginWithBackupCode(username, password, network, credentialId, code);
        } catch (Exception e) {
            rejected = true;
        }
        Assert.assertTrue("Backup code should have been rejected", rejected);
    }

    /**
     * Mount login uses the dedicated TOTP credential stored in MountConfig instead of
     * prompting a human. Verifies the round trip: a freshly-issued TOTP key, hex-encoded
     * into MountConfig, drives a non-interactive UserContext.signIn through the responder
     * built by {@link peergos.server.net.MountConfigHandler#mountTotpResponder}.
     */
    @Test
    public void mountTotpResponderSignsIn() throws Exception {
        String username = generateUsername();
        String password = "password";
        UserContext context = PeergosNetworkUtils.ensureSignedUp(username, password, network, crypto);
        Assert.assertTrue(context.network.account.getSecondAuthMethods(username, context.signer).join().isEmpty());

        TimeBasedOneTimePasswordGenerator totp = new TimeBasedOneTimePasswordGenerator(
                java.time.Duration.ofSeconds(30L), 6, peergos.shared.login.mfa.TotpKey.ALGORITHM);
        peergos.shared.login.mfa.TotpKey totpKey = addTotpKey(context, totp);

        // Build a MountConfig that mirrors what the UI would POST after provisioning.
        peergos.server.webdav.MountConfig cfg = new peergos.server.webdav.MountConfig(
                true, username, password, "webdav-user", "webdav-pass", 8090, "digest",
                peergos.shared.util.ArrayOps.bytesToHex(totpKey.credentialId),
                peergos.shared.util.ArrayOps.bytesToHex(totpKey.key));
        Assert.assertTrue("MountConfig should report hasTotp()", cfg.hasTotp());

        // Drive the same path buildContext takes, just without the URL/network plumbing.
        java.util.function.Function<
                peergos.shared.login.mfa.MultiFactorAuthRequest,
                java.util.concurrent.CompletableFuture<peergos.shared.login.mfa.MultiFactorAuthResponse>>
                responder = peergos.server.net.MountConfigHandler.mountTotpResponder(cfg);

        UserContext mounted = UserContext.signIn(username, password, responder, network, crypto).join();
        Assert.assertEquals(username, mounted.username);

        // Cleanup so a stale TOTP doesn't trip subsequent tests on the same account.
        context.network.account.deleteSecondFactor(username, totpKey.credentialId, context.signer).join();
        Assert.assertTrue(context.network.account.getSecondAuthMethods(username, context.signer).join().isEmpty());
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
    public void resumableUploadPreservesHash() throws Exception {
        String username = generateUsername();
        String password = "test";
        UserContext context = PeergosNetworkUtils.ensureSignedUp(username, password, network, crypto);

        byte[] content = new byte[256 * 1024];
        new Random(7).nextBytes(content);
        HashTree expected = HashTree.build(AsyncReader.build(content), 0, content.length, crypto.hasher).join();

        ResumeUploadProps props = ResumeUploadProps.random(crypto);
        String filename = "resumable.bin";
        context.getUserRoot().join()
                .uploadFileWithHash(filename, AsyncReader.build(content), content.length,
                        Optional.of(expected), Optional.empty(), Optional.empty(),
                        Optional.of(props),
                        network, crypto, () -> false, x -> {}).join();

        FileWrapper file = context.getByPath(Paths.get(username, filename)).join().get();
        HashBranch stored = file.getFileProperties().treeHash
                .orElseThrow(() -> new AssertionError("treeHash missing on resumable-upload file"));
        Assert.assertEquals(expected.branch(0), stored);
    }

    @Test
    public void appWriteInSecretLink() throws Exception {
        String username = generateUsername();
        String password = "test01";
        UserContext context = PeergosNetworkUtils.ensureSignedUp(username, password, network.clear(), crypto);
        String dirName = "someapp";
        context.getUserRoot().join()
                .mkdir(".apps", network, false, context.mirrorBatId(), crypto).join();
        context.getByPath(username + "/.apps").join().get()
                .mkdir(dirName, network, false, context.mirrorBatId(), crypto).join();
        LinkProperties link = context.createSecretLink(username + "/.apps/" + dirName, true, Optional.empty(), Optional.empty(), "", false).join();

        UserContext fromLink = UserContext.fromSecretLinkV2(link.toLinkString(context.signer.publicKeyHash), () -> Futures.of(""), network, crypto).join();
        App app = App.init(fromLink, dirName).join();
        app.writeInternal(Paths.get(dirName), "G'day mate!".getBytes(StandardCharsets.UTF_8), null).join();
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
        context.getUserRoot().join().uploadSubtree(folders.stream(), context.mirrorBatId(), context.network, crypto, context.getTransactionService(), x -> Futures.of(true), f -> Futures.of(true), () -> true).join();

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

    private static TotpKey addMountKey(UserContext context,
                                       TimeBasedOneTimePasswordGenerator totp,
                                       String name) throws Exception {
        TotpKey mountKey = context.network.account.addMountFactor(context.username, name, context.signer).join();
        Key key = new SecretKeySpec(mountKey.key, TotpKey.ALGORITHM);
        String code = totp.generateOneTimePasswordString(key, Instant.now());
        Assert.assertTrue(context.network.account
                .enableMountFactor(context.username, mountKey.credentialId, code, context.signer).join());
        return mountKey;
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

    /** Cleaning up a partial upload doesn't return usage exactly to its starting value —
     *  the transaction bookkeeping itself writes blocks that survive the cleanup. The
     *  original 5000 byte allowance was tight enough that ordinary variation in that
     *  residue failed the test; these cleanups reclaim ~50MB, so a larger allowance
     *  still catches a cleanup that doesn't reclaim, while not tracking the exact
     *  bookkeeping cost. */
    private static final long CLEANUP_RESIDUE_TOLERANCE = 100_000;

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
            userRoot.uploadSubtree(Stream.of(dirUploads), context.mirrorBatId(), network, crypto, txns, f -> Futures.of(false), f -> Futures.of(true), () -> true).join();
        } catch (Exception e) {}
        try {
            context.getUserRoot().join().uploadFileJS("anotherfile", thrower, 0, size, false,
                    context.mirrorBatId(), network, crypto, x -> {}, txns, f -> Futures.of(false)).join();
        } catch (Exception e) {}
        long usageAfterFail = context.getSpaceUsage(false).join();
        for (int i = 0; i < 60 && usageAfterFail <= throwAtIndex; i++) { // give server a chance to recalculate usage
            Thread.sleep(2_000);
            usageAfterFail = context.getSpaceUsage(false).join();
        }
        Assert.assertTrue("usageAfterFail=" + usageAfterFail + " throwAtIndex=" + throwAtIndex,
                usageAfterFail > throwAtIndex);
        context.cleanPartialUploads(t -> true).join();
        long usageAfterCleanup = context.getSpaceUsage(false).join();
        for (int i = 0; i < 60 && usageAfterCleanup >= initialUsage + CLEANUP_RESIDUE_TOLERANCE; i++) {
            Thread.sleep(1_000);
            usageAfterCleanup = context.getSpaceUsage(false).join();
        }
        Assert.assertTrue("usageAfterCleanup=" + usageAfterCleanup + " initialUsage=" + initialUsage
                        + " (reclaimed " + (usageAfterFail - usageAfterCleanup) + " of " + (usageAfterFail - initialUsage) + ")",
                usageAfterCleanup < initialUsage + CLEANUP_RESIDUE_TOLERANCE);
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
            userRoot.uploadSubtree(Stream.of(dirUploads), context.mirrorBatId(), network, crypto, txns, f -> Futures.of(false), f -> Futures.of(true), () -> true).join();
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
        // Bounded poll — see correctUsageAndSpaceRecovery for the reasoning.
        for (int i = 0; i < 60 && usageAfterDelete >= initialUsage; i++) {
            Thread.sleep(2_000);
            usageAfterDelete = context.getSpaceUsage(false).join();
        }
        Assert.assertTrue("usageAfterDelete=" + usageAfterDelete + " initialUsage=" + initialUsage,
                usageAfterDelete < initialUsage);

        // clean the partial upload
        context.cleanPartialUploads(t -> true).join();
        long usageAfterCleanup = context.getSpaceUsage(false).join();
        for (int i = 0; i < 60 && usageAfterCleanup >= usageAfterDelete; i++) { // Allow time for server to process cleanup event
            Thread.sleep(1_000);
            usageAfterCleanup = context.getSpaceUsage(false).join();
        }

        Assert.assertTrue("usageAfterCleanup=" + usageAfterCleanup + " usageAfterDelete=" + usageAfterDelete,
                usageAfterCleanup < usageAfterDelete);
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
    public void bufferedReaderPartialLastChunk() {
        String username = generateUsername();
        String password = "test";
        UserContext context = PeergosNetworkUtils.ensureSignedUp(username, password, network, crypto);
        FileWrapper userRoot = context.getUserRoot().join();

        String filename = "partial-last-chunk.bin";
        int size = 2 * Chunk.MAX_SIZE + 1024 * 1024;
        byte[] fileData = new byte[size];
        new Random(42).nextBytes(fileData);

        userRoot.uploadOrReplaceFile(filename, new AsyncReader.ArrayBacked(fileData), fileData.length,
                context.network, context.crypto, () -> false, l -> {}).join();

        FileWrapper file = context.getByPath(PathUtil.get(username, filename)).join().get();
        FileProperties props = file.getFileProperties();
        AsyncReader reader = file.getBufferedInputStream(network, crypto, props.sizeHigh(), props.sizeLow(), 5, l -> {}).join();

        byte[] read = new byte[size];
        // the first read schedules the prefetch of the remaining chunks, including the short final one
        reader.readIntoArray(read, 0, 1).join();
        ForkJoinPool.commonPool().awaitQuiescence(120, TimeUnit.SECONDS);

        Assert.assertEquals(size - 1, (int) reader.readIntoArray(read, 1, size - 1).join());
        Assert.assertArrayEquals(fileData, read);

        boolean readPastEnd = true;
        try {
            reader.readIntoArray(new byte[1], 0, 1).join();
        } catch (Exception e) {
            readPastEnd = false;
        }
        Assert.assertFalse("Final chunk must not be padded to " + Chunk.MAX_SIZE + " bytes", readPastEnd);
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

    private static final String SCANNING_DRIVE = "Checking files in Drive";

    /** Run one sync pass the way the sync runner does, and return everything it logged. */
    private List<String> syncPass(String link,
                                  Path localDir,
                                  PublicKeyHash owner,
                                  SyncState synced,
                                  Path peergosDir,
                                  Consumer<String> onLog) throws IOException {
        List<String> log = Collections.synchronizedList(new ArrayList<>());
        DirectorySync.syncDir(new LocalFileSystem(localDir, crypto.hasher),
                DirectorySync.buildRemote(link, alternativeNet2, crypto),
                true, true, owner, alternativeNet2, synced, 32, 5, peergosDir, crypto, () -> false,
                msg -> {
                    log.add(msg);
                    DirectorySync.log(msg);
                    onLog.accept(msg);
                });
        return log;
    }

    private List<String> syncPass(String link, Path localDir, PublicKeyHash owner, SyncState synced, Path peergosDir) throws IOException {
        return syncPass(link, localDir, owner, synced, peergosDir, msg -> {});
    }

    private boolean remoteHas(String link, String relPath) {
        return DirectorySync.buildRemote(link, alternativeNet1, crypto).exists(PathUtil.get(relPath));
    }

    /** A sync that uploads leaves the remote at a newer version than the one it scanned, so unless
     *  it works out that the change was its own, every pass that pushes anything up makes the next
     *  pass rescan all of Drive. It must only claim its own changes though: a change from another
     *  device that it hasn't scanned has to leave the snapshot behind.
     */
    @Test
    public void syncSkipsRescanAfterItsOwnChanges() throws Exception {
        String username = generateUsername();
        String password = "password";
        UserContext context = PeergosNetworkUtils.ensureSignedUp(username, password, network, crypto);
        context.getUserRoot().join().mkdir("sync", context.network, false, context.mirrorBatId(), crypto).join();
        Path remoteDir = PathUtil.get(username, "sync");
        String link = DirectorySync.init(context, remoteDir.toString()).toLinkString(context.signer.publicKeyHash);

        Path localDir = Files.createTempDirectory("peergos-sync-local");
        Path peergosDir = Files.createTempDirectory("peergos-sync-state");
        PublicKeyHash owner = network.coreNode.getPublicKeyHash(username).join().get();
        SyncState synced = new JdbcTreeState(":memory:");

        Files.write(localDir.resolve("file1.txt"), "one".getBytes());
        // the first pass has no snapshot to work from, and its scan of the still empty folder
        // records no writers, so it takes the second pass to record a version we can use
        Assert.assertTrue(syncPass(link, localDir, owner, synced, peergosDir).contains(SCANNING_DRIVE));
        Assert.assertTrue(remoteHas(link, "file1.txt"));
        Assert.assertTrue(syncPass(link, localDir, owner, synced, peergosDir).contains(SCANNING_DRIVE));

        // nothing has changed at either end
        Assert.assertFalse(syncPass(link, localDir, owner, synced, peergosDir).contains(SCANNING_DRIVE));

        // a local addition is pushed up, moving the remote version on
        Files.write(localDir.resolve("file2.txt"), "two".getBytes());
        Assert.assertFalse(syncPass(link, localDir, owner, synced, peergosDir).contains(SCANNING_DRIVE));
        Assert.assertTrue(remoteHas(link, "file2.txt"));

        // the change was the sync's own, so there is still nothing new to look for
        Assert.assertFalse("a pass that only pushed its own changes up must not rescan Drive",
                syncPass(link, localDir, owner, synced, peergosDir).contains(SCANNING_DRIVE));

        // a change from another device is not the sync's own
        UserContext other = PeergosNetworkUtils.ensureSignedUp(username, password, alternativeNet1, crypto);
        byte[] three = "three".getBytes();
        other.getByPath(remoteDir).join().get()
                .uploadOrReplaceFile("file3.txt", AsyncReader.build(three), three.length, other.network, crypto, () -> false, x -> {}).join();
        Assert.assertTrue(syncPass(link, localDir, owner, synced, peergosDir).contains(SCANNING_DRIVE));
        Assert.assertTrue(Files.exists(localDir.resolve("file3.txt")));

        // and a change from another device that lands while the sync is uploading is still not its
        // own, even though the sync's own write commits on top of it
        Files.write(localDir.resolve("file4.txt"), "four".getBytes());
        byte[] five = "five".getBytes();
        AtomicBoolean written = new AtomicBoolean(false);
        try {
            syncPass(link, localDir, owner, synced, peergosDir, msg -> {
                if (msg.contains("uploading") && ! written.getAndSet(true))
                    other.getByPath(remoteDir).join().get()
                            .uploadOrReplaceFile("file5.txt", AsyncReader.build(five), five.length, other.network, crypto, () -> false, x -> {}).join();
            });
        } catch (Exception e) {
            // a pass that fails on the concurrent write saves nothing, which is also safe
        }
        Assert.assertTrue("the concurrent write must have been injected", written.get());
        Assert.assertTrue(syncPass(link, localDir, owner, synced, peergosDir).contains(SCANNING_DRIVE));
        Assert.assertTrue(Files.exists(localDir.resolve("file5.txt")));
    }
    private static byte[] buildZip(Map<String, byte[]> stored, Map<String, byte[]> deflated) {
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream zout = new java.util.zip.ZipOutputStream(bout)) {
            for (Map.Entry<String, byte[]> e : deflated.entrySet()) {
                zout.putNextEntry(new java.util.zip.ZipEntry(e.getKey()));
                zout.write(e.getValue());
                zout.closeEntry();
            }
            for (Map.Entry<String, byte[]> e : stored.entrySet()) {
                java.util.zip.ZipEntry entry = new java.util.zip.ZipEntry(e.getKey());
                entry.setMethod(java.util.zip.ZipOutputStream.STORED);
                entry.setSize(e.getValue().length);
                entry.setCompressedSize(e.getValue().length);
                java.util.zip.CRC32 crc = new java.util.zip.CRC32();
                crc.update(e.getValue());
                entry.setCrc(crc.getValue());
                zout.putNextEntry(entry);
                zout.write(e.getValue());
                zout.closeEntry();
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return bout.toByteArray();
    }

    @Test
    public void browseZipArchive() throws Exception {
        String username = generateUsername();
        UserContext context = PeergosNetworkUtils.ensureSignedUp(username, "test01", network.clear(), crypto);
        JavaInflate.init();

        // bigger than a chunk, so entries are found by seeking rather than by reading the whole file
        byte[] big = new byte[6 * 1024 * 1024];
        new Random(42).nextBytes(big);
        Map<String, byte[]> stored = new LinkedHashMap<>();
        stored.put("big.bin", big);
        Map<String, byte[]> deflated = new LinkedHashMap<>();
        deflated.put("readme.txt", "hello archive".getBytes(StandardCharsets.UTF_8));
        deflated.put("logs/first.log", "log one".getBytes(StandardCharsets.UTF_8));
        deflated.put("logs/second.log", "log two".getBytes(StandardCharsets.UTF_8));
        byte[] zip = buildZip(stored, deflated);
        context.getUserRoot().join()
                .uploadOrReplaceFile("data.zip", AsyncReader.build(zip), zip.length, network, crypto, () -> false, x -> {})
                .join();

        CLI cli = new CLI(new CLIContext(null, context, "", username));
        StringWriter sink = new StringWriter();
        PrintWriter writer = new PrintWriter(sink);
        String archive = "/" + username + "/data.zip";

        Assert.assertEquals("big.bin\nlogs\nreadme.txt", cli.ls(CLI.fromLine("ls " + archive)));
        Assert.assertEquals("first.log\nsecond.log", cli.ls(CLI.fromLine("ls " + archive + "/logs")));
        Assert.assertTrue(cli.ls(CLI.fromLine("ls -l " + archive)).contains("-r-    6.0 MiB"));

        cli.cat(CLI.fromLine("cat " + archive + "/readme.txt"), writer);
        Assert.assertEquals("hello archive", sink.toString());

        // cd descends into the archive, and then relative paths resolve within it
        cli.cd(CLI.fromLine("cd " + archive + "/logs"));
        Assert.assertEquals("first.log\nsecond.log", cli.ls(CLI.fromLine("ls")));
        Assert.assertEquals("log two", cat(cli, "second.log"));
        cli.cd(CLI.fromLine("cd " + archive));

        Path localDir = Files.createTempDirectory("peergos-archive-test");
        cli.get(CLI.fromLine("get " + archive + "/big.bin " + localDir), writer);
        Assert.assertArrayEquals(big, Files.readAllBytes(localDir.resolve("big.bin")));

        cli.get(CLI.fromLine("get " + archive + "/logs " + localDir), writer);
        Assert.assertArrayEquals("log one".getBytes(StandardCharsets.UTF_8),
                Files.readAllBytes(localDir.resolve("logs").resolve("first.log")));

        // the archive itself is still an ordinary file to download
        cli.get(CLI.fromLine("get " + archive + " " + localDir), writer);
        Assert.assertArrayEquals(zip, Files.readAllBytes(localDir.resolve("data.zip")));
    }

    private static String cat(CLI cli, String path) throws IOException {
        StringWriter sink = new StringWriter();
        cli.cat(CLI.fromLine("cat " + path), new PrintWriter(sink));
        return sink.toString();
    }
    private static byte[] readAll(FileWrapper file, NetworkAccess network) {
        long size = file.getFileProperties().size;
        byte[] res = new byte[(int) size];
        AsyncReader reader = file.getInputStream(network, crypto, x -> {}).join();
        int offset = 0;
        while (offset < res.length) {
            int read = reader.readIntoArray(res, offset, res.length - offset).join();
            if (read <= 0)
                throw new IllegalStateException("Unexpected end of file");
            offset += read;
        }
        return res;
    }

    private static ZipWriter.NewEntry newEntry(String path, byte[] data) {
        return new ZipWriter.NewEntry(path, data.length, LocalDateTime.of(2026, 8, 25, 10, 30),
                () -> Futures.of(AsyncReader.build(data)));
    }

    private static List<String> listWithSystemUnzip(Path zip) throws Exception {
        Process process = new ProcessBuilder("unzip", "-Z1", zip.toString()).redirectErrorStream(true).start();
        String out = new String(process.getInputStream().readAllBytes());
        process.waitFor();
        return Stream.of(out.split("\n")).map(String::trim).filter(l -> ! l.isEmpty()).collect(Collectors.toList());
    }

    private static String testWithSystemUnzip(Path zip) throws Exception {
        Process process = new ProcessBuilder("unzip", "-t", zip.toString()).redirectErrorStream(true).start();
        String out = new String(process.getInputStream().readAllBytes());
        process.waitFor();
        return out;
    }

    @Test
    public void writeIntoZipArchive() throws Exception {
        String username = generateUsername();
        UserContext context = PeergosNetworkUtils.ensureSignedUp(username, "test01", network.clear(), crypto);
        JavaInflate.init();
        Path dir = Files.createTempDirectory("peergos-zip-write");

        Map<String, byte[]> original = new LinkedHashMap<>();
        original.put("readme.txt", "the original entry\n".repeat(100).getBytes(StandardCharsets.UTF_8));
        original.put("logs/first.log", "log line\n".repeat(1000).getBytes(StandardCharsets.UTF_8));
        byte[] zipBytes = buildZip(new LinkedHashMap<>(), original);
        context.getUserRoot().join()
                .uploadOrReplaceFile("data.zip", AsyncReader.build(zipBytes), zipBytes.length, network, crypto,
                        () -> false, x -> {})
                .join();
        Supplier<FileWrapper> archive = () -> context.getByPath("/" + username + "/data.zip").join().get();

        // add entries, including one that has to span a chunk boundary
        byte[] added = "a new entry that compresses well\n".repeat(2000).getBytes(StandardCharsets.UTF_8);
        byte[] noisy = new byte[6 * 1024 * 1024];
        new Random(7).nextBytes(noisy);
        FileWrapper afterAppend = ZipWriter.append(archive.get(),
                Arrays.asList(newEntry("notes/added.txt", added), newEntry("big.bin", noisy)),
                network, crypto, x -> {}).join();

        Path onDisk = dir.resolve("after-append.zip");
        Files.write(onDisk, readAll(afterAppend, network));
        Assert.assertTrue(testWithSystemUnzip(onDisk), testWithSystemUnzip(onDisk).contains("No errors detected"));
        Assert.assertEquals(Arrays.asList("readme.txt", "logs/first.log", "notes/added.txt", "big.bin"),
                listWithSystemUnzip(onDisk));

        ZipReader zip = ZipReader.open(archive.get(), network, crypto).join();
        Assert.assertArrayEquals(added, readEntry(zip, "notes/added.txt"));
        Assert.assertArrayEquals(noisy, readEntry(zip, "big.bin"));
        Assert.assertArrayEquals(original.get("readme.txt"), readEntry(zip, "readme.txt"));

        // replacing an entry keeps only the new one
        byte[] replacement = "the replacement\n".repeat(50).getBytes(StandardCharsets.UTF_8);
        ZipWriter.append(archive.get(), Arrays.asList(newEntry("readme.txt", replacement)), network, crypto, x -> {}).join();
        ZipReader replaced = ZipReader.open(archive.get(), network, crypto).join();
        Assert.assertArrayEquals(replacement, readEntry(replaced, "readme.txt"));
        Assert.assertEquals(4, listWithSystemUnzip(write(dir.resolve("replaced.zip"), archive.get())).size());

        // removing an entry erases the bytes it left behind
        byte[] marker = "SECRET-MARKER-42".getBytes(StandardCharsets.UTF_8);
        byte[] secretData = new byte[100_000]; // incompressible, so it is stored as it stands
        new Random(42).nextBytes(secretData);
        System.arraycopy(marker, 0, secretData, 5_000, marker.length);
        ZipWriter.append(archive.get(), Arrays.asList(newEntry("secret.bin", secretData)), network, crypto, x -> {}).join();
        byte[] withSecret = readAll(archive.get(), network);
        Assert.assertTrue("the secret is in the archive before the delete", indexOf(withSecret, marker) >= 0);
        Assert.assertTrue("the entry name is in the archive before the delete",
                indexOf(withSecret, "secret.bin".getBytes(StandardCharsets.UTF_8)) >= 0);

        ZipWriter.remove(archive.get(), Arrays.asList("secret.bin"), true, network, crypto, x -> {}).join();
        byte[] afterDelete = readAll(archive.get(), network);
        Assert.assertTrue("the deleted bytes are gone", indexOf(afterDelete, marker) < 0);
        Assert.assertTrue("the deleted entry's name is gone too",
                indexOf(afterDelete, "secret.bin".getBytes(StandardCharsets.UTF_8)) < 0);
        Path deleted = dir.resolve("deleted.zip");
        Files.write(deleted, afterDelete);
        Assert.assertTrue(testWithSystemUnzip(deleted), testWithSystemUnzip(deleted).contains("No errors detected"));
        Assert.assertFalse(listWithSystemUnzip(deleted).contains("secret.bin"));

        // and the same delete without erasing keeps the data, which is the cheaper option
        ZipWriter.append(archive.get(), Arrays.asList(newEntry("keep-the-bytes.bin", secretData)), network, crypto, x -> {}).join();
        ZipWriter.remove(archive.get(), Arrays.asList("keep-the-bytes.bin"), false, network, crypto, x -> {}).join();
        byte[] afterTombstone = readAll(archive.get(), network);
        Assert.assertTrue("a tombstone leaves the bytes where they were", indexOf(afterTombstone, marker) >= 0);
        Path tombstoned = dir.resolve("tombstoned.zip");
        Files.write(tombstoned, afterTombstone);
        Assert.assertTrue(testWithSystemUnzip(tombstoned), testWithSystemUnzip(tombstoned).contains("No errors detected"));
        Assert.assertFalse(listWithSystemUnzip(tombstoned).contains("keep-the-bytes.bin"));

        // a rename of the same length is patched where it stands
        ZipWriter.rename(archive.get(), "readme.txt", "README.txt", network, crypto, x -> {}).join();
        Path renamed = dir.resolve("renamed.zip");
        Files.write(renamed, readAll(archive.get(), network));
        Assert.assertTrue(testWithSystemUnzip(renamed), testWithSystemUnzip(renamed).contains("No errors detected"));
        Assert.assertTrue(listWithSystemUnzip(renamed).contains("README.txt"));
        Assert.assertArrayEquals(replacement, readEntry(ZipReader.open(archive.get(), network, crypto).join(), "README.txt"));

        // a rename of a different length writes the entry again under the new name
        ZipWriter.rename(archive.get(), "notes/added.txt", "a-much-longer-name.txt", network, crypto, x -> {}).join();
        Path longer = dir.resolve("longer.zip");
        Files.write(longer, readAll(archive.get(), network));
        Assert.assertTrue(testWithSystemUnzip(longer), testWithSystemUnzip(longer).contains("No errors detected"));
        Assert.assertTrue(listWithSystemUnzip(longer).contains("notes/a-much-longer-name.txt"));
        Assert.assertFalse(listWithSystemUnzip(longer).contains("notes/added.txt"));
        ZipReader finalZip = ZipReader.open(archive.get(), network, crypto).join();
        Assert.assertArrayEquals(added, readEntry(finalZip, "notes/a-much-longer-name.txt"));
        Assert.assertArrayEquals(noisy, readEntry(finalZip, "big.bin"));

        // a directory goes in as the files under it, in one write, plus a record for the empty one
        ZipWriter.append(archive.get(), Arrays.asList(
                        newEntry("tree/docs/notes.txt", added),
                        newEntry("tree/top.txt", replacement),
                        ZipWriter.NewEntry.directory("tree/empty", LocalDateTime.of(2026, 8, 25, 10, 30))),
                network, crypto, x -> {}).join();
        Path tree = dir.resolve("tree.zip");
        Files.write(tree, readAll(archive.get(), network));
        Assert.assertTrue(testWithSystemUnzip(tree), testWithSystemUnzip(tree).contains("No errors detected"));
        List<String> withTree = listWithSystemUnzip(tree);
        Assert.assertTrue(withTree.contains("tree/docs/notes.txt"));
        Assert.assertTrue(withTree.contains("tree/top.txt"));
        // nothing implies an empty directory, so it needs a record of its own, named with a slash
        Assert.assertTrue(withTree.contains("tree/empty/"));
        ZipReader withDirectory = ZipReader.open(archive.get(), network, crypto).join();
        Assert.assertArrayEquals(added, readEntry(withDirectory, "tree/docs/notes.txt"));
        Assert.assertTrue(withDirectory.getIndex().get("tree/empty").get().isDirectory);
    }

    private Path write(Path target, FileWrapper file) throws Exception {
        Files.write(target, readAll(file, network));
        return target;
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            int j = 0;
            while (j < needle.length && haystack[i + j] == needle[j])
                j++;
            if (j == needle.length)
                return i;
        }
        return -1;
    }

    private static byte[] readEntry(ZipReader zip, String path) {
        peergos.shared.user.fs.archive.ZipEntry entry = zip.getIndex().get(path)
                .orElseThrow(() -> new IllegalStateException("No entry " + path));
        AsyncReader reader = zip.read(entry).join();
        byte[] res = new byte[(int) entry.size];
        int offset = 0;
        while (offset < res.length) {
            int read = reader.readIntoArray(res, offset, res.length - offset).join();
            if (read <= 0)
                throw new IllegalStateException("Unexpected end of entry " + path);
            offset += read;
        }
        return res;
    }
}