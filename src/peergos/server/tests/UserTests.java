package peergos.server.tests;
import java.time.*;
import java.util.concurrent.atomic.*;
import java.util.logging.*;

import peergos.server.crypto.asymmetric.curve25519.*;
import peergos.server.crypto.hash.*;
import peergos.server.crypto.random.*;
import peergos.server.crypto.symmetric.*;
import peergos.server.messages.*;
import peergos.server.tests.util.*;
import peergos.server.user.*;
import peergos.server.util.*;

import org.junit.*;
import static org.junit.Assert.*;

import peergos.shared.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.crypto.symmetric.*;
import peergos.server.*;
import peergos.shared.io.ipfs.Cid;
import peergos.shared.io.ipfs.Multihash;
import peergos.shared.login.mfa.*;
import peergos.shared.mutable.*;
import peergos.shared.storage.*;
import peergos.shared.storage.auth.*;
import peergos.shared.storage.controller.*;
import peergos.shared.user.*;
import peergos.shared.user.fs.*;
import peergos.shared.user.fs.cryptree.*;
import peergos.shared.user.fs.transaction.*;
import peergos.shared.util.*;
import peergos.shared.util.Exceptions;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

public abstract class UserTests {
	private static final Logger LOG = Logging.LOG();
    static {
        ThumbnailGenerator.setInstance(new JavaImageThumbnailer());
    }

    public static int RANDOM_SEED = 666;
    protected final NetworkAccess network;
    protected final UserService service;
    protected static final Crypto crypto = Main.initCrypto();

    private static Random random = new Random(RANDOM_SEED);

    public UserTests(NetworkAccess network, UserService service) {
        this.network = network;
        this.service = service;
    }

    public abstract Args getArgs();

    public static Args buildArgs() {
        try {
            Path peergosDir = Files.createTempDirectory("peergos");
            int port = TestPorts.getPort();
            int proxyPort = TestPorts.getPort();
            int gatewayPort = TestPorts.getPort();
            int ipfsApiPort = TestPorts.getPort();
            int ipfsGatewayPort = TestPorts.getPort();
            int ipfsSwarmPort = TestPorts.getPort();
            return Args.parse(new String[]{
                    "-port", Integer.toString(port),
                    "-proxy-target", "/ip4/127.0.0.1/tcp/" + proxyPort,
                    "-gateway-port", Integer.toString(gatewayPort),
                    "-ipfs-api-address", "/ip4/127.0.0.1/tcp/" + ipfsApiPort,
                    "-ipfs-gateway-address", "/ip4/127.0.0.1/tcp/" + ipfsGatewayPort,
                    "-ipfs-swarm-port", Integer.toString(ipfsSwarmPort),
                    "-ipfs.metrics.port", Integer.toString(TestPorts.getPort()),
                    "-admin-usernames", "peergos",
                    "-logToConsole", "true",
                    "-enable-gc", "true",
                    "-gc.period.millis", "60000",
                    "max-users", "10000",
                    "max-daily-signups", "20000",
                    Main.PEERGOS_PATH, peergosDir.toString(),
                    "peergos.password", "testpassword",
                    "pki.keygen.password", "testpkipassword",
                    "pki.keyfile.password", "testpassword",
            });
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static void deleteFiles(File f) {
        if (! f.exists())
            return;
        if (f.isDirectory()) {
            for (File child : f.listFiles()) {
                deleteFiles(child);
            }
        }
        f.delete();
    }

    public void gc() {
        service.gc.collect(e -> Futures.of(true));
    }

    protected String generateUsername() {
        return "test" + Math.abs(random.nextInt() % 1_000_000);
    }

    private static CompletableFuture<FileWrapper> uploadFileSection(FileWrapper parent,
                                                                    String filename,
                                                                    AsyncReader fileData,
                                                                    long startIndex,
                                                                    long endIndex,
                                                                    NetworkAccess network,
                                                                    Crypto crypto,
                                                                    ProgressConsumer<Long> monitor) {
        return parent.uploadFileSection(filename, fileData, false, startIndex, endIndex, Optional.empty(),
                true, network, crypto, monitor, crypto.random.randomBytes(32), Optional.empty(), Optional.of(Bat.random(crypto.random)),
                parent.mirrorBatId());
    }

    @Test
    public void serializationSizesSmall() {
        SigningKeyPair signer = SigningKeyPair.random(crypto.random, crypto.signer);
        byte[] rawSignPub = signer.publicSigningKey.serialize(); // 36
        byte[] rawSignSecret = signer.secretSigningKey.serialize(); // 68
        byte[] rawSignBoth = signer.serialize(); // 105
        BoxingKeyPair boxer = BoxingKeyPair.random(crypto.random, crypto.boxer);
        byte[] rawBoxPub = boxer.publicBoxingKey.serialize(); // 36
        byte[] rawBoxSecret = boxer.secretBoxingKey.serialize(); // 36
        byte[] rawBoxBoth = boxer.serialize(); // 73
        SymmetricKey sym = SymmetricKey.random();
        byte[] rawSym = sym.serialize(); // 37
        Assert.assertTrue("Serialization overhead isn't too much", rawSignPub.length <= 32 + 4);
        Assert.assertTrue("Serialization overhead isn't too much", rawSignSecret.length <= 64 + 4);
        Assert.assertTrue("Serialization overhead isn't too much", rawSignBoth.length <= 96 + 9);
        Assert.assertTrue("Serialization overhead isn't too much", rawBoxPub.length <= 32 + 4);
        Assert.assertTrue("Serialization overhead isn't too much", rawBoxSecret.length <= 32 + 4);
        Assert.assertTrue("Serialization overhead isn't too much", rawBoxBoth.length <= 64 + 9);
        Assert.assertTrue("Serialization overhead isn't too much", rawSym.length <= 33 + 4);
    }

    @Test
    public void differentLoginTypes() throws Exception {
        String username = generateUsername();
        String password = "letmein";
        String extraSalt = ArrayOps.bytesToHex(crypto.random.randomBytes(32));
        List<ScryptGenerator> params = Arrays.asList(
                new ScryptGenerator(17, 8, 1, 96, extraSalt),
                new ScryptGenerator(17, 8, 1, 64, extraSalt),
                new ScryptGenerator(18, 8, 1, 96, extraSalt),
                new ScryptGenerator(19, 8, 1, 96, extraSalt),
                new ScryptGenerator(17, 9, 1, 96, extraSalt)
        );
        for (ScryptGenerator p: params) {
            long t1 = System.currentTimeMillis();
            UserUtil.generateUser(username, password, crypto.hasher, crypto.symmetricProvider, crypto.random, crypto.signer, crypto.boxer, p).get();
            long t2 = System.currentTimeMillis();
            LOG.info("User gen took " + (t2 - t1) + " mS");
            System.gc();
        }
    }

    @Test
    public void javascriptCompatible() {
        String username = generateUsername();
        String password = "test01";

        SafeRandomJava random = new SafeRandomJava();
        UserUtil.generateUser(username, password, new ScryptJava(), new Salsa20Poly1305Java(),
                random, new Ed25519Java(), new Curve25519Java(), SecretGenerationAlgorithm.getLegacy(random)).thenAccept(userWithRoot -> {
		    PublicSigningKey expected = PublicSigningKey.fromString("7HvEWP6yd1UD8rOorfFrieJ8S7yC8+l3VisV9kXNiHmI7Eav7+3GTRSVBRCymItrzebUUoCi39M6rdgeOU9sXXFD");
		    if (! expected.equals(userWithRoot.getUser().publicSigningKey))
		        throw new IllegalStateException("Generated user different from the Javascript! \n"+userWithRoot.getUser().publicSigningKey + " != \n"+expected);
        });
    }

    @Test
    public void randomSignup() {
        String username = generateUsername();
        String password = "password";
        UserContext context = PeergosNetworkUtils.ensureSignedUp(username, password, network, crypto);
        InstanceAdmin.VersionInfo version = network.instanceAdmin.getVersionInfo().join();
        Assert.assertTrue(! version.version.isBefore(Version.parse("0.0.0")));

        FileWrapper userRoot = context.getUserRoot().join();
        Assert.assertTrue("owner uses identity multihash", userRoot.getPointer().capability.owner.isIdentity());
        Assert.assertTrue("signer uses identity multihash", userRoot.getPointer().capability.writer.isIdentity());
        Assert.assertTrue("user root does not have a retrievable parent",
                ! userRoot.retrieveParent(network).join().isPresent());

        String someUrlFragment = "Somedata";
        UserContext.EncryptedURL encryptedURL = context.encryptURL(someUrlFragment);
        String decryptedUrl = context.decryptURL(encryptedURL.base64Ciphertext, encryptedURL.base64Nonce);
        Assert.assertTrue(decryptedUrl.equalsIgnoreCase(someUrlFragment));
    }

    @Test
    public void singleSignUp() {
        // This is to ensure a user can't accidentally sign up rather than login and overwrite all their data
        String username = generateUsername();
        String password = "password";
        PeergosNetworkUtils.ensureSignedUp(username, password, network, crypto);
        CompletableFuture<UserContext> secondSignup = UserContext.signUp(username, password, "", network, crypto);

        Assert.assertTrue("Second sign up fails", secondSignup.isCompletedExceptionally());
    }

    public static CompletableFuture<MultiFactorAuthResponse> noMfa(MultiFactorAuthRequest req) {
        throw new IllegalStateException("Unsupported!");
    }

    @Test
    public void errorLoggingInToDeletedAccont() {
        String username = generateUsername();
        String password = "password";
        UserContext context = PeergosNetworkUtils.ensureSignedUp(username, password, network, crypto);
        context.deleteAccount(password, UserTests::noMfa).join();

        try {
            PeergosNetworkUtils.ensureSignedUp(username, password, network, crypto);
        } catch (Exception e) {
            if (! e.getMessage().contains("User has been deleted"))
                throw new RuntimeException("Incorrect error message");
        }
    }

    @Test
    public void expiredSignin() {
        String username = generateUsername();
        String password = "password";
        // set username claim to an expiry in the past
        UserContext context = UserContext.signUpGeneral(username, password, "", Optional.empty(), id -> {},
                Optional.empty(), LocalDate.now().minusDays(1),
                network, crypto, SecretGenerationAlgorithm.getDefault(crypto.random), t -> {}).join();

        LocalDate expiry = context.getUsernameClaimExpiry().join();
        Assert.assertTrue(expiry.isBefore(LocalDate.now()));

        context.ensureUsernameClaimRenewed().join();
        UserContext context2 = PeergosNetworkUtils.ensureSignedUp(username, password, network, crypto);
        LocalDate expiry2 = context2.getUsernameClaimExpiry().join();
        Assert.assertTrue(expiry2.isAfter(LocalDate.now()));
    }

    @Test
    public void expiredSigninAfterPasswordChange() {
        String username = generateUsername();
        String password = "password";
        UserContext context = UserContext.signUpGeneral(username, password, "", Optional.empty(), id -> {},
                Optional.empty(), LocalDate.now().minusDays(2),
                network, crypto, SecretGenerationAlgorithm.getDefault(crypto.random), x -> {}).join();
        String newPassword = "G'day mate!";

        // change password and set username claim to an expiry in the past
        SecretGenerationAlgorithm alg = context.getKeyGenAlgorithm().join();
        SecretGenerationAlgorithm newAlg = SecretGenerationAlgorithm.withNewSalt(alg, crypto.random);
        context = context.changePassword(password, newPassword, alg, newAlg, LocalDate.now().minusDays(1), UserTests::noMfa).join();

        LocalDate expiry = context.getUsernameClaimExpiry().join();
        Assert.assertTrue(expiry.isBefore(LocalDate.now()));

        context.ensureUsernameClaimRenewed().join();
        UserContext context2 = PeergosNetworkUtils.ensureSignedUp(username, newPassword, network, crypto);
        LocalDate expiry2 = context2.getUsernameClaimExpiry().join();
        Assert.assertTrue(expiry2.isAfter(LocalDate.now()));
    }

    @Test
    public void noRepeatedPassword() {
        // This is to ensure a user can't change their password to a previously used password
        String username = generateUsername();
        String password1 = "pass1";
        String password2 = "pass2";
        UserContext context1 = PeergosNetworkUtils.ensureSignedUp(username, password1, network, crypto);
        UserContext context2 = context1.changePassword(password1, password2, UserTests::noMfa).join();
        try {
            context2.changePassword(password2, password1, UserTests::noMfa).join();
        } catch (Throwable t) {
            Assert.assertTrue(t.getMessage().contains("You must change to a different password."));
        }
    }

    @Test
    public void duplicateSignUp() {
        String username = generateUsername();
        String password1 = "password1";
        String password2 = "password2";
        PeergosNetworkUtils.ensureSignedUp(username, password1, network, crypto);
        try {
            UserContext.signUp(username, password2, "", network, crypto).get();
        } catch (Exception e) {
            if (! e.getMessage().contains("User already exists"))
                Assert.fail("Incorrect error message");
        }
    }

    @Test
    public void repeatedSignUp() {
        String username = generateUsername();
        String password = "password";
        PeergosNetworkUtils.ensureSignedUp(username, password, network, crypto);
        try {
            UserContext.signUp(username, password, "", network, crypto).get();
        } catch (Exception e) {
            if (!Exceptions.getRootCause(e).getMessage().contains("User already exists"))
                Assert.fail("Incorrect error message");
        }
    }

    @Test
    public void changePassword() throws Exception {
        String username = generateUsername();
        String password = "password";
        UserContext userContext = PeergosNetworkUtils.ensureSignedUp(username, password, network, crypto);
        Pair<PublicKeyHash, PublicBoxingKey> keyPairs = userContext.getPublicKeys(username).join().get();
        PublicBoxingKey initialBoxer = keyPairs.right;
        PublicKeyHash initialIdentity = keyPairs.left;
        String newPassword = "newPassword";
        UserContext updated = userContext.changePassword(password, newPassword, UserTests::noMfa).join();
        MultiUserTests.checkUserValidity(network, username);

        Pair<PublicKeyHash, PublicBoxingKey> updatedPairs = updated.getPublicKeys(username).join().get();
        PublicBoxingKey newBoxer = updatedPairs.right;
        PublicKeyHash newIdentity = updatedPairs.left;
        Assert.assertTrue(newBoxer.equals(initialBoxer));
        Assert.assertTrue(newIdentity.equals(initialIdentity));
        UserContext changedPassword = PeergosNetworkUtils.ensureSignedUp(username, newPassword, network, crypto);

        // change it again
        String password3 = "pass3";
        changedPassword.changePassword(newPassword, password3, UserTests::noMfa).get();
        MultiUserTests.checkUserValidity(network, username);
        PeergosNetworkUtils.ensureSignedUp(username, password3, network, crypto);
    }

    @Test
    public void legacyLogin() throws Exception {
        String username = generateUsername();
        String password = "password";
        UserContext userContext = UserContext.signUpGeneral(username, password, "", Optional.empty(), id -> {},
                Optional.empty(), LocalDate.now().plusMonths(2),
                network, crypto, SecretGenerationAlgorithm.getLegacy(crypto.random), x -> {}).join();
        SecretGenerationAlgorithm originalAlg = WriterData.fromCbor(UserContext.getWriterDataCbor(network, username).join().right).generationAlgorithm.get();
        Assert.assertTrue("legacy accounts generate boxer", originalAlg.generateBoxerAndIdentity());
        Pair<PublicKeyHash, PublicBoxingKey> keyPairs = userContext.getPublicKeys(username).join().get();
        PublicBoxingKey initialBoxer = keyPairs.right;
        PublicKeyHash initialIdentity = keyPairs.left;
        WriterData initialWd = WriterData.getWriterData(initialIdentity, initialIdentity, network.mutable, network.dhtClient).join().props.get();
        Assert.assertTrue(initialWd.staticData.isPresent());

        UserContext login = PeergosNetworkUtils.ensureSignedUp(username, password, network, crypto);

        String newPassword = "newPassword";
        userContext.changePassword(password, newPassword, UserTests::noMfa).get();
        MultiUserTests.checkUserValidity(network, username);

        UserContext changedPassword = PeergosNetworkUtils.ensureSignedUp(username, newPassword, network, crypto);
        Pair<PublicKeyHash, PublicBoxingKey> newKeyPairs = changedPassword.getPublicKeys(username).join().get();
        PublicBoxingKey newBoxer = newKeyPairs.right;
        PublicKeyHash newIdentity = newKeyPairs.left;
        Assert.assertTrue(newBoxer.equals(initialBoxer));
        Assert.assertTrue(! newIdentity.equals(initialIdentity));

        SecretGenerationAlgorithm alg = WriterData.fromCbor(UserContext.getWriterDataCbor(network, username).join().right).generationAlgorithm.get();
        Assert.assertTrue("password change upgrades legacy accounts", ! alg.generateBoxerAndIdentity());
        WriterData finalWd = WriterData.getWriterData(newIdentity, newIdentity, network.mutable, network.dhtClient).join().props.get();
        Assert.assertTrue(finalWd.staticData.isEmpty());
    }

    @Test
    public void changePasswordFAIL() throws Exception {
        String username = generateUsername();
        String password = "password";
        UserContext userContext = PeergosNetworkUtils.ensureSignedUp(username, password, network, crypto);
        String newPassword = "passwordtest";
        UserContext newContext = userContext.changePassword(password, newPassword, UserTests::noMfa).get();

        try {
            UserContext oldContext = PeergosNetworkUtils.ensureSignedUp(username, password, network, crypto);
        } catch (Exception e) {
            if (! e.getMessage().contains("Incorrect password") && ! e.getMessage().contains("Incorrect+password"))
                throw e;
        }
    }

    @Test
    public void changeLoginAlgorithm() throws Exception {
        String username = generateUsername();
        String password = "password";
        UserContext context = PeergosNetworkUtils.ensureSignedUp(username, password, network, crypto);
        SecretGenerationAlgorithm algo = context.getKeyGenAlgorithm().get();
        ScryptGenerator newAlgo = new ScryptGenerator(19, 8, 1, 64, algo.getExtraSalt());
        context.changePassword(password, password, algo, newAlgo, LocalDate.now().plusMonths(2), UserTests::noMfa).get();
        PeergosNetworkUtils.ensureSignedUp(username, password, network, crypto);
    }

    @Test
    public void maliciousPointerClone() throws Throwable {
        String a = generateUsername();
        String b = generateUsername();
        String password = "password";
        UserContext aContext = PeergosNetworkUtils.ensureSignedUp(a, password, network, crypto);
        UserContext bContext = PeergosNetworkUtils.ensureSignedUp(b, password, network, crypto);

        FileWrapper aRoot = aContext.getUserRoot().join();
        FileWrapper bRoot = bContext.getUserRoot().join();
        MaybeMultihash target = network.mutable.getPointerTarget(aContext.signer.publicKeyHash, aRoot.writer(), network.dhtClient).join().updated;
        MaybeMultihash current = network.mutable.getPointerTarget(bContext.signer.publicKeyHash, bRoot.writer(), network.dhtClient).join().updated;
        PointerUpdate cas = new PointerUpdate(current, target, Optional.of(1000L));
        Assert.assertFalse(network.mutable.setPointer(bContext.signer.publicKeyHash, bRoot.writer(),
                bRoot.signingPair().secret.signMessage(cas.serialize()).join()).join());
        MaybeMultihash updated = network.mutable.getPointerTarget(bContext.signer.publicKeyHash, bRoot.writer(), network.dhtClient).join().updated;
        Assert.assertTrue("Malicious pointer update failed", updated.equals(current));
    }

    @Test
    public void downloadSmallFile() throws Exception {
        String username = generateUsername();
        String password = "test";
        UserContext context = PeergosNetworkUtils.ensureSignedUp(username, password, network, crypto);
        FileWrapper userRoot = context.getUserRoot().get();

        String filename = "somedata.txt";
        Path filePath = PathUtil.get(username, filename);
        byte[] data = new byte[3];
        userRoot.uploadOrReplaceFile(filename, new AsyncReader.ArrayBacked(data), data.length, context.network,
                context.crypto, l -> {}).get();
        checkFileContents(data, context.getByPath(filePath).join().get(), context);
        FileWrapper file = context.getByPath(filePath).join().get();
        FileProperties props = file.getFileProperties();
        List<Boolean> progressUpdate = new ArrayList<>();
        file.getInputStream(context.network, context.crypto, props.sizeHigh(), props.sizeLow(), read -> {
            progressUpdate.add(true);
        }).join();
        assertTrue(progressUpdate.size() == 1 && progressUpdate.get(0));
    }
    @Test
    public void concurrentFileModificationFailure() throws Exception {
        String username = generateUsername();
        String password = "test";
        NetworkAccess network = this.network.clear();
        UserContext context = PeergosNetworkUtils.ensureSignedUp(username, password, network, crypto);
        FileWrapper userRoot = context.getUserRoot().get();

        String filename = "somedata.txt";
        Path filePath = PathUtil.get(username, filename);
        // write empty file
        byte[] data = new byte[120*1024];
        userRoot.uploadOrReplaceFile(filename, new AsyncReader.ArrayBacked(data), data.length, context.network,
                context.crypto, l -> {}).get();
        checkFileContents(data, context.getByPath(filePath).join().get(), context);

        FileWrapper fileV1 = context.getByPath(filePath).join().get();
        FileWrapper fileV2 = context.getByPath(filePath).join().get();
        byte[] section1 = "11111111".getBytes();
        PublicKeyHash owner = fileV2.owner();
        SigningPrivateKeyAndPublicHash writer = fileV2.signingPair();
        network.synchronizer.applyComplexUpdate(owner, writer,
                (v, c) -> fileV2.overwriteSection(v, c, AsyncReader.build(section1),
                        1024, 1024 + section1.length, network, crypto, x -> {})).join();
        byte[] data1 = Arrays.copyOfRange(data, 0, data.length);
        System.arraycopy(section1, 0, data1, 1024, section1.length);
        checkFileContents(data1, context.getByPath(filePath).join().get(), context);
        byte[] section2 = "22222222".getBytes();
        try {
            network.synchronizer.applyComplexUpdate(owner, writer,
                    (v, c) -> fileV1.overwriteSection(v, c, AsyncReader.build(section2),
                            1024, 1024 + section2.length, network, crypto, x -> {})).join();
            throw new RuntimeException("Concurrentmodification should have failed!");
        } catch (CompletionException c) {
            if (!(c.getCause() instanceof CasException))
                throw new RuntimeException("Failure!");
        }
    }

    @Test
    public void writeReadVariations() throws Exception {
        String username = generateUsername();
        String password = "test";
        UserContext context = PeergosNetworkUtils.ensureSignedUp(username, password, network, crypto);
        FileWrapper userRoot = context.getUserRoot().get();

        String filename = "somedata.txt";
        // write empty file
        byte[] data = new byte[0];
        userRoot.uploadOrReplaceFile(filename, new AsyncReader.ArrayBacked(data), data.length, context.network,
                context.crypto, l -> {}).get();
        checkFileContents(data, context.getUserRoot().get().getDescendentByPath(filename, crypto.hasher, context.network).get().get(), context);

        // write small 1 chunk file
        byte[] data2 = "This is a small amount of data".getBytes();
        FileWrapper updatedRoot = uploadFileSection(context.getUserRoot().get(), filename, new AsyncReader.ArrayBacked(data2), 0, data2.length, context.network,
                context.crypto, l -> {}).get();
        checkFileContents(data2, updatedRoot.getDescendentByPath(filename, crypto.hasher, context.network).get().get(), context);

        // check multiple read calls  in one chunk
        checkFileContentsChunked(data2, updatedRoot.getDescendentByPath(filename, crypto.hasher, context.network).get().get(), context, 3);
        // check file size
        // assertTrue("File size", data2.length == userRoot.getDescendentByPath(filename,context.network).get().get().getFileProperties().size);


        // check multiple read calls in multiple chunks
        int bigLength = Chunk.MAX_SIZE * 3;
        byte[] bigData = new byte[bigLength];
        random.nextBytes(bigData);
        FileWrapper updatedRoot2 = uploadFileSection(updatedRoot, filename, new AsyncReader.ArrayBacked(bigData), 0, bigData.length, context.network,
                context.crypto, l -> {}).get();
        checkFileContentsChunked(bigData,
                updatedRoot2.getDescendentByPath(filename, crypto.hasher, context.network).get().get(),
                context,
                5);
        assertTrue("File size", bigData.length == context.getByPath(username + "/" + filename).get().get().getFileProperties().size);

        // extend file within existing chunk
        byte[] data3 = new byte[128 * 1024];
        new Random().nextBytes(data3);
        String otherName = "other"+filename;
        FileWrapper updatedRoot3 = uploadFileSection(updatedRoot2, otherName, new AsyncReader.ArrayBacked(data3), 0, data3.length, context.network,
                context.crypto, l -> {}).get();
        assertTrue("File size", data3.length == context.getByPath(username + "/" + otherName).get().get().getFileProperties().size);
        checkFileContents(data3, updatedRoot3.getDescendentByPath(otherName, crypto.hasher, context.network).get().get(), context);

        // insert data in the middle
        byte[] data4 = "some data to insert somewhere".getBytes();
        int startIndex = 100 * 1024;
        FileWrapper updatedRoot4 = uploadFileSection(updatedRoot3, otherName, new AsyncReader.ArrayBacked(data4), startIndex, startIndex + data4.length,
                context.network, context.crypto, l -> {}).get();
        System.arraycopy(data4, 0, data3, startIndex, data4.length);
        checkFileContents(data3, updatedRoot4.getDescendentByPath(otherName, crypto.hasher, context.network).get().get(), context);

        //rename
        String newname = "newname.txt";
        FileWrapper updatedRoot5 = updatedRoot4.getDescendentByPath(otherName, crypto.hasher, context.network).join().get()
                .rename(newname, updatedRoot4, PathUtil.get(username, otherName), context).join();
        checkFileContents(data3, updatedRoot5.getDescendentByPath(newname, crypto.hasher, context.network).join().get(), context);
        // check from the root as well
        checkFileContents(data3, context.getByPath(username + "/" + newname).get().get(), context);
        // check from a fresh log in too
        UserContext context2 = PeergosNetworkUtils.ensureSignedUp(username, password, network.clear(), crypto);
        Optional<FileWrapper> renamed = context2.getByPath(username + "/" + newname).get();
        checkFileContents(data3, renamed.get(), context);
    }

    @Test
    public void bulkUpload() throws Exception {
        String username = generateUsername();
        String password = "test";
        UserContext context = PeergosNetworkUtils.ensureSignedUp(username, password, network, crypto);
        FileWrapper userRoot = context.getUserRoot().get();

        List<Integer> fileSizes = List.of(1024, 4096, 8192, 1024*1024, 6*1024*1024, 15*1024*1024);
        List<Integer> countForSize = List.of(10, 5, 4, 2, 1, 1);

        Map<List<String>, byte[]> subtree = new HashMap<>();

        String subdir = randomString();
        String subsubdir = randomString();
        for (int i=0; i < countForSize.size(); i++) {
            for (int j=0; j < countForSize.get(i); j++) {
                byte[] data = new byte[fileSizes.get(i)];
                random.nextBytes(data);
                subtree.put(Arrays.asList(randomString()), data);
                subtree.put(Arrays.asList(subdir, randomString()), data);
                subtree.put(Arrays.asList(subdir, subsubdir, randomString()), data);
            }
        }

        Stream<FileWrapper.FolderUploadProperties> byFolder = subtree.entrySet()
                .stream()
                .collect(Collectors.groupingBy(e -> e.getKey().subList(0, e.getKey().size() - 1)))
                .entrySet()
                .stream()
                .map(e -> new FileWrapper.FolderUploadProperties(e.getKey(),
                        e.getValue()
                                .stream()
                                .map(f -> new FileWrapper.FileUploadProperties(f.getKey().get(f.getKey().size() - 1),
                                        AsyncReader.build(f.getValue()), 0, f.getValue().length, false, false, x -> {}))
                                .collect(Collectors.toList())));

        int priorChildren = userRoot.getChildren(crypto.hasher, network).join().size();

        userRoot.uploadSubtree(byFolder, Optional.empty(), network, crypto, context.getTransactionService(), f -> Futures.of(false), () -> true).join();

        userRoot = context.getUserRoot().join();
        int postChildren = userRoot.getChildren(crypto.hasher, network).join().size();
        Assert.assertTrue("uploaded dir present", postChildren > priorChildren);
        for (Map.Entry<List<String>, byte[]> e : subtree.entrySet()) {
            String path = e.getKey().stream().collect(Collectors.joining("/"));
            Optional<FileWrapper> fileOpt = userRoot.getDescendentByPath(path, crypto.hasher, context.network).join();
            Assert.assertTrue(path + "is present", fileOpt.isPresent());
            FileWrapper file = fileOpt.get();
            checkFileContentsChunked(e.getValue(), file, context, 5);
        }
    }

    @Test
    public void resumeFailedUploads() {
        String username = generateUsername();
        String password = "terriblepassword";
        UserContext context = PeergosNetworkUtils.ensureSignedUp(username, password, network, crypto);

        String filename = "somefile";
        int size = 50 * 1024 * 1024;
        byte[] data = new byte[size];
        random.nextBytes(data);
        AsyncReader thrower = new ThrowingStream(data, size / 2);
        FileWrapper txnDir = context.getByPath(Paths.get(username, UserContext.TRANSACTIONS_DIR_NAME)).join().get();
        TransactionService txns = new NonClosingTransactionService(network, crypto, txnDir);
        String subdir = "dir";
        try {
            FileWrapper.FileUploadProperties fileUpload = new FileWrapper.FileUploadProperties(filename, thrower, 0, size, false, false, x -> {
            });
            FileWrapper.FolderUploadProperties dirUploads = new FileWrapper.FolderUploadProperties(Arrays.asList(subdir), Arrays.asList(fileUpload));
            context.getUserRoot().join().uploadSubtree(Stream.of(dirUploads), context.mirrorBatId(), network, crypto, txns, f -> Futures.of(false), () -> true).join();
        } catch (Exception e) {
        }
        FileWrapper home = context.getUserRoot().join();
        Set<Transaction> open = context.getTransactionService().getOpenTransactions(home.version).join();
        Assert.assertTrue(open.size() > 0);
        // Now try again, with confirmation from the user to resume upload
        FileWrapper parent = context.getByPath(Paths.get(username, subdir)).join().get();
        parent.uploadFileJS(filename, AsyncReader.build(data), 0, size, false, context.mirrorBatId(),
                network, crypto, x -> {}, context.getTransactionService(), f -> Futures.of(true)).join();
        checkFileContents(data, context.getByPath(Paths.get(username, subdir, filename)).join().get(), context);
    }

    @Test
    public void replaceFileBulkUpload() {
        String username = generateUsername();
        String password = "terriblepassword";
        UserContext context = PeergosNetworkUtils.ensureSignedUp(username, password, network, crypto);

        String filename = "somefile.txt";
        int size = 1000;
        byte[] data = new byte[size];
        random.nextBytes(data);
        AsyncReader reader = new AsyncReader.ArrayBacked(data);
        FileWrapper txnDir = context.getByPath(Paths.get(username, UserContext.TRANSACTIONS_DIR_NAME)).join().get();
        TransactionService txns = new NonClosingTransactionService(network, crypto, txnDir);
        String subdir = "dir";
        long[] monitorVal = new long[1];
        ProgressConsumer<Long> monitor = val -> {
            monitorVal[0] = val;
        };
        try {
            FileWrapper.FileUploadProperties fileUpload = new FileWrapper.FileUploadProperties(filename, reader, 0, size, false, false, monitor);
            FileWrapper.FolderUploadProperties dirUploads = new FileWrapper.FolderUploadProperties(Arrays.asList(subdir), Arrays.asList(fileUpload));
            context.getUserRoot().join().uploadSubtree(Stream.of(dirUploads), context.mirrorBatId(), network, crypto, txns, f -> Futures.of(false), () -> true).join();
        } catch (Exception e) {
        }
        long[] monitorUploadJSVal = new long[1];
        ProgressConsumer<Long> monitorUploadJS = val -> {
            monitorUploadJSVal[0] = val;
        };
        FileWrapper parent = context.getByPath(Paths.get(username, subdir)).join().get();
        parent.uploadFileJS(filename, AsyncReader.build(data), 0, size, true, context.mirrorBatId(), network, crypto, monitorUploadJS, txns, f -> Futures.of(true)).join();
        Assert.assertTrue("monitorJS", monitorUploadJSVal[0] == monitorVal[0]);

        random.nextBytes(data);
        AsyncReader reader2 = new AsyncReader.ArrayBacked(data);
        FileWrapper txnDir2 = context.getByPath(Paths.get(username, UserContext.TRANSACTIONS_DIR_NAME)).join().get();
        TransactionService txns2 = new NonClosingTransactionService(network, crypto, txnDir2);
        long[] monitor2Val = new long[1];
        ProgressConsumer<Long> monitor2 = val -> {
            monitor2Val[0] = val;
        };
        try {
            FileWrapper.FileUploadProperties fileUpload = new FileWrapper.FileUploadProperties(filename, reader2, 0, size, false, true, monitor2);
            FileWrapper.FolderUploadProperties dirUploads = new FileWrapper.FolderUploadProperties(Arrays.asList(subdir), Arrays.asList(fileUpload));
            context.getUserRoot().join().uploadSubtree(Stream.of(dirUploads), context.mirrorBatId(), network, crypto, txns2, f -> Futures.of(false), () -> true).join();
        } catch (Exception e) {
        }
        Assert.assertTrue("bulkMonitor", monitor2Val[0] == monitorVal[0]);
    }

    @Test
    public void appendToFile() {
        String username = generateUsername();
        String password = "test";
        UserContext context = PeergosNetworkUtils.ensureSignedUp(username, password, network, crypto);
        FileWrapper userRoot = context.getUserRoot().join();
        FileWrapper userRootCopy = context.getUserRoot().join();

        String filename = "file1.txt";
        String contents = "Hello ";
        byte[] data = contents.getBytes();
        userRootCopy = userRoot.uploadFileJS(filename, new AsyncReader.ArrayBacked(data), 0,data.length, false,
                userRoot.mirrorBatId(), network, crypto, l -> {}, context.getTransactionService(), f -> Futures.of(false)).join();
        checkFileContents(data, context.getUserRoot().join().getDescendentByPath(filename, crypto.hasher, context.network).join().get(), context);

        contents = "World!";
        data = contents.getBytes();
        userRootCopy = userRootCopy.appendFileJS(filename, new AsyncReader.ArrayBacked(data), 0,data.length, network, crypto, l -> {}).join();
        checkFileContents("Hello World!".getBytes(), context.getUserRoot().join().getDescendentByPath(filename, crypto.hasher, context.network).join().get(), context);

    }

    @Test
    public void concurrentUploadSucceeds() {
        String username = generateUsername();
        String password = "test";
        UserContext context = PeergosNetworkUtils.ensureSignedUp(username, password, network, crypto);
        FileWrapper userRoot = context.getUserRoot().join();
        FileWrapper userRootCopy = context.getUserRoot().join();

        String filename = "file1.bin";
        byte[] data = randomData(6*1024*1024);
        userRoot.uploadFileJS(filename, new AsyncReader.ArrayBacked(data), 0,data.length, false,
                userRoot.mirrorBatId(), network, crypto, l -> {}, context.getTransactionService(), f -> Futures.of(false)).join();
        checkFileContents(data, context.getUserRoot().join().getDescendentByPath(filename, crypto.hasher, context.network).join().get(), context);

        String file2name = "file2.bin";
        byte[] data2 = randomData(6*1024*1024);
        userRootCopy.uploadFileJS(file2name, new AsyncReader.ArrayBacked(data2), 0,data2.length, false,
                userRootCopy.mirrorBatId(), network, crypto, l -> {}, context.getTransactionService(), f -> Futures.of(false)).join();
        checkFileContents(data2, context.getUserRoot().join().getDescendentByPath(file2name, crypto.hasher, context.network).join().get(), context);
    }

    @Test
    public void renameFile() throws Exception {
        String username = generateUsername();
        String password = "test";
        UserContext context = PeergosNetworkUtils.ensureSignedUp(username, password, network, crypto);
        FileWrapper userRoot = context.getUserRoot().get();

        String filename = "somedata.txt";
        // write empty file
        byte[] data = new byte[0];
        userRoot.uploadOrReplaceFile(filename, new AsyncReader.ArrayBacked(data), data.length, context.network,
                context.crypto, l -> {}).get();
        checkFileContents(data, context.getUserRoot().get().getDescendentByPath(filename, crypto.hasher, context.network).get().get(), context);

        //rename
        String newname = "newname.txt";
        FileWrapper parent = context.getUserRoot().get();
        FileWrapper file = context.getByPath(username + "/" + filename).get().get();

        file.rename(newname, parent, PathUtil.get(username, filename), context).get();

        FileWrapper updatedRoot = context.getUserRoot().get();
        FileWrapper updatedFile = context.getByPath(updatedRoot.getName() + "/" + newname).get().get();
        checkFileContents(data, updatedFile, context);
    }

    @Test
    public void fileModifiedDateShouldChangeAfterOverwrite() throws Exception {
        String username = generateUsername();
        String password = "test";
        UserContext context = PeergosNetworkUtils.ensureSignedUp(username, password, network, crypto);
        FileWrapper userRoot = context.getUserRoot().get();

        String filename = "somedata.txt";
        byte[] data = randomData(1024);
        userRoot.uploadOrReplaceFile(filename, new AsyncReader.ArrayBacked(data), data.length, context.network,
                context.crypto, l -> {}).get();

        userRoot = context.getUserRoot().get();

        userRoot.getChild(filename, context.crypto.hasher, context.network).thenCompose(file -> {
            FileWrapper existingFile = file.get();
            LocalDateTime modified = existingFile.getFileProperties().modified;
            byte[] bytes = randomData(1024 * 2);
            try {
                Thread.sleep(1000);
            } catch (InterruptedException ie) {
                ie.printStackTrace();
            }
            return file.get().overwriteFile(AsyncReader.build(bytes), bytes.length, context.network, context.crypto,
                    x -> {})
                    .thenApply(updatedFile -> {
                        LocalDateTime newTimestamp = updatedFile.getFileProperties().modified;
                        Assert.assertTrue("modified date", ! newTimestamp.equals(modified));
                        return updatedFile;
                    });
        }).join();
    }

    @Test
    public void directoryEncryptionKey() throws Exception {
        // ensure that a directory's child links are encrypted with the base key, not the parent key
        String username = generateUsername();
        String password = "test";
        UserContext context = PeergosNetworkUtils.ensureSignedUp(username, password, network, crypto);
        FileWrapper userRoot = context.getUserRoot().get();

        String dirname = "somedir";
        userRoot.mkdir(dirname, network, false, userRoot.mirrorBatId(), crypto).join();

        FileWrapper dir = context.getByPath("/" + username + "/" + dirname).get().get();
        RetrievedCapability pointer = dir.getPointer();
        SymmetricKey baseKey = pointer.capability.rBaseKey;
        SymmetricKey parentKey = dir.getParentKey();
        Assert.assertTrue("parent key different from base key", ! parentKey.equals(baseKey));
        pointer.fileAccess.getDirectChildren(pointer.capability, dir.version, network).join();
    }

    @Test
    public void fileEncryptionKey() throws Exception {
        // ensure that a directory's child links are encrypted with the base key, not the parent key
        String username = generateUsername();
        String password = "test";
        UserContext context = PeergosNetworkUtils.ensureSignedUp(username, password, network, crypto);
        FileWrapper userRoot = context.getUserRoot().get();

        String filename = "somedir";
        byte[] data = new byte[200*1024];
        userRoot.uploadOrReplaceFile(filename, new AsyncReader.ArrayBacked(data), data.length, context.network,
                context.crypto, l -> {}).join();

        FileWrapper file = context.getByPath("/" + username + "/" + filename).join().get();
        RetrievedCapability pointer = file.getPointer();
        SymmetricKey baseKey = pointer.capability.rBaseKey;
        SymmetricKey dataKey = pointer.fileAccess.getDataKey(baseKey);
        Assert.assertTrue("data key different from base key", ! dataKey.equals(baseKey));
        pointer.fileAccess.getLinkedData(file.owner(), dataKey, c -> ((CborObject.CborByteArray)c).value, crypto.hasher, network, x -> {}).join();
    }

    @Test
    public void concurrentWritesToDir() throws Exception {
        String username = generateUsername();
        String password = "test01";
        UserContext context = PeergosNetworkUtils.ensureSignedUp(username, password, network, crypto);

        // write empty file
        int concurrency = 8;
        int fileSize = 1024;
        ForkJoinPool pool = new ForkJoinPool(concurrency);
        Set<CompletableFuture<Boolean>> futs = IntStream.range(0, concurrency)
                .mapToObj(i -> CompletableFuture.supplyAsync(() -> {
                    byte[] data = randomData(fileSize);
                    String filename = i + ".bin";
                    FileWrapper userRoot = context.getUserRoot().join();
                    FileWrapper result = userRoot.uploadOrReplaceFile(filename,
                            new AsyncReader.ArrayBacked(data),
                            data.length, context.network, context.crypto, l -> {}).join();
                    Optional<FileWrapper> childOpt = result.getChild(filename, crypto.hasher, network).join();
                    checkFileContents(data, childOpt.get(), context);
                    LOG.info("Finished a file");
                    return true;
                }, pool)).collect(Collectors.toSet());

        boolean success = Futures.combineAll(futs).get().stream().reduce(true, (a, b) -> a && b);

        Set<FileWrapper> files = context.getUserRoot().get().getChildren(crypto.hasher, context.network).get();
        Set<String> names = files.stream().filter(f -> ! f.getFileProperties().isHidden).map(f -> f.getName()).collect(Collectors.toSet());
        Set<String> expectedNames = IntStream.range(0, concurrency).mapToObj(i -> i + ".bin").collect(Collectors.toSet());
        Assert.assertTrue("All children present and accounted for: " + names, names.equals(expectedNames));
    }

    @Test
    public void concurrentMkdirs() throws Exception {
        String username = generateUsername();
        String password = "test01";
        UserContext context = PeergosNetworkUtils.ensureSignedUp(username, password, network, crypto);

        // write empty file
        int concurrency = 8;
        int fileSize = 1024;
        ForkJoinPool pool = new ForkJoinPool(concurrency);
        Set<CompletableFuture<Boolean>> futs = IntStream.range(0, concurrency)
                .mapToObj(i -> CompletableFuture.supplyAsync(() -> {
                    byte[] data = randomData(fileSize);
                    String filename = "folder" + i;
                        FileWrapper userRoot = context.getUserRoot().join();
                        FileWrapper result = userRoot.uploadOrReplaceFile(filename,
                                new AsyncReader.ArrayBacked(data), data.length, context.network, context.crypto, l -> {}).join();
                        return true;
                }, pool)).collect(Collectors.toSet());

        boolean success = Futures.combineAll(futs).get().stream().reduce(true, (a, b) -> a && b);

        Set<FileWrapper> files = context.getUserRoot().get().getChildren(crypto.hasher, context.network).get();
        Set<String> names = files.stream().filter(f -> ! f.getFileProperties().isHidden).map(f -> f.getName()).collect(Collectors.toSet());
        Set<String> expectedNames = IntStream.range(0, concurrency).mapToObj(i -> "folder" + i).collect(Collectors.toSet());
        Assert.assertTrue("All children present and accounted for: " + names, names.equals(expectedNames));
    }

    @Test
    public void concurrentWritesToFile() throws Exception {
        String username = generateUsername();
        String password = "test01";
        UserContext context = PeergosNetworkUtils.ensureSignedUp(username, password, network, crypto);

        // write a n chunk file, then concurrently modify each of the chunks
        int concurrency = 2;
        int CHUNK_SIZE = 5 * 1024 * 1024;
        int fileSize = concurrency * CHUNK_SIZE;
        String filename = "afile.bin";
        FileWrapper userRoot = context.getUserRoot().get();
        FileWrapper newRoot = userRoot.uploadOrReplaceFile(filename,
                new AsyncReader.ArrayBacked(randomData(fileSize)),
                fileSize, context.network, context.crypto, l -> {}).get();

        List<byte[]> sections = Collections.synchronizedList(new ArrayList<>(concurrency));
        for (int i=0; i < concurrency; i++)
            sections.add(null);

        ForkJoinPool pool = new ForkJoinPool(concurrency);
        Set<CompletableFuture<Boolean>> futs = IntStream.range(0, concurrency)
                .mapToObj(i -> CompletableFuture.supplyAsync(() -> {
                    FileWrapper root = context.getUserRoot().join();

                    byte[] data = randomData(CHUNK_SIZE);
                    FileWrapper result = uploadFileSection(root, filename,
                            new AsyncReader.ArrayBacked(data),
                            i * CHUNK_SIZE, (i + 1) * CHUNK_SIZE,
                            context.network, context.crypto, l -> {}).join();
                    Optional<FileWrapper> childOpt = result.getChild(filename, crypto.hasher, network).join();
                    sections.set(i, data);
                    return true;
                }, pool)).collect(Collectors.toSet());

        boolean success = Futures.combineAll(futs).get().stream().reduce(true, (a, b) -> a && b);

        FileWrapper file = context.getByPath("/" + username + "/" + filename).get().get();
        byte[] all = new byte[concurrency * CHUNK_SIZE];
        for (int i=0; i < concurrency; i++)
            System.arraycopy(sections.get(i), 0, all, i * CHUNK_SIZE, CHUNK_SIZE);
        checkFileContents(all, file, context);
    }

    @Test
    public void smallFileWrite() throws Exception {
        String username = generateUsername();
        String password = "test01";
        UserContext context = PeergosNetworkUtils.ensureSignedUp(username, password, network, crypto);
        FileWrapper userRoot = context.getUserRoot().get();

        String filename = "small.txt";
        byte[] data = "G'day mate".getBytes();
        AtomicLong writeCount = new AtomicLong(0);
        userRoot.uploadOrReplaceFile(filename, new AsyncReader.ArrayBacked(data), data.length, context.network,
                context.crypto, writeCount::addAndGet).get();
        FileWrapper file = context.getByPath(PathUtil.get(username, filename).toString()).get().get();
        String mimeType = file.getFileProperties().mimeType;
        Assert.assertTrue("Incorrect mimetype: " + mimeType, mimeType.equals("text/plain"));
        Assert.assertTrue("No thumbnail", ! file.getFileProperties().thumbnail.isPresent());
        Assert.assertTrue("Completed progress monitor", writeCount.get() == data.length + FileWrapper.THUMBNAIL_PROGRESS_OFFSET);
        AbsoluteCapability cap = file.getPointer().capability;
        CryptreeNode fileAccess = file.getPointer().fileAccess;
        RelativeCapability toParent = fileAccess.getParentCapability(fileAccess.getParentKey(cap.rBaseKey)).get();
        Assert.assertTrue("parent link shouldn't include write access",
                ! toParent.wBaseKeyLink.isPresent());
        Assert.assertTrue("parent link shouldn't include public write key",
                ! toParent.writer.isPresent());

        FileWrapper home = context.getByPath(PathUtil.get(username).toString()).get().get();
        RetrievedCapability homePointer = home.getPointer();
        List<NamedRelativeCapability> children = homePointer.fileAccess.getDirectChildren(homePointer.capability, home.version, network).get();
        for (NamedRelativeCapability child : children) {
            Assert.assertTrue("child pointer is minimal",
                    ! child.cap.writer.isPresent() && child.cap.wBaseKeyLink.isPresent());
        }
    }

    static class ThrowingStream implements AsyncReader {
        private final byte[] data;
        private int index = 0;
        private final int throwAtIndex;

        public ThrowingStream(byte[] data, int throwAtIndex) {
            this.data = data;
            this.throwAtIndex = throwAtIndex;
        }

        @Override
        public CompletableFuture<AsyncReader> seekJS(int high32, int low32) {
            if (high32 != 0)
                throw new IllegalArgumentException("Cannot have arrays larger than 4GiB!");
            if (index + low32 > throwAtIndex)
                throw new RuntimeException("Simulated IO Error");
            index += low32;
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public CompletableFuture<Integer> readIntoArray(byte[] res, int offset, int length) {
            if (index + length > throwAtIndex)
                throw new RuntimeException("Simulated IO Error");
            System.arraycopy(data, index, res, offset, length);
            index += length;
            return CompletableFuture.completedFuture(length);
        }

        @Override
        public CompletableFuture<AsyncReader> reset() {
            index = 0;
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public void close() {}
    }

    @Test
    public void repeatFailedUpload() throws Exception {
        String username = generateUsername();
        String password = "test01";
        UserContext context = PeergosNetworkUtils.ensureSignedUp(username, password, network, crypto);
        FileWrapper userRoot = context.getUserRoot().get();

        String filename = "small.txt";
        byte[] data = new byte[2*5*1024*1024];
        ThrowingStream throwingReader = new ThrowingStream(data, 5 * 1024 * 1024);

        TransactionService transactions = context.getTransactionService();
        try {
            userRoot.uploadFileJS(filename, throwingReader, 0, data.length, false,
                    userRoot.mirrorBatId(), context.network, context.crypto, l -> {}, transactions, f -> Futures.of(false)).join();
        } catch (Exception e) {}

        userRoot.uploadFileJS(filename, AsyncReader.build(data), 0, data.length, false,
                userRoot.mirrorBatId(), context.network, context.crypto, l -> {}, transactions, f -> Futures.of(true)).join();
    }

    @Test
    public void javaThumbnail() throws Exception {
        String username = generateUsername();
        String password = "test01";
        UserContext context = PeergosNetworkUtils.ensureSignedUp(username, password, network, crypto);
        FileWrapper userRoot = context.getUserRoot().get();

        String filename = "small.png";
        byte[] data = Files.readAllBytes(PathUtil.get("assets", "logo.png"));
        userRoot.uploadOrReplaceFile(filename, new AsyncReader.ArrayBacked(data), data.length, context.network,
                context.crypto, l -> {}).get();
        FileWrapper file = context.getByPath(PathUtil.get(username, filename).toString()).get().get();
        String thumbnail = file.getBase64Thumbnail();
        Assert.assertTrue("Has thumbnail", thumbnail.length() > 0);

        data = Files.readAllBytes(PathUtil.get("assets", "logos", "peergos-logo.png"));
        userRoot.uploadOrReplaceFile(filename, new AsyncReader.ArrayBacked(data), data.length, context.network,
                context.crypto, l -> {}).get();
        file = context.getByPath(PathUtil.get(username, filename).toString()).get().get();
        boolean res = file.calculateAndUpdateThumbnail(context.network, context.crypto).join();
        Assert.assertTrue("Has updated Thumbnail", res);
        file = context.getByPath(PathUtil.get(username, filename).toString()).get().get();
        String thumbnailAfter = file.getBase64Thumbnail();
        Assert.assertTrue("Thumbnail NOT changed", !thumbnail.equals(thumbnailAfter));
    }

    @Test
    public void copyFileWithThumbnail() throws Exception {
        String username = generateUsername();
        String password = "test01";
        UserContext context = PeergosNetworkUtils.ensureSignedUp(username, password, network.clear(), crypto);
        FileWrapper userRoot = context.getUserRoot().join();
        Path home = PathUtil.get(username);

        String filename = "logo.png";
        byte[] data = Files.readAllBytes(PathUtil.get("assets", filename));

        FileWrapper updatedUserRoot = userRoot.uploadOrReplaceFile(filename, new AsyncReader.ArrayBacked(data),
                data.length, context.network, crypto, x -> {}).join();
        // copy the file
        String foldername = "afolder";
        updatedUserRoot.mkdir(foldername, context.network, false, userRoot.mirrorBatId(), crypto).join();
        FileWrapper subfolder = context.getByPath(home.resolve(foldername)).join().get();
        FileWrapper original = context.getByPath(home.resolve(filename)).join().get();
        Boolean res = original.copyTo(subfolder, context).join();
        Assert.assertTrue("Copied", res);
        FileWrapper copy = context.getByPath(home.resolve(foldername).resolve(filename)).join().get();
        String thumbnail = copy.getBase64Thumbnail();
        Assert.assertTrue("Has thumbnail", thumbnail.length() > 0);
        checkFileContents(data, copy, context);
    }

    @Ignore // until we figure out how to manage javafx in tests
    @Test
    public void javaVideoThumbnail() throws Exception {
        String username = generateUsername();
        String password = "test01";
        UserContext context = PeergosNetworkUtils.ensureSignedUp(username, password, network, crypto);
        FileWrapper userRoot = context.getUserRoot().get();

        String filename = "trailer.mp4";
        byte[] data = Files.readAllBytes(PathUtil.get("assets", filename));
        userRoot.uploadOrReplaceFile(filename, new AsyncReader.ArrayBacked(data), data.length, context.network,
                context.crypto, l -> {}).get();
        FileWrapper file = context.getByPath(PathUtil.get(username, filename).toString()).get().get();
        String thumbnail = file.getBase64Thumbnail();
        Assert.assertTrue("Has thumbnail", thumbnail.length() > 0);
    }

    @Test
    public void legacyFileModification() throws Exception {
        // test that a legacy file without BATs can be modified and extended, and all new fragments have BATs
        String username = generateUsername();
        String password = "test01";
        UserContext context = PeergosNetworkUtils.ensureSignedUp(username, password, network, crypto);
        PublicKeyHash owner = context.signer.publicKeyHash;
        FileWrapper userRoot = context.getUserRoot().get();

        String filename = "mediumfile.bin";
        byte[] data = new byte[4*1024*1024];
        random.nextBytes(data);
        FileWrapper userRoot2 = userRoot.uploadOrReplaceFile(filename, new AsyncReader.ArrayBacked(data), data.length,
                context.network, context.crypto, l -> {}, crypto.random.randomBytes(32), Optional.empty(), Optional.empty()).get();

        // Remove BATs from cryptree node and fragments in file
        FileWrapper file = context.getByPath(PathUtil.get(username, filename)).join().get();
        CborObject.CborMap cbor = (CborObject.CborMap) file.getPointer().fileAccess.toCbor();
        // add blocks without bat prefix
        List<Multihash> rawBlockLinks = cbor.links();
        List<BatWithId> bats = (List<BatWithId>)((CborObject.CborList)((CborObject.CborMap)cbor.get("d")).get("bats")).value;
        List<Multihash> newFragmentCids = IntStream.range(0, rawBlockLinks.size()).mapToObj(i -> {
            Cid original = (Cid) rawBlockLinks.get(i);
            BatWithId bat = bats.get(i);
            byte[] originalBlock = context.network.dhtClient.getRaw(owner, original, Optional.of(bat)).join().get();
            byte[] newBlock = Bat.removeRawBlockBatPrefix(originalBlock);
            return context.network.uploadFragments(Arrays.asList(new Fragment(newBlock)), userRoot.owner(),
                    userRoot.signingPair(), x -> {}, TransactionId.build("tid")).join().get(0);
        }).collect(Collectors.toList());

        Map<String, Cborable> modified = new LinkedHashMap<>();
        cbor.applyToAll(modified::put);
        CborObject.CborMap d = (CborObject.CborMap) modified.get("d");
        d.put("bats", new CborObject.CborList(Collections.emptyList()));
        d.put("f", new CborObject.CborList(newFragmentCids
                .stream()
                .map(CborObject.CborMerkleLink::new)
                .collect(Collectors.toList())));
        modified.put("d", d);
        modified.remove("bats");
        CborObject.CborMap noBats = CborObject.CborMap.build(modified);
        CommittedWriterData cwd = WriterData.getWriterData(owner, file.writer(), network.mutable, network.dhtClient).join();
        WriterData wd = cwd.props.get();
        SigningPrivateKeyAndPublicHash signingPair = file.signingPair();
        Cid cryptreeCid = network.dhtClient.put(owner, signingPair, noBats.serialize(), crypto.hasher,
                TransactionId.build("hey")).join();
        byte[] mapKey = file.readOnlyPointer().getMapKey();

        // also update child pointer in parent
        FileWrapper root = context.getUserRoot().join();
        FileWrapper cFile = file;
        network.synchronizer.applyComplexUpdate(owner, signingPair,
                (s, c) -> {
                    WriterData newWd = network.tree.put(wd, owner, signingPair, mapKey,
                            cFile.getPointer().fileAccess.committedHash(), cryptreeCid, TransactionId.build("hey")).join();
                    return c.commit(owner, signingPair, newWd, cwd, TransactionId.build("123"));
                }).join();
        WritableAbsoluteCapability cap = cFile.writableFilePointer();
        WritableAbsoluteCapability batlessCap = cap.withMapKey(cFile.readOnlyPointer().getMapKey(), Optional.empty());
        network.synchronizer.applyComplexUpdate(owner, signingPair,
                (s, c) -> root.getPointer().fileAccess.updateChildLink(s, c, root.writableFilePointer(), root.signingPair(),
                        cap, new NamedAbsoluteCapability(filename, batlessCap), network, crypto.random, crypto.hasher)).join();

        // check there are no BATs for this file
        UserContext newContext = PeergosNetworkUtils.ensureSignedUp(username, password, network.clear(), crypto);
        file = newContext.getByPath(PathUtil.get(username, filename)).join().get();
        Assert.assertTrue(file.writableFilePointer().bat.isEmpty());

        // Check fragments are retrievable without a BAT
        Multihash originalFragmentWithoutBatPrefix = file.getPointer().fileAccess.toCbor().links().get(0);
        CompletableFuture<Optional<byte[]>> originalRaw = network.clear().dhtClient.getRaw(owner, (Cid) originalFragmentWithoutBatPrefix, Optional.empty());
        Assert.assertTrue(originalRaw.join().isPresent());

        //overwrite with 2 chunk file
        byte[] threeChunkData = new byte[11*1024*1024];
        random.nextBytes(threeChunkData);
        FileWrapper userRoot3 = uploadFileSection(userRoot2, filename, new AsyncReader.ArrayBacked(threeChunkData), 0,
                threeChunkData.length, newContext.network, newContext.crypto, l -> {}).join();
        FileWrapper updatedFile = newContext.getByPath(PathUtil.get(username, filename)).join().get();
        checkFileContents(threeChunkData, updatedFile, newContext);
        assertTrue("10MiB file size", threeChunkData.length == updatedFile.getFileProperties().size);

        WritableAbsoluteCapability newcap = updatedFile.writableFilePointer();
        Assert.assertTrue(newcap.bat.isEmpty());
        // check later chunks don't have BATs
        Pair<byte[], Optional<Bat>> nextChunkRel = updatedFile.getPointer().fileAccess.getNextChunkLocation(updatedFile.getKey(),
                updatedFile.getFileProperties().streamSecret, newcap.getMapKey(), Optional.empty(), crypto.hasher).join();
        Assert.assertTrue(nextChunkRel.right.isEmpty());
        NetworkAccess cleared = network.clear();
        WriterData uwd = WriterData.getWriterData(owner, updatedFile.writer(), network.mutable, network.dhtClient).join().props.get();
        Optional<CryptreeNode> secondChunk = cleared.getMetadata(uwd, newcap.withMapKey(nextChunkRel.left, Optional.empty())).join();
        Assert.assertTrue(secondChunk.isPresent());
        // now the third chunk
        Pair<byte[], Optional<Bat>> thirdChunkRel = secondChunk.get().getNextChunkLocation(updatedFile.getKey(),
                updatedFile.getFileProperties().streamSecret, newcap.getMapKey(), Optional.empty(), crypto.hasher).join();
        Assert.assertTrue(thirdChunkRel.right.isEmpty());
        Optional<CryptreeNode> thirdChunk = cleared.getMetadata(uwd, newcap.withMapKey(thirdChunkRel.left, Optional.empty())).join();
        Assert.assertTrue(thirdChunk.isPresent());

        // check cryptree node can still be retrieved without a BAT
        Assert.assertTrue(network.clear().getFile(updatedFile.version, newcap, Optional.of(updatedFile.signingPair()), username).join().isPresent());

        // check retrieval of fragments fail without bat
        Multihash fragment = updatedFile.getPointer().fileAccess.toCbor().links().get(0);
        Optional<byte[]> raw = network.clear().dhtClient.getRaw(owner, (Cid) fragment, Optional.empty()).exceptionally(e -> Optional.empty()).join();
        Assert.assertTrue(raw.isEmpty());
    }

    @Test
    public void mediumFileWrite() throws Exception {
        String username = generateUsername();
        String password = "test01";
        UserContext context = PeergosNetworkUtils.ensureSignedUp(username, password, network, crypto);
        FileWrapper userRoot = context.getUserRoot().get();

        String filename = "mediumfile.bin";
        byte[] data = new byte[0];
        FileWrapper userRoot2 = userRoot.uploadOrReplaceFile(filename, new AsyncReader.ArrayBacked(data), data.length,
                context.network, context.crypto, l -> {}).get();

        //overwrite with 2 chunk file
        byte[] data5 = new byte[10*1024*1024];
        random.nextBytes(data5);
        FileWrapper userRoot3 = uploadFileSection(userRoot2, filename, new AsyncReader.ArrayBacked(data5), 0, data5.length, context.network,
                context.crypto, l -> {}).join();
        checkFileContents(data5, userRoot3.getDescendentByPath(filename, crypto.hasher, context.network).get().get(), context);
        assertTrue("10MiB file size", data5.length == userRoot3.getDescendentByPath(filename,
                crypto.hasher, context.network).get().get().getFileProperties().size);

        // insert data in the middle of second chunk
        LOG.info("\n***** Mid 2nd chunk write test");
        byte[] dataInsert = "some data to insert somewhere else".getBytes();
        int start = 5*1024*1024 + 4*1024;
        FileWrapper userRoot4 = uploadFileSection(userRoot3, filename, new AsyncReader.ArrayBacked(dataInsert), start, start + dataInsert.length,
                context.network, context.crypto, l -> {}).get();
        System.arraycopy(dataInsert, 0, data5, start, dataInsert.length);
        FileWrapper file = userRoot4.getDescendentByPath(filename, crypto.hasher, context.network).get().get();
        checkFileContents(data5, file, context);

        // check used space
        long totalSpaceUsed = context.getSpaceUsage().get();
        while (totalSpaceUsed < 10*1024*1024) {
            Thread.sleep(1_000);
            totalSpaceUsed = context.getSpaceUsage().get();
        }
        Assert.assertTrue("Correct used space", totalSpaceUsed > 10*1024*1024);

        // check second chunk BAT is different from first
        Pair<byte[], Optional<Bat>> nextChunkRel = file.getPointer().fileAccess.getNextChunkLocation(file.getKey(),
                file.getFileProperties().streamSecret, file.writableFilePointer().getMapKey(), file.writableFilePointer().bat, crypto.hasher).join();
        Assert.assertTrue(! nextChunkRel.right.get().equals(file.writableFilePointer().bat.get()));

        // check retrieval of cryptree node or data both fail without bat
        WritableAbsoluteCapability cap = file.writableFilePointer();
        WritableAbsoluteCapability badCap = cap.withMapKey(cap.getMapKey(), Optional.empty());
        NetworkAccess cleared = network.clear();
        CompletableFuture<Optional<FileWrapper>> badFileGet = cleared.getFile(file.version, badCap, Optional.of(file.signingPair()), username);
        Assert.assertTrue(badFileGet.exceptionally(t -> Optional.empty()).join().isEmpty());

        Multihash fragment = file.getPointer().fileAccess.toCbor().links().get(0);
        CompletableFuture<Optional<byte[]>> raw = cleared.dhtClient.getRaw(context.signer.publicKeyHash, (Cid) fragment, Optional.empty());
        Assert.assertTrue(raw.exceptionally(t -> Optional.empty()).join().isEmpty());
    }

    @Test
    public void truncate() {
        String username = generateUsername();
        String password = "test01";
        UserContext context = PeergosNetworkUtils.ensureSignedUp(username, password, network, crypto);
        FileWrapper userRoot = context.getUserRoot().join();

        String filename = "mediumfile.bin";
        byte[] data = new byte[15*1024*1024];
        random.nextBytes(data);
        FileWrapper userRoot2 = userRoot.uploadOrReplaceFile(filename, new AsyncReader.ArrayBacked(data), data.length,
                context.network, context.crypto, l -> {}).join();

        FileWrapper original = context.getByPath(PathUtil.get(username, filename)).join().get();
        Pair<byte[], Optional<Bat>> thirdChunkLabel = original.getMapKey(12 * 1024 * 1024, network, crypto).join();

        int truncateLength = 7 * 1024 * 1024;
        FileWrapper truncated = original.truncate(truncateLength, network, crypto).join();
        checkFileContents(Arrays.copyOfRange(data, 0, truncateLength), truncated, context);
        // check we can't get the third chunk any more
        WritableAbsoluteCapability pointer = original.writableFilePointer();
        CommittedWriterData cwd = network.synchronizer.getValue(pointer.owner, pointer.writer).join().get(pointer.writer);
        Optional<CryptreeNode> thirdChunk = network.getMetadata(cwd.props.get(), pointer.withMapKey(thirdChunkLabel.left, thirdChunkLabel.right)).join();
        Assert.assertTrue("File is truncated", ! thirdChunk.isPresent());
        Assert.assertTrue("File has correct size", truncated.getFileProperties().size == truncateLength);

        // truncate to first chunk
        int truncateLength2 = 1 * 1024 * 1024;
        FileWrapper truncated2 = truncated.truncate(truncateLength2, network, crypto).join();
        checkFileContents(Arrays.copyOfRange(data, 0, truncateLength2), truncated2, context);
        Assert.assertTrue("File has correct size", truncated2.getFileProperties().size == truncateLength2);

        // truncate within first chunk
        int truncateLength3 = 1024 * 1024 / 2;
        FileWrapper truncated3 = truncated2.truncate(truncateLength3, network, crypto).join();
        checkFileContents(Arrays.copyOfRange(data, 0, truncateLength2), truncated2, context);
        Assert.assertTrue("File has correct size", truncated3.getFileProperties().size == truncateLength3);
    }

    @Test
    public void fileSeek() throws Exception {
        String username = generateUsername();
        String password = "test01";
        UserContext context = PeergosNetworkUtils.ensureSignedUp(username, password, network, crypto);
        FileWrapper userRoot = context.getUserRoot().get();

        String filename = "mediumfile.bin";

        int MB = 1024*1024;
        byte[] data = new byte[15 * MB];
        random.nextBytes(data);
        uploadFileSection(userRoot, filename, new AsyncReader.ArrayBacked(data), 0, data.length, context.network,
                context.crypto, l -> {}).join();
        byte[] buf = new byte[2 * MB];

        for (int offset: Arrays.asList(10, 4*MB, 6*MB, 11*MB)) {
            AsyncReader reader = context.getByPath(PathUtil.get(username, filename)).join()
                    .get().getInputStream(network, crypto, x -> { }).join();
            AsyncReader seeked = reader.seek(offset).join();
            seeked.readIntoArray(buf, 0, buf.length).join();
            if (! Arrays.equals(buf, Arrays.copyOfRange(data, offset, offset + buf.length)))
                throw new IllegalStateException("Seeked data incorrect! Offset: " + offset);
        }

        for (int mb = 0; mb < 13; mb++) {
            AsyncReader reader = context.getByPath(PathUtil.get(username, filename)).join()
                    .get().getInputStream(network, crypto, x -> { }).join();
            for (int count = 0; count < mb; count++) {
                reader = reader.seek(count * MB).join();
                reader.readIntoArray(buf, 0, buf.length).join();
                if (!Arrays.equals(buf, Arrays.copyOfRange(data, count * MB, count * MB + buf.length)))
                    throw new IllegalStateException("Seeked data incorrect! Offset: " + count * MB);
            }
        }
    }

    @Test
    public void writeTiming() throws Exception {
        String username = generateUsername();
        String password = "test01";
        UserContext context = PeergosNetworkUtils.ensureSignedUp(username, password, network, crypto);
        FileWrapper userRoot = context.getUserRoot().get();

        String filename = "mediumfile.bin";
        byte[] data = new byte[0];
        FileWrapper updatedRoot = userRoot.uploadOrReplaceFile(filename, new AsyncReader.ArrayBacked(data), data.length,
                context.network, context.crypto, l -> {}).get();

        //overwrite with 2 chunk file
        byte[] data5 = new byte[10*1024*1024];
        random.nextBytes(data5);
        long t1 = System.currentTimeMillis();
        uploadFileSection(updatedRoot, filename, new AsyncReader.ArrayBacked(data5), 0, data5.length,
                context.network, context.crypto, l -> {}).get();
        long t2 = System.currentTimeMillis();
        LOG.info("Write time per chunk " + (t2-t1)/2 + "mS");
        Assert.assertTrue("Timely write", (t2-t1)/2 < 20000);
    }

    @Test
    public void publiclySharedFile() throws Exception {
        String username = generateUsername();
        String password = "test01";
        UserContext context = PeergosNetworkUtils.ensureSignedUp(username, password, network, crypto);
        FileWrapper userRoot = context.getUserRoot().get();

        String filename = "afile.bin";
        byte[] data = new byte[128*1024];
        random.nextBytes(data);
        uploadFileSection(userRoot, filename, new AsyncReader.ArrayBacked(data), 0, data.length,
                context.network, context.crypto, l -> {}).get();
        String path = "/" + username + "/" + filename;
        FileWrapper file = context.getByPath(path).get().get();
        context.makePublic(file).get();

        FileWrapper publicFile = context.getPublicFile(PathUtil.get(username, filename)).join().get();
        byte[] returnedData = Serialize.readFully(publicFile.getInputStream(context.network, crypto, x -> {}).join(), data.length).join();
        Assert.assertTrue("Correct data returned for publicly shared file", Arrays.equals(data, returnedData));
    }

    @Test
    public void publiclySharedDirectory() throws Exception {
        String username = generateUsername();
        String password = "test01";
        UserContext context = PeergosNetworkUtils.ensureSignedUp(username, password, network, crypto);
        FileWrapper userRoot = context.getUserRoot().get();

        String filename = "afile.bin";
        byte[] data = new byte[128*1024];
        random.nextBytes(data);
        String dirName = "subdir";
        userRoot.mkdir(dirName, network, false, userRoot.mirrorBatId(), crypto).get();
        String dirPath = "/" + username + "/" + dirName;
        FileWrapper subdir = context.getByPath(dirPath).get().get();
        FileWrapper updatedSubdir = uploadFileSection(subdir, filename, new AsyncReader.ArrayBacked(data), 0,
                data.length, context.network, context.crypto, l -> {}).get();
        context.makePublic(updatedSubdir).get();

        FileWrapper publicFile = context.getPublicFile(PathUtil.get(username, dirName, filename)).join().get();
        byte[] returnedData = Serialize.readFully(publicFile.getInputStream(context.network, crypto, x -> {}).join(), data.length).join();
        Assert.assertTrue("Correct data returned for publicly shared file", Arrays.equals(data, returnedData));
    }

    @Test
    public void publicLinkToFile() throws Exception {
        PeergosNetworkUtils.publicLinkToFile(random, network, network);
    }

    @Test
    public void publicLinkToDir() throws Exception {
        String username = generateUsername();
        String password = "test01";
        UserContext context = PeergosNetworkUtils.ensureSignedUp(username, password, network, crypto);
        FileWrapper userRoot = context.getUserRoot().get();

        String filename = "mediumfile.bin";
        byte[] data = new byte[128*1024];
        random.nextBytes(data);
        String dirName = "subdir";
        userRoot.mkdir(dirName, context.network, false, userRoot.mirrorBatId(), context.crypto).get();
        FileWrapper subdir = context.getByPath("/" + username + "/" + dirName).get().get();
        String anotherDirName = "anotherDir";
        subdir.mkdir(anotherDirName, context.network, false, userRoot.mirrorBatId(), context.crypto).get();
        FileWrapper anotherDir = context.getByPath("/" + username + "/" + dirName + "/" + anotherDirName).get().get();
        uploadFileSection(anotherDir, filename, new AsyncReader.ArrayBacked(data), 0, data.length, context.network,
                context.crypto, l -> {}).get();

        String path = "/" + username + "/" + dirName + "/" + anotherDirName;
        FileWrapper theDir = context.getByPath(path).get().get();
        String link = theDir.toLink();
        UserContext linkContext = UserContext.fromSecretLink(link, network, crypto).get();
        String entryPath = linkContext.getEntryPath().get();
        Assert.assertTrue("public link to folder has correct entry path", entryPath.equals(path));

        Optional<FileWrapper> fileThroughLink = linkContext.getByPath(path + "/" + filename).get();
        Assert.assertTrue("File present through link", fileThroughLink.isPresent());

        SharedWithState sharing = context.getDirectorySharingState(PathUtil.get(path)).join();
        Assert.assertTrue("Can retrieve (empty) sharing state in secret link", sharing.isEmpty());
    }

    @Test
    public void writablePublicLinkToDir() throws Exception {
        String username = generateUsername();
        String password = "test01";
        UserContext context = PeergosNetworkUtils.ensureSignedUp(username, password, network, crypto);
        FileWrapper userRoot = context.getUserRoot().get();

        String filename = "mediumfile.bin";
        byte[] data = new byte[128*1024];
        random.nextBytes(data);
        String dirName = "subdir";
        userRoot.mkdir(dirName, context.network, false, userRoot.mirrorBatId(), context.crypto).get();
        FileWrapper subdir = context.getByPath("/" + username + "/" + dirName).get().get();
        String anotherDirName = "anotherDir";
        subdir.mkdir(anotherDirName, context.network, false, userRoot.mirrorBatId(), context.crypto).get();
        FileWrapper anotherDir = context.getByPath("/" + username + "/" + dirName + "/" + anotherDirName).get().get();
        uploadFileSection(anotherDir, filename, new AsyncReader.ArrayBacked(data), 0, data.length, context.network,
                context.crypto, l -> {}).get();

        String path = "/" + username + "/" + dirName + "/" + anotherDirName;
        // move folder to new signing subspace
        context.shareWriteAccessWith(PathUtil.get(path), Collections.emptySet()).join();

        FileWrapper theDir = context.getByPath(path).get().get();
        String link = theDir.toWritableLink();
        UserContext linkContext = UserContext.fromSecretLink(link, network, crypto).get();
        String entryPath = linkContext.getEntryPath().get();
        Assert.assertTrue("public link to folder has correct entry path", entryPath.equals(path));

        Optional<FileWrapper> fileThroughLink = linkContext.getByPath(path + "/" + filename).get();
        Assert.assertTrue("File present through link", fileThroughLink.isPresent());

        Optional<FileWrapper> dirThroughLink = linkContext.getByPath(path).get();
        Assert.assertTrue("dir is writable", dirThroughLink.isPresent() && dirThroughLink.get().isWritable());

        byte[] newData = "Some dataaa".getBytes();
        dirThroughLink.get().uploadFileJS("anoterfile", AsyncReader.build(newData), 0, newData.length,
                false, dirThroughLink.get().mirrorBatId(), linkContext.network, linkContext.crypto, x -> {}, null, f -> Futures.of(false)).join();
    }

    @Test
    public void recursiveDelete() {
        String username = generateUsername();
        String password = "test01";
        UserContext context = PeergosNetworkUtils.ensureSignedUp(username, password, network.clear(), crypto);
        FileWrapper userRoot = context.getUserRoot().join();
        Path home = PathUtil.get(username);

        String foldername = "afolder";
        userRoot.mkdir(foldername, context.network, false, userRoot.mirrorBatId(), crypto).join();
        FileWrapper folder = context.getByPath(home.resolve(foldername)).join().get();

        String subfoldername = "subfolder";
        folder = folder.mkdir(subfoldername, context.network, false, userRoot.mirrorBatId(), crypto).join();
        Path subfolderPath = PathUtil.get(username, foldername, subfoldername);
        FileWrapper subfolder = context.getByPath(subfolderPath).join().get();

        folder.remove(context.getUserRoot().join(), subfolderPath, context).join();

        AbsoluteCapability pointer = subfolder.getPointer().capability;
        CommittedWriterData cwd = network.synchronizer.getValue(pointer.owner, pointer.writer).join().get(pointer.writer);
        Optional<CryptreeNode> subdir = network.getMetadata(cwd.props.get(), pointer).join();
        Assert.assertTrue("Child deleted", ! subdir.isPresent());
    }

    @Test
    public void todoTest() throws Exception {
        String username = generateUsername();
        String password = "test01";
        UserContext context = PeergosNetworkUtils.ensureSignedUp(username, password, network, crypto);
        String todoBoardName = "s_a-m1p2l e";

        TodoListItem item = new TodoListItem("id", LocalDateTime.now(), "text", false);
        TodoListItem item2 = new TodoListItem("id2", LocalDateTime.now(), "text2", true);
        String todoListName = "todoList";

        List<TodoListItem> items = new ArrayList<>();
        items.add(item);
        items.add(item2);
        TodoList list = TodoList.build(todoListName, "1", items);
        List<TodoList> lists = new ArrayList<>();
        lists.add(list);
        TodoBoard updatedBoard = TodoBoard.build(todoBoardName, lists);
        byte[] data = updatedBoard.serialize();
        FileWrapper userRoot = context.getUserRoot().join();
        final String TODO_FILE_EXTENSION = ".todo";
        FileWrapper updatedRoot = userRoot.uploadOrReplaceFile(todoBoardName + TODO_FILE_EXTENSION, new AsyncReader.ArrayBacked(data), data.length,
                context.network, context.crypto, l -> {}).get();

        FileWrapper file = updatedRoot.getChild(todoBoardName + TODO_FILE_EXTENSION, context.crypto.hasher, context.network).join().get();
        long size = file.getSize();
        byte[] retrievedData = Serialize.readFully(file.getInputStream(context.network, context.crypto,
                size, l -> {}).join(), file.getSize()).join();
        updatedBoard = TodoBoard.fromByteArray(retrievedData);
        lists = updatedBoard.getTodoLists();
        assertTrue("lists size", lists.size() == 1);
        TodoList todolist = lists.get(0);
        assertTrue("todoList name", todolist.getName().equals(todoListName));
        List<TodoListItem> todoItems = todolist.getTodoItems();
        assertTrue("size", todoItems.size() == 2);
        assertTrue("item[0]", todoItems.get(0).equals(item));
        assertTrue("item[1]", todoItems.get(1).equals(item2));
    }

    @Test
    public void profileTest() {
        String username = generateUsername();
        String password = "test01";
        UserContext context = PeergosNetworkUtils.ensureSignedUp(username, password, network, crypto);

        String firstName = "john";
        String lastName = "doe";
        String bio = "asleep";
        String phone = "555 5555";
        String email = "joe@doesnt.exist";
        String status = "busy";
        String dir = "webroot";
        Path webroot = PathUtil.get(username, dir);
        String webrootString = "/" + username + "/" + dir;
        context.getUserRoot().join().mkdir(dir, context.network, false, context.mirrorBatId(), crypto).join();

        byte[] thumbnail = randomData(100);
        byte[] hires = randomData(1000);

        ProfilePaths.setFirstName(context, firstName).join();
        ProfilePaths.setLastName(context, lastName).join();
        ProfilePaths.setBio(context, bio).join();
        ProfilePaths.setPhone(context, phone).join();
        ProfilePaths.setEmail(context, email).join();
        ProfilePaths.setStatus(context, status).join();
        ProfilePaths.setWebRoot(context, webrootString).join();
        ProfilePaths.setProfilePhoto(context, thumbnail).join();
        ProfilePaths.setHighResProfilePhoto(context, hires).join();

        assertTrue("Correct value", ProfilePaths.getFirstName(username, context).join().get().equals(firstName));
        assertTrue("Correct value", ProfilePaths.getLastName(username, context).join().get().equals(lastName));
        assertTrue("Correct value", ProfilePaths.getBio(username, context).join().get().equals(bio));
        assertTrue("Correct value", ProfilePaths.getPhone(username, context).join().get().equals(phone));
        assertTrue("Correct value", ProfilePaths.getEmail(username, context).join().get().equals(email));
        assertTrue("Correct value", ProfilePaths.getStatus(username, context).join().get().equals(status));
        assertTrue("Correct value", ProfilePaths.getWebRoot(username, context).join().get().equals(webrootString));
        assertTrue("Correct value", Arrays.equals(ProfilePaths.getProfilePhoto(username, context).join().get(), thumbnail));
        assertTrue("Correct value", Arrays.equals(ProfilePaths.getHighResProfilePhoto(username, context).join().get(), hires));

        Profile profile = ProfilePaths.getProfile(username, context).join();
        assertTrue("Correct value", profile.firstName.get().equals(firstName));
        assertTrue("Correct value", profile.lastName.get().equals(lastName));
        assertTrue("Correct value", profile.bio.get().equals(bio));
        assertTrue("Correct value", profile.phone.get().equals(phone));
        assertTrue("Correct value", profile.email.get().equals(email));
        assertTrue("Correct value", profile.status.get().equals(status));
        assertTrue("Correct value", profile.webRoot.get().equals(webrootString));
        assertTrue("Correct value", Arrays.equals(profile.profilePhoto.get(), thumbnail));

        ProfilePaths.publishWebroot(context).join();
        Optional<FileWrapper> fw = context.getPublicFile(webroot).join();
        assertTrue("webroot", fw.isPresent());

        ProfilePaths.unpublishWebRoot(context).join();
        Optional<FileWrapper> currentCap = context.getPublicFile(webroot).join();
        assertTrue(currentCap.isEmpty());
        ProfilePaths.publishWebroot(context).join();
        Optional<FileWrapper> fw2 = context.getPublicFile(webroot).join();
        assertTrue("webroot", fw2.isPresent());
    }

    @Test
    public void rename() throws Exception {
        String username = generateUsername();
        String password = "test01";
        UserContext context = PeergosNetworkUtils.ensureSignedUp(username, password, network, crypto);
        FileWrapper userRoot = context.getUserRoot().get();

        String dirName = "subdir";
        userRoot.mkdir(dirName, context.network, false, userRoot.mirrorBatId(), context.crypto).get();
        FileWrapper subdir = context.getByPath("/" + username + "/" + dirName).get().get();
        String anotherDirName = "anotherDir";
        subdir.mkdir(anotherDirName, context.network, false, userRoot.mirrorBatId(), context.crypto).get();

        String path = "/" + username + "/" + dirName;
        FileWrapper theDir = context.getByPath(path).get().get();
        FileWrapper userRoot2 = context.getByPath("/" + username).get().get();
        FileWrapper renamed = theDir.rename("subdir2", userRoot2, PathUtil.get(username, dirName), context).get();
    }

    // This one takes a while, so disable most of the time
//    @Test
    public void hugeFolder() throws Exception {
        String username = generateUsername();
        String password = "test01";
        UserContext context = PeergosNetworkUtils.ensureSignedUp(username, password, network, crypto);
        List<String> names = new ArrayList<>();
        int nChildren = 2000;
        IntStream.range(0, nChildren).forEach(i -> names.add(randomString()));

        for (int i=0; i < names.size(); i++) {
            String filename = names.get(i);
            context.getUserRoot().get().mkdir(filename, context.network, false, context.mirrorBatId(), context.crypto);
            Set<FileWrapper> children = context.getUserRoot().get().getChildren(crypto.hasher, context.network).get();
            Assert.assertTrue("All children present", children.size() == i + 3); // 3 due to .keystore and shared
        }
    }

    public static void checkFileContents(byte[] expected, FileWrapper f, UserContext context) {
        long size = f.getFileProperties().size;
        byte[] retrievedData = Serialize.readFully(f.getInputStream(context.network, context.crypto,
            size, l-> {}).join(), f.getSize()).join();
        assertEquals(expected.length, size);
        assertTrue("Correct contents", Arrays.equals(retrievedData, expected));
    }

    private static void checkFileContentsChunked(byte[] expected, FileWrapper f, UserContext context, int  nReads) throws Exception {

        AsyncReader in = f.getInputStream(context.network, context.crypto,
                f.getFileProperties().size, l -> {}).get();
        assertTrue(nReads > 1);

        long size = f.getSize();
        long readLength = size/nReads;

        ByteArrayOutputStream bout = new ByteArrayOutputStream();

        for (int i = 0; i < nReads; i++) {
            long pos = i * readLength;
            long len = i < nReads - 1 ? readLength : expected.length - pos;
            LOG.info("Reading from "+ pos +" to "+ (pos + len) +" with total "+ expected.length);
            byte[] retrievedData = Serialize.readFully(in, len).get();
            bout.write(retrievedData);
        }
        byte[] readBytes = bout.toByteArray();
        assertEquals("Lengths correct", readBytes.length, expected.length);

        String start = ArrayOps.bytesToHex(Arrays.copyOfRange(expected, 0, 10));

        for (int i = 0; i < readBytes.length; i++)
            assertEquals("position  " + i + " out of " + readBytes.length + ", start of file " + start,
                    ArrayOps.byteToHex(readBytes[i] & 0xFF),
                    ArrayOps.byteToHex(expected[i] & 0xFF));

        assertTrue("Correct contents", Arrays.equals(readBytes, expected));
    }


    @Test
    public void readWriteTest() throws Exception {
        String username = generateUsername();
        String password = "test01";
        UserContext context = PeergosNetworkUtils.ensureSignedUp(username, password, network, crypto);
        FileWrapper userRoot = context.getUserRoot().get();

        Set<FileWrapper> children = userRoot.getChildren(crypto.hasher, context.network).get();

        children.stream()
                .map(FileWrapper::toString)
                .forEach(System.out::println);

        String name = randomString();
        byte[] data = randomData(10*1024*1024); // 2 chunks to test block chaining

        AsyncReader resetableFileInputStream = new AsyncReader.ArrayBacked(data);
        FileWrapper updatedRoot = userRoot.uploadOrReplaceFile(name, resetableFileInputStream, data.length,
                context.network, context.crypto, l -> {}).get();

        Optional<FileWrapper> opt = updatedRoot.getChildren(crypto.hasher, context.network).get()
                .stream()
                .filter(e -> e.getFileProperties().name.equals(name))
                .findFirst();

        assertTrue("found uploaded file", opt.isPresent());

        FileWrapper fileWrapper = opt.get();
        long size = fileWrapper.getFileProperties().size;
        AsyncReader in = fileWrapper.getInputStream(context.network, context.crypto, size, (l) -> {}).get();
        byte[] retrievedData = Serialize.readFully(in, fileWrapper.getSize()).get();

        boolean  dataEquals = Arrays.equals(data, retrievedData);

        assertTrue("retrieved same data", dataEquals);
    }

    @Test
    public void deleteTest() throws Exception {
        String username = generateUsername();
        String password = "test01";
        UserContext context = PeergosNetworkUtils.ensureSignedUp(username, password, network.clear(), crypto);
        FileWrapper userRoot = context.getUserRoot().get();

        String name = randomString();
        byte[] data = randomData(10*1024*1024); // 2 chunks to test block chaining

        AsyncReader fileData = new AsyncReader.ArrayBacked(data);

        FileWrapper updatedRoot = userRoot.uploadOrReplaceFile(name, fileData, data.length,
                context.network, context.crypto, l -> {}).get();
        String otherName = name + ".other";

        FileWrapper updatedRoot2 = updatedRoot.uploadOrReplaceFile(otherName, fileData.reset().join(),
                data.length, context.network, context.crypto, l -> {}).get();

        Optional<FileWrapper> opt = updatedRoot2.getChildren(crypto.hasher, context.network).get()
                        .stream()
                        .filter(e -> e.getFileProperties().name.equals(name))
                        .findFirst();

        assertTrue("found uploaded file", opt.isPresent());

        FileWrapper fileWrapper = opt.get();
        long size = fileWrapper.getFileProperties().size;
        AsyncReader in = fileWrapper.getInputStream(context.network, context.crypto, size, (l) -> {}).get();
        byte[] retrievedData = Serialize.readFully(in, fileWrapper.getSize()).get();

        boolean  dataEquals = Arrays.equals(data, retrievedData);

        assertTrue("retrieved same data", dataEquals);

        //delete the file
        fileWrapper.remove(updatedRoot2, PathUtil.get(username, name), context).get();

        //re-create user-context
        UserContext context2 = PeergosNetworkUtils.ensureSignedUp(username, password, network.clear(), crypto);
        FileWrapper userRoot2 = context2.getUserRoot().get();


        //check the file is no longer present
        boolean isPresent = userRoot2.getChildren(crypto.hasher, context2.network).get()
                .stream()
                .anyMatch(e -> e.getFileProperties().name.equals(name));

        Assert.assertFalse("uploaded file is deleted", isPresent);


        //check content of other file in same directory that was not removed
        FileWrapper otherFileWrapper = userRoot2.getChildren(crypto.hasher, context2.network).get()
                .stream()
                .filter(e -> e.getFileProperties().name.equals(otherName))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Missing other file"));

        AsyncReader asyncReader = otherFileWrapper.getInputStream(context2.network, context2.crypto, l -> {}).get();

        byte[] otherRetrievedData = Serialize.readFully(asyncReader, otherFileWrapper.getSize()).get();
        boolean  otherDataEquals = Arrays.equals(data, otherRetrievedData);
        Assert.assertTrue("other file data is  intact", otherDataEquals);
    }

    @Test
    public void bulkDeleteTest() {
        String username = generateUsername();
        String password = "test01";
        UserContext context = PeergosNetworkUtils.ensureSignedUp(username, password, network.clear(), crypto);
        FileWrapper userRoot = context.getUserRoot().join();

        Set<String> filenames = new HashSet<>();

        for (int i=0; i < 20; i++) {
            String name = randomString();
            byte[] data = randomData(8 * 1024);

            AsyncReader fileData = new AsyncReader.ArrayBacked(data);
            userRoot = userRoot.uploadOrReplaceFile(name, fileData, data.length,
                    context.network, context.crypto, l -> {}).join();
            filenames.add(name);
        }

        Set<FileWrapper> kids = userRoot.getChildren(crypto.hasher, context.network).join();
        Set<String> kidNames = kids
                .stream()
                .map(f -> f.getName())
                .collect(Collectors.toSet());

        assertTrue("found uploaded files", kidNames.containsAll(filenames));

        //delete the files
        List<FileWrapper> toDelete = kids.stream()
                .filter(f -> filenames.contains(f.getName()))
                .collect(Collectors.toList());
        FileWrapper.deleteChildren(userRoot, toDelete, PathUtil.get(username), context).join();

        //re-create user-context
        UserContext context2 = PeergosNetworkUtils.ensureSignedUp(username, password, network.clear(), crypto);
        FileWrapper userRoot2 = context2.getUserRoot().join();

        //check the files are no longer present
        List<FileWrapper> remaining = userRoot2.getChildren(crypto.hasher, context2.network).join().stream()
                .filter(f -> filenames.contains(f.getName()))
                .collect(Collectors.toList());
        Assert.assertTrue("uploaded files are deleted", remaining.isEmpty());
    }

    @Test
    public void internalCopy() {
        String username = generateUsername();
        String password = "test01";
        UserContext context = PeergosNetworkUtils.ensureSignedUp(username, password, network.clear(), crypto);
        FileWrapper userRoot = context.getUserRoot().join();
        Path home = PathUtil.get(username);

        String filename = "initialfile.bin";
        byte[] data = randomData(10*1024*1024); // 2 chunks to test block chaining

        FileWrapper updatedUserRoot = userRoot.uploadOrReplaceFile(filename, new AsyncReader.ArrayBacked(data),
                data.length, context.network, crypto, x -> {}).join();

        // copy the file
        String foldername = "afolder";
        updatedUserRoot.mkdir(foldername, context.network, false, userRoot.mirrorBatId(), crypto).join();
        FileWrapper subfolder = context.getByPath(home.resolve(foldername)).join().get();
        FileWrapper original = context.getByPath(home.resolve(filename)).join().get();
        Boolean res = original.copyTo(subfolder, context).join();
        FileWrapper copy = context.getByPath(home.resolve(foldername).resolve(filename)).join().get();
        Assert.assertTrue("Different base key", ! copy.getPointer().capability.rBaseKey.equals(original.getPointer().capability.rBaseKey));
        Assert.assertTrue("Different metadata key", ! getMetaKey(copy).equals(getMetaKey(original)));
        Assert.assertTrue("Different data key", ! getDataKey(copy).equals(getDataKey(original)));
        checkFileContents(data, copy, context);
    }

    @Test
    public void internalCopyDirToDir() {
        String username = generateUsername();
        String password = "test01";
        UserContext context = PeergosNetworkUtils.ensureSignedUp(username, password, network.clear(), crypto);
        FileWrapper userRoot = context.getUserRoot().join();
        Path home = PathUtil.get(username);

        String filename = "initialfile.bin";
        byte[] data = randomData(10*1024*1024); // 2 chunks to test block chaining

        String foldername = "afolder";
        userRoot = userRoot.mkdir(foldername, context.network, false, userRoot.mirrorBatId(), crypto).join();
        String foldername2 = "bfolder";
        userRoot.mkdir(foldername2, context.network, false, userRoot.mirrorBatId(), crypto).join();
        FileWrapper folder2 = context.getByPath(home.resolve(foldername2)).join().get();


        FileWrapper subfolder = context.getByPath(home.resolve(foldername)).join().get();
        subfolder = subfolder.uploadOrReplaceFile(filename, new AsyncReader.ArrayBacked(data),
                data.length, context.network, crypto, x -> {}).join();

        subfolder.copyTo(folder2, context).join();
        Optional<FileWrapper> file = context.getByPath(PathUtil.get(username, foldername2, foldername, filename)).join();
        Assert.assertTrue("File copied in dir", file.isPresent());
    }

    @Test
    public void usage() {
        String username = generateUsername();
        String password = "password";
        Assert.assertTrue(network.instanceAdmin.acceptingSignups().join().free);
        UserContext context = PeergosNetworkUtils.ensureSignedUp(username, password, network, crypto);
        long quota = context.getQuota().join();
        long usage = context.getSpaceUsage().join();
        Assert.assertTrue("non zero quota", quota > 0);
        Assert.assertTrue("non zero space usage", usage > 0);

        CompletableFuture<List<SpaceUsage.LabelledSignedSpaceRequest>> nonAdmin = context.getPendingSpaceRequests();

        Assert.assertTrue("Non admins get an empty list", nonAdmin.join().isEmpty());

        // Now let's request some more quota and get it approved by an admin
        context.requestSpace(quota * 2).join();

        // retrieve, decode and approve request as admin
        UserContext admin = PeergosNetworkUtils.ensureSignedUp("peergos", "testpassword", network.clear(), crypto);
        List<SpaceUsage.LabelledSignedSpaceRequest> spaceReqs = admin.getPendingSpaceRequests().join();
        List<DecodedSpaceRequest> parsed = admin.decodeSpaceRequests(spaceReqs).join();
        DecodedSpaceRequest req = parsed.stream().filter(r -> r.getUsername().equals(username)).findFirst().get();
        admin.approveSpaceRequest(req).join();

        long updatedQuota = context.getQuota().join();
        Assert.assertTrue("Quota updated " + updatedQuota + " != 2 * " + quota, updatedQuota == 2 * quota);
    }

    @Test
    public void correctUsageAndSpaceRecovery() throws Exception {
        String username = generateUsername();
        String password = "password";
        UserContext context = PeergosNetworkUtils.ensureSignedUp(username, password, network, crypto);
        long initialUsage = context.getSpaceUsage().join();

        UserCleanup.checkRawUsage(context);
        String filename = "test.bin";
        context.getUserRoot().join().uploadFileJS(filename, AsyncReader.build(new byte[10*1024*1024]),
                0, 10*1024*1024, true, context.mirrorBatId(), network, crypto, x-> {},
                context.getTransactionService(), f -> Futures.of(true)).join();
        String dirName = "subdir";
        context.getUserRoot().join().mkdir(dirName, network, false, context.mirrorBatId(), crypto).join();
        Thread.sleep(5_000); // Allow time for space usage recalculation
        UserCleanup.checkRawUsage(context);

        // now delete the file and dir
        Path filePath = PathUtil.get(username, filename);
        context.getByPath(filePath).join().get().remove(context.getUserRoot().join(), filePath, context).join();
        Path dirPath = PathUtil.get(username, dirName);
        context.getByPath(dirPath).join().get().remove(context.getUserRoot().join(), dirPath, context).join();
        try {Thread.sleep(2000);} catch (InterruptedException e) {}
        UserCleanup.checkRawUsage(context);

        long finalUsage = context.getSpaceUsage().join();
        long diff = finalUsage - initialUsage;
        Assert.assertTrue(diff == 0);
    }

    @Test
    public void serverMessaging() {
        String username = generateUsername();
        String password = "password";
        UserContext context = PeergosNetworkUtils.ensureSignedUp(username, password, network, crypto);

        String serverMsgBody = "Welcome to the world of Peergos!";
        ServerMessageStore msgStore = (ServerMessageStore) service.serverMessages;
        msgStore.addMessage(username, new ServerMessage(1, ServerMessage.Type.FromServer,
                System.currentTimeMillis(), serverMsgBody, Optional.empty(), false));

        String replyBody = "Thanks for making Peergos awesome!";
        context.sendReply(context.getNewMessages().join().get(0), replyBody).join();
        List<ServerMessage> afterReply = context.getNewMessages().join();
        Assert.assertTrue(afterReply.size() == 0);

        String msgBody = "Peergos really is amazing! I love it!";
        context.sendFeedback(msgBody).join();
        List<ServerMessage> messages = context.getNewMessages().join();
        Assert.assertTrue(messages.size() == 0);

        List<ServerMessage> onServer = msgStore.getMessages(username);
        Assert.assertTrue(onServer.size() == 3);
        ServerMessage reply = onServer.get(2);
        Assert.assertTrue(reply.contents.equals(msgBody));

        List<ServerConversation> convs = context.getServerConversations().join();
        Assert.assertTrue(convs.size() == 0);

        msgStore.addMessage(username, new ServerMessage(1, ServerMessage.Type.FromServer,
                System.currentTimeMillis(), "Thank you for supporting Peergos.", Optional.empty(), false));
        context.dismissMessage(context.getNewMessages().join().get(0)).join();
        List<ServerMessage> updatedMessages = context.getNewMessages().join();
        Assert.assertTrue(updatedMessages.size() == 0);
        // Test that we get rate limited
        try {
            for (int i = 0; i < 20; i++)
                context.sendFeedback("SPAM " + i).join();
            Assert.fail();
        } catch (RuntimeException e) {}
    }

    public static SymmetricKey getDataKey(FileWrapper file) {
        return file.getPointer().fileAccess.getDataKey(file.getPointer().capability.rBaseKey);
    }

    public static SymmetricKey getMetaKey(FileWrapper file) {
        return file.getPointer().capability.rBaseKey;
    }

    @Test
    public void deleteDirectoryTest() throws Exception {
        String username = generateUsername();
        String password = "test01";
        UserContext context = PeergosNetworkUtils.ensureSignedUp(username, password, network, crypto);
        FileWrapper userRoot = context.getUserRoot().get();

        Set<FileWrapper> children = userRoot.getChildren(crypto.hasher, context.network).get();

        children.stream()
                .map(FileWrapper::toString)
                .forEach(System.out::println);

        String folderName = "a_folder";
        boolean isSystemFolder = false;

        //create the directory
        userRoot.mkdir(folderName, context.network, isSystemFolder, userRoot.mirrorBatId(), context.crypto).get();

        FileWrapper updatedUserRoot = context.getUserRoot().get();
        FileWrapper directory = updatedUserRoot.getChildren(crypto.hasher, context.network)
                .get()
                .stream()
                .filter(e -> e.getFileProperties().name.equals(folderName))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Missing created folder " + folderName));

        // check the parent link doesn't include write access
        AbsoluteCapability cap = directory.getPointer().capability;
        CryptreeNode fileAccess = directory.getPointer().fileAccess;
        RelativeCapability toParent = fileAccess.getParentCapability(fileAccess.getParentKey(cap.rBaseKey)).get();
        Assert.assertTrue("parent link shouldn't include write access",
                ! toParent.wBaseKeyLink.isPresent());
        Assert.assertTrue("parent link shouldn't include public write key",
                ! toParent.writer.isPresent());

        //remove the directory
        directory.remove(updatedUserRoot, PathUtil.get(username, folderName), context).get();

        //ensure folder directory not  present
        boolean isPresent = context.getUserRoot().get().getChildren(crypto.hasher, context.network)
                .get()
                .stream()
                .filter(e -> e.getFileProperties().name.equals(folderName))
                .findFirst()
                .isPresent();

        Assert.assertFalse("folder not present after remove", isPresent);

        //can sign-in again
        try {
            UserContext context2 = PeergosNetworkUtils.ensureSignedUp(username, password, network, crypto);
            FileWrapper userRoot2 = context2.getUserRoot().get();
        } catch (Exception ex) {
            fail("Failed to log-in and see user-root " + ex.getMessage());
        }

    }

    @Test
    public void overwriteContentsOfFileGrowFile() throws Exception {
        String username = generateUsername();
        String password = "test";
        UserContext context = PeergosNetworkUtils.ensureSignedUp(username, password, network, crypto);
        FileWrapper userRoot = context.getUserRoot().get();

        String filename = "somedata.txt";
        Path filePath = PathUtil.get(username, filename);
        byte[] data = randomData(6);
        userRoot.uploadOrReplaceFile(filename, new AsyncReader.ArrayBacked(data), data.length, context.network,
                context.crypto, l -> {}).get();
        checkFileContents(data, context.getByPath(filePath).join().get(), context);

        FileWrapper fileV2 = context.getByPath(filePath).join().get();

        byte[] bytes = "11111111".getBytes();
        AsyncReader java_reader = peergos.shared.user.fs.AsyncReader.build(bytes);
        int newSizeLo = bytes.length;
        fileV2.overwriteFileJS(java_reader, 0, newSizeLo,
                context.network, context.crypto, len -> {}).join();

        checkFileContents(bytes, context.getByPath(filePath).join().get(), context);
    }

    @Test
    public void overwriteContentsOfFileShrinkFile() throws Exception {
        String username = generateUsername();
        String password = "test";
        UserContext context = PeergosNetworkUtils.ensureSignedUp(username, password, network, crypto);
        FileWrapper userRoot = context.getUserRoot().get();

        String filename = "somedata.txt";
        Path filePath = PathUtil.get(username, filename);
        byte[] data = randomData(6000);
        for(int i=0; i < data.length; i++) {
            if(data[i] == 0) {
                data[i] = 1;
            }
        }
        userRoot.uploadOrReplaceFile(filename, new AsyncReader.ArrayBacked(data), data.length, context.network,
                context.crypto, l -> {}).get();
        checkFileContents(data, context.getByPath(filePath).join().get(), context);

        FileWrapper fileV2 = context.getByPath(filePath).join().get();

        byte[] bytes = "11111111".getBytes();
        AsyncReader java_reader = peergos.shared.user.fs.AsyncReader.build(bytes);
        int newSizeLo = bytes.length;
        fileV2.overwriteFileJS(java_reader, 0, newSizeLo,
                context.network, context.crypto, len -> {}).join();

        checkFileContents(bytes, context.getByPath(filePath).join().get(), context);

        FileWrapper fileV3 = context.getByPath(filePath).join().get();
        byte[] retrievedData = Serialize.readFully(fileV3.getInputStream(context.network, context.crypto,
                6000, l-> {}).join(), 6000).join();
        int nonZeroBytes = (int)IntStream.range(0, retrievedData.length).map(i-> retrievedData[i]).filter(a -> a != 0).count();
        Assert.assertTrue("File truncated", nonZeroBytes == bytes.length);


    }

    public static String randomString() {
        return UUID.randomUUID().toString();
    }

    public static byte[] randomData(int length) {
        byte[] data = new byte[length];
        random.nextBytes(data);
        return data;
    }

    private static Path TMP_DIR = PathUtil.get("test","resources","tmp");

    private static void ensureTmpDir() {
        File dir = TMP_DIR.toFile();
        if (! dir.isDirectory() &&  ! dir.mkdirs())
            throw new IllegalStateException("Could not find or create specified tmp directory "+ TMP_DIR);
    }

    private static Path createTmpFile(String filename) throws IOException {
        ensureTmpDir();
        Path resolve = TMP_DIR.resolve(filename);
        File file = resolve.toFile();
        file.createNewFile();
        file.deleteOnExit();
        return resolve;
    }
}
