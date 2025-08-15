package peergos.server.tests;

import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import peergos.server.Main;
import peergos.server.UserService;
import peergos.server.util.Args;
import peergos.server.util.JavaPoster;
import peergos.shared.NetworkAccess;
import peergos.shared.OnlineState;
import peergos.shared.corenode.OfflineCorenode;
import peergos.shared.crypto.asymmetric.PublicBoxingKey;
import peergos.shared.crypto.asymmetric.PublicSigningKey;
import peergos.shared.crypto.asymmetric.curve25519.Curve25519PublicKey;
import peergos.shared.crypto.asymmetric.mlkem.HybridCurve25519MLKEMPublicKey;
import peergos.shared.crypto.hash.PublicKeyHash;
import peergos.shared.io.ipfs.Cid;
import peergos.shared.login.LoginCache;
import peergos.shared.login.OfflineAccountStore;
import peergos.shared.storage.*;
import peergos.shared.user.*;
import peergos.shared.util.Futures;
import peergos.shared.util.Pair;

import java.net.URI;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.CompletableFuture;

@RunWith(Parameterized.class)
public class BrowserLegacyToPQTest extends UserTests {
    private static Args args = buildArgs().with("useIPFS", "false");

    public BrowserLegacyToPQTest(NetworkAccess network, UserService service) {
        super(network, service);
    }

    @Parameterized.Parameters()
    public static Collection<Object[]> parameters() throws Exception {
        UserService service = Main.PKI_INIT.main(args).localApi;
        // use actual http messager
        ServerMessager.HTTP serverMessager = new ServerMessager.HTTP(new JavaPoster(new URI("http://localhost:" + args.getArg("port")).toURL(), false));
        NetworkAccess orig = NetworkAccess.buildBuffered(service.storage, service.bats, service.coreNode, service.account, service.mutable,
                5_000, service.social, service.controller, service.usage, serverMessager, crypto.hasher, Arrays.asList("peergos"), false);
        OnlineState onlineState = new OnlineState(() -> orig.dhtClient.id()
                .thenApply(x -> true)
                .exceptionally(t -> false));
        NetworkAccess network = orig
                .withStorage(s -> new UnauthedCachingStorage(s, new RamCache(), crypto.hasher))
                .withAccountCache(a -> new OfflineAccountStore(orig.account, new RamAccountCache(), onlineState));
        return Arrays.asList(new Object[][] {
                {network, service}
        });
    }

    public static class RamAccountCache implements LoginCache {
        private final Map<String, LoginData> cache = new HashMap<>();

        @Override
        public CompletableFuture<Boolean> setLoginData(LoginData login) {
            cache.put(login.username, login);
            return Futures.of(true);
        }

        @Override
        public CompletableFuture<Boolean> removeLoginData(String username) {
            cache.remove(username);
            return Futures.of(true);
        }

        @Override
        public CompletableFuture<UserStaticData> getEntryData(String username, PublicSigningKey authorisedReader) {
            return Futures.of(cache.get(username).entryPoints);
        }
    }

    public static class RamCache implements BlockCache {
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

    @Test
    public void legacyToPQ() throws Exception {
        String username = generateUsername();
        String password = "password";
        List<String> progress = new ArrayList<>();
        UserContext userContext = UserContext.signUpGeneral(username, password, "", Optional.empty(), id -> {},
                Optional.empty(), LocalDate.now().plusMonths(2),
                network, crypto, SecretGenerationAlgorithm.getLegacy(crypto.random), progress::add).join();
        SecretGenerationAlgorithm originalAlg = WriterData.fromCbor(UserContext.getWriterDataCbor(network, username).join().right).generationAlgorithm.get();
        Assert.assertTrue("legacy accounts generate boxer", originalAlg.generateBoxerAndIdentity());
        Pair<PublicKeyHash, PublicBoxingKey> keyPairs = userContext.getPublicKeys(username).join().get();
        PublicBoxingKey initialBoxer = keyPairs.right;
        PublicKeyHash initialIdentity = keyPairs.left;
        WriterData initialWd = WriterData.getWriterData(initialIdentity, initialIdentity, network.mutable, network.dhtClient).join().props.get();
        Assert.assertTrue(initialWd.staticData.isPresent());
        Assert.assertTrue(initialBoxer instanceof Curve25519PublicKey);

        String newPassword = "newPassword";
        userContext.changePassword(password, newPassword, UserTests::noMfa).get();
        MultiUserTests.checkUserValidity(network, username);

        List<String> progress2 = new ArrayList<>();
        // changing password also upgrade to PQ
        UserContext changedPassword = UserContext.signIn(username, newPassword, UserTests::noMfa, false, network, crypto, progress2::add).join();
        Pair<PublicKeyHash, PublicBoxingKey> newKeyPairs = changedPassword.getPublicKeys(username).join().get();
        PublicBoxingKey newBoxer = newKeyPairs.right;
        PublicKeyHash newIdentity = newKeyPairs.left;
//        Assert.assertTrue(! newBoxer.equals(initialBoxer));
        Assert.assertTrue(! newIdentity.equals(initialIdentity));

        SecretGenerationAlgorithm alg = WriterData.fromCbor(UserContext.getWriterDataCbor(network, username).join().right).generationAlgorithm.get();
        Assert.assertTrue("password change upgrades legacy accounts", ! alg.generateBoxerAndIdentity());
        WriterData finalWd = WriterData.getWriterData(newIdentity, newIdentity, network.mutable, network.dhtClient).join().props.get();
        Assert.assertTrue(finalWd.staticData.isEmpty());

        UserContext pq = UserContext.signIn(username, newPassword, UserTests::noMfa, network, crypto).join();
        PublicBoxingKey pqBoxer = pq.getPublicKeys(username).join().get().right;
        Assert.assertTrue(pqBoxer.equals(newKeyPairs.right));

        // change password again
        String newerPassword = "greentrees";
        UserContext secondPassChange = pq.changePassword(newPassword, newerPassword, UserTests::noMfa).join();
        UserContext relogin = UserContext.signIn(username, newerPassword, UserTests::noMfa, network, crypto).join();
        PublicBoxingKey finalPqBoxer = relogin.getPublicKeys(username).join().get().right;
        Assert.assertTrue(finalPqBoxer.equals(pqBoxer));

        relogin.ensurePostQuantum(password, UserTests::noMfa, m -> {}).join();
        Assert.assertTrue(pqBoxer instanceof HybridCurve25519MLKEMPublicKey);

    }
}
