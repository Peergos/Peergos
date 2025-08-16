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
import peergos.shared.crypto.asymmetric.mlkem.HybridCurve25519MLKEMSecretKey;
import peergos.shared.crypto.hash.PublicKeyHash;
import peergos.shared.user.*;
import peergos.shared.util.Pair;

import java.net.URI;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;

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
        NetworkAccess network = NetworkAccess.buildBuffered(service.storage, service.bats, service.coreNode, service.account, service.mutable,
                5_000, service.social, service.controller, service.usage, serverMessager, crypto.hasher, Arrays.asList("peergos"), false);
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

    @Test
    public void legacyToV2ToPQ() throws Exception {
        String username = generateUsername();
        String password = "password";
        List<String> progress = new ArrayList<>();
        UserContext v1 = UserContext.signUpGeneral(username, password, "", Optional.empty(), id -> {},
                Optional.empty(), LocalDate.now().plusMonths(2),
                network, crypto, SecretGenerationAlgorithm.getLegacy(crypto.random), progress::add).join();
        SecretGenerationAlgorithm originalAlg = WriterData.fromCbor(UserContext.getWriterDataCbor(network, username).join().right).generationAlgorithm.get();
        Assert.assertTrue("legacy accounts generate boxer", originalAlg.generateBoxerAndIdentity());
        Pair<PublicKeyHash, PublicBoxingKey> keyPairs = v1.getPublicKeys(username).join().get();
        PublicBoxingKey initialBoxer = keyPairs.right;
        PublicKeyHash initialIdentity = keyPairs.left;
        WriterData initialWd = WriterData.getWriterData(initialIdentity, initialIdentity, network.mutable, network.dhtClient).join().props.get();
        Assert.assertTrue(initialWd.staticData.isPresent());
        Assert.assertTrue(initialBoxer instanceof Curve25519PublicKey);

        String newPassword = "newPassword";
        v1.changePassword(password, newPassword, UserTests::noMfa).get();
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

        UserContext withNewPassword = UserContext.signIn(username, newPassword, UserTests::noMfa, network, crypto).join();
        PublicBoxingKey pqBoxer = withNewPassword.getPublicKeys(username).join().get().right;
        Assert.assertTrue(pqBoxer.equals(newKeyPairs.right));

        withNewPassword.ensurePostQuantum(password, UserTests::noMfa, m -> {}).join();
        Assert.assertTrue(pqBoxer instanceof HybridCurve25519MLKEMPublicKey);
    }

    @Test
    public void legacyDirectToPQ() throws Exception {
        String username = generateUsername();
        String password = "password";
        List<String> progress = new ArrayList<>();
        UserContext v1 = UserContext.signUpGeneral(username, password, "", Optional.empty(), id -> {},
                Optional.empty(), LocalDate.now().plusMonths(2),
                network, crypto, SecretGenerationAlgorithm.getLegacy(crypto.random), progress::add).join();
        SecretGenerationAlgorithm originalAlg = WriterData.fromCbor(UserContext.getWriterDataCbor(network, username).join().right).generationAlgorithm.get();
        Assert.assertTrue("legacy accounts generate boxer", originalAlg.generateBoxerAndIdentity());
        Pair<PublicKeyHash, PublicBoxingKey> keyPairs = v1.getPublicKeys(username).join().get();
        PublicBoxingKey initialBoxer = keyPairs.right;
        PublicKeyHash initialIdentity = keyPairs.left;
        WriterData initialWd = WriterData.getWriterData(initialIdentity, initialIdentity, network.mutable, network.dhtClient).join().props.get();
        Assert.assertTrue(initialWd.staticData.isPresent());
        Assert.assertTrue(initialBoxer instanceof Curve25519PublicKey);

        MultiUserTests.checkUserValidity(network, username);

        List<String> progress2 = new ArrayList<>();

        v1.ensurePostQuantum(password, UserTests::noMfa, m -> {}).join();
        UserContext pq = UserContext.signIn(username, password, UserTests::noMfa, false, network, crypto, progress2::add).join();
        SecretGenerationAlgorithm alg = WriterData.fromCbor(UserContext.getWriterDataCbor(network, username).join().right).generationAlgorithm.get();
//        Assert.assertTrue("password change upgrades legacy accounts", ! alg.generateBoxerAndIdentity());
        Pair<PublicKeyHash, PublicBoxingKey> newKeyPairs = pq.getPublicKeys(username).join().get();
        PublicBoxingKey newBoxer = newKeyPairs.right;
        Assert.assertTrue(newBoxer instanceof HybridCurve25519MLKEMPublicKey);
        PublicKeyHash newIdentity = newKeyPairs.left;
        Assert.assertTrue(pq.boxer.secretBoxingKey instanceof HybridCurve25519MLKEMSecretKey);
        Assert.assertTrue(newBoxer instanceof HybridCurve25519MLKEMPublicKey);

        WriterData finalWd = WriterData.getWriterData(newIdentity, newIdentity, network.mutable, network.dhtClient).join().props.get();
//        Assert.assertTrue(finalWd.staticData.isEmpty());

        // check a fresh login has same keys
        UserContext pq2 = UserContext.signIn(username, password, UserTests::noMfa, false, network, crypto, progress2::add).join();
        Assert.assertArrayEquals(pq2.boxer.serialize(), pq.boxer.serialize());
        Assert.assertArrayEquals(pq2.signer.serialize(), pq.signer.serialize());
    }
}
