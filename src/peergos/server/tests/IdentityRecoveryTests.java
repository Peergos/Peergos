package peergos.server.tests;

import org.junit.*;
import peergos.server.*;
import peergos.server.storage.*;
import peergos.server.util.*;
import peergos.shared.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.*;
import peergos.shared.mutable.*;
import peergos.shared.routing.*;
import peergos.shared.user.*;
import peergos.shared.user.fs.*;
import peergos.shared.storage.*;
import peergos.shared.util.*;

import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

import static peergos.server.tests.UserTests.deleteFiles;

/** Recovering an account with its recovery key alone, after a thief had both its password and identity key, and
 *  moving an identity to a new server in the routing log. */
public class IdentityRecoveryTests {
    private static final Crypto crypto = Main.initCrypto();
    private static Args args;
    private static ServerProcesses server;
    private static int port;

    @BeforeClass
    public static void init() {
        args = UserTests.buildArgs()
                .with("useIPFS", "true")
                .with("async-bootstrap", "true")
                .with(peergos.server.routing.RoutingService.ARG_ROUTING_LOG.name, "true")
                .with(peergos.server.routing.RoutingService.ARG_ROUTING_WORK_BITS.name, "16")
                .removeArg(IpfsWrapper.IPFS_BOOTSTRAP_NODES);
        server = Main.PKI_INIT.main(args);
        port = args.getInt("port");
    }

    @AfterClass
    public static void cleanup() {
        try { server.localApi.stop(); } catch (Exception e) {}
        try { server.p2pApi.stop(); } catch (Exception e) {}
        try { if (server.ipfs != null) server.ipfs.stop(); } catch (Exception e) {}
        deleteFiles(args.fromPeergosDir("", "").toFile());
    }

    @Test
    public void aLoginCanNameTheServerItsUsernameBelongsTo() throws Exception {
        String username = "named" + (System.currentTimeMillis() % 100_000);
        NetworkAccess net = Builder.buildLocalJavaNetworkAccess(port).join();
        UserContext user = UserContext.signUp(username, "password", "", net, crypto).join();
        Cid server = net.dhtClient.id().join();

        UserContext named = UserContext.signIn(username + "@" + server, "password", UserTests::noMfa,
                Builder.buildLocalJavaNetworkAccess(port).join(), crypto).join();
        Assert.assertEquals(username, named.username);
        Assert.assertEquals(user.signer.publicKeyHash, named.signer.publicKeyHash);
        Assert.assertEquals(username + "@" + server, LoginName.parse(user.getMigrationId().join()).toString());

        Cid elsewhere = Cid.decodePeerId(ServerKeys.peerId(SigningKeyPair.random(crypto.random, crypto.signer).publicSigningKey).toString());
        Assert.assertTrue("a server that can't be reached knows no one", failed(() -> UserContext.signIn(username + "@" + elsewhere,
                "password", UserTests::noMfa, Builder.buildLocalJavaNetworkAccess(port).join(), crypto).join()));
        Assert.assertTrue(failed(() -> LoginName.parse(username + "@not-a-peerid")));
        Assert.assertTrue(failed(() -> LoginName.parse("Not Valid@" + server)));
    }

    @Test
    public void aMoveNamesTheNewServerOnlyWhenSignedByTheCurrentKey() throws Exception {
        String username = "move" + (System.currentTimeMillis() % 100_000);
        UserContext user = UserContext.signUp(username, "password", "", Builder.buildLocalJavaNetworkAccess(port).join(), crypto).join();
        PublicKeyHash identity = user.signer.publicKeyHash;
        Assert.assertFalse("nothing to move before the identity commits", user.publishMove().join());

        Assert.assertTrue(user.commitRecoveryKey("abandon-ability-able-about-above-absent-absorb").join());
        IdentityRouting routing = user.network.identityRouting;
        IdentityRouting.Status status = routing.status(identity).join();
        Assert.assertTrue("the inception names the admitting server", status.listed);
        Assert.assertFalse(user.checkRoutingLog().join());
        Multihash here = status.server.get();
        Assert.assertTrue(user.publishMove().join());
        Assert.assertTrue("a later move supersedes it", user.publishMove().join());
        List<IdentityMove> moves = routing.records(identity).join().stream()
                .filter(r -> r instanceof IdentityMove)
                .map(r -> (IdentityMove) r)
                .collect(Collectors.toList());
        Assert.assertEquals(1, moves.size());
        Assert.assertEquals(List.of(here), moves.get(0).providers);

        PublicSigningKey current = user.network.dhtClient.getSigningKey(identity, identity).join().get();
        SigningKeyPair owner = new SigningKeyPair(current, user.signer.secret);
        long counter = moves.get(0).counter + 1;
        Multihash elsewhere = ServerKeys.peerId(SigningKeyPair.random(crypto.random, crypto.signer).publicSigningKey);
        Assert.assertTrue("a move here names only this server", failed(() -> routing.move(identity,
                IdentityMove.build(owner, List.of(elsewhere), counter).join()).join()));
        SigningKeyPair stranger = SigningKeyPair.random(crypto.random, crypto.signer);
        Assert.assertTrue("only the current key moves the identity", failed(() -> routing.move(identity,
                IdentityMove.build(stranger, List.of(here), counter).join()).join()));
    }

    @Test
    public void theRecoveryKeyAloneTakesBackAnAccountAThiefHolds() throws Exception {
        String username = "recover" + (System.currentTimeMillis() % 100_000);
        String password = "first-password", recoveryKey = "abandon-ability-able-about-above-absent-absorb";
        UserContext user = UserContext.signUp(username, password, "", Builder.buildLocalJavaNetworkAccess(port).join(), crypto).join();
        byte[] data = "only the owner should control this".getBytes();
        user.getUserRoot().join().uploadOrReplaceFile("secret.txt", AsyncReader.build(data), data.length,
                user.network, crypto, () -> false, l -> {}).join();

        Assert.assertEquals("unset", user.recoveryKeyStatus().join());
        Assert.assertTrue(user.commitRecoveryKey(recoveryKey).join());
        Assert.assertEquals("set", user.recoveryKeyStatus().join());

        UserContext thief = UserContext.signIn(username, password, UserTests::noMfa,
                Builder.buildLocalJavaNetworkAccess(port).join(), crypto).join();
        thief = thief.changePassword(password, "thiefs-password", UserTests::noMfa).join();
        CommittedWriterData earlier = WriterData.getWriterData(thief.signer.publicKeyHash, thief.signer.publicKeyHash,
                thief.network.mutable, thief.network.dhtClient).join();

        String newRecoveryKey = "zoo-zone-zero-youth-young-you-yellow";
        UserContext recovered = UserContext.recoverAccount(username, recoveryKey, "new-password", newRecoveryKey,
                Builder.buildLocalJavaNetworkAccess(port).join(), crypto, s -> {}).join();
        Assert.assertNotEquals(thief.signer.secret, recovered.signer.secret);
        Assert.assertEquals(thief.signer.publicKeyHash, recovered.signer.publicKeyHash);
        UserTests.checkFileContents(data, recovered.getByPath(PathUtil.get(username, "secret.txt")).join().get(), recovered);
        Assert.assertEquals("set", recovered.recoveryKeyStatus().join());

        Assert.assertTrue("the thief's password no longer logs in", failed(() -> UserContext.signIn(username, "thiefs-password",
                UserTests::noMfa, Builder.buildLocalJavaNetworkAccess(port).join(), crypto).join()));
        Assert.assertTrue("the used recovery key no longer recovers", failed(() -> UserContext.recoverAccount(username, recoveryKey,
                "x", "y", Builder.buildLocalJavaNetworkAccess(port).join(), crypto, s -> {}).join()));

        UserContext again = UserContext.signIn(username, "new-password", UserTests::noMfa,
                Builder.buildLocalJavaNetworkAccess(port).join(), crypto).join();
        UserTests.checkFileContents(data, again.getByPath(PathUtil.get(username, "secret.txt")).join().get(), again);

        // last, since pointing the owner at an earlier WriterData changes how its password logs in
        NetworkAccess net = Builder.buildLocalJavaNetworkAccess(port).join();
        PublicKeyHash owner = recovered.signer.publicKeyHash;
        CommittedWriterData current = WriterData.getWriterData(owner, owner, net.mutable, net.dhtClient).join();
        PointerUpdate cas = new PointerUpdate(current.hash, earlier.hash, PointerUpdate.increment(current.sequence));
        byte[] byThief = thief.signer.secret.signMessage(cas.serialize()).join();
        Assert.assertFalse("the old identity key no longer signs for the identity", net.mutable.setPointer(owner, owner, byThief).join());
        byte[] byOwner = recovered.signer.secret.signMessage(cas.serialize()).join();
        Assert.assertTrue("the same update signed by the new key is taken", net.mutable.setPointer(owner, owner, byOwner).join());
    }

    private static String failure(Runnable r) {
        try {
            r.run();
            return null;
        } catch (Exception e) {
            Throwable t = e;
            while (t.getCause() != null)
                t = t.getCause();
            return t.getMessage() == null ? t.toString() : t.getMessage();
        }
    }

    private static boolean failed(Runnable r) {
        try {
            r.run();
            return false;
        } catch (Exception e) {
            return true;
        }
    }
}
