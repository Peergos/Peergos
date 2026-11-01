package peergos.server.tests;

import org.junit.*;
import peergos.server.*;
import peergos.server.util.*;
import peergos.shared.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.*;
import peergos.shared.routing.*;
import peergos.shared.social.*;
import peergos.shared.storage.*;
import peergos.shared.user.*;

import java.time.*;
import java.util.*;

import static peergos.server.tests.UserTests.deleteFiles;

/** Friend links (remove-pki §8): the bundle a link names, verified before use, and the server serving it and taking
 *  requests to it only while the link is live. */
public class FriendLinkTests {
    private static final Crypto crypto = Main.initCrypto();
    private static Args args;
    private static ServerProcesses server;
    private static int port;

    @BeforeClass
    public static void init() {
        args = UserTests.buildArgs();
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

    private static UserContext signUp(String prefix) {
        String username = prefix + (System.currentTimeMillis() % 100_000);
        return UserContext.signUp(username, "password", "", Builder.buildLocalJavaNetworkAccess(port).join(), crypto).join();
    }

    @Test
    public void aLinkFetchesItsSignedBundle() {
        UserContext alice = signUp("fla");
        UserContext bob = signUp("flb");
        FriendLink link = alice.createFriendLink("for bob", Optional.empty(), 0).join();
        Assert.assertEquals(link, FriendLink.fromLink("https://example.com/#" + link.toLink()));

        FriendLinkBundle bundle = bob.fetchFriendLink(link.toLink()).join();
        Assert.assertEquals(alice.signer.publicKeyHash, bundle.identity);
        Assert.assertArrayEquals(link.linkId, bundle.linkId);
        Assert.assertEquals(alice.username, bundle.suggestedName);
        FriendLinkStore.Entry entry = alice.getFriendLinks().join().stream()
                .filter(e -> Arrays.equals(e.linkId, link.linkId))
                .findFirst().get();
        Assert.assertEquals("for bob", entry.label);
        Assert.assertEquals(entry.boxer.publicBoxingKey, bundle.boxer);
        Assert.assertTrue("a request can't be sent to yourself", failed(() -> alice.fetchFriendLink(link.toLink()).join()));
    }

    @Test
    public void aRevokedLinkLooksLikeOneThatNeverExisted() {
        UserContext alice = signUp("flr");
        UserContext bob = signUp("fls");
        FriendLink link = alice.createFriendLink("", Optional.empty(), 0).join();
        FriendLinkStore.Entry entry = alice.getFriendLinks().join().get(0);
        Assert.assertTrue(alice.revokeFriendLink(entry).join());
        Assert.assertTrue(alice.getFriendLinks().join().isEmpty());

        FriendLink unknown = new FriendLink(link.identity, crypto.random.randomBytes(FriendLinkBundle.LINK_ID_BYTES), Optional.empty());
        String revokedError = error(() -> bob.fetchFriendLink(link.toLink()).join());
        String unknownError = error(() -> bob.fetchFriendLink(unknown.toLink()).join());
        Assert.assertTrue(revokedError, revokedError.contains(UserContext.FRIEND_LINK_UNAVAILABLE));
        Assert.assertEquals(revokedError, unknownError);
        Assert.assertTrue(bob.network.social.getFriendLinkBundle(link.identity, link.linkId).join().isEmpty());
    }

    @Test
    public void anExpiredLinkIsNotServed() {
        UserContext alice = signUp("fle");
        UserContext bob = signUp("flf");
        FriendLink link = alice.createFriendLink("", Optional.of(LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1)), 0).join();
        Assert.assertTrue(bob.network.social.getFriendLinkBundle(link.identity, link.linkId).join().isEmpty());
    }

    @Test
    public void aBundleNotSignedByTheIdentityOrForAnotherLinkIsRejected() throws Exception {
        UserContext alice = signUp("flt");
        UserContext bob = signUp("flu");
        FriendLink link = alice.createFriendLink("", Optional.empty(), 0).join();
        FriendLinkBundle.Signed real = bob.network.social.getFriendLinkBundle(link.identity, link.linkId).join().get();
        Assert.assertNotNull(UserContext.verifyFriendLinkBundle(link, real, bob.network).join());

        SigningKeyPair other = SigningKeyPair.random(crypto.random, crypto.signer);
        FriendLinkBundle.Signed forged = real.bundle.sign(other.secretSigningKey).join();
        Assert.assertTrue(failed(() -> UserContext.verifyFriendLinkBundle(link, forged, bob.network).join()));

        FriendLink another = new FriendLink(link.identity, crypto.random.randomBytes(FriendLinkBundle.LINK_ID_BYTES), Optional.empty());
        Assert.assertTrue("a bundle for one link can't answer for another",
                failed(() -> UserContext.verifyFriendLinkBundle(another, real, bob.network).join()));
    }

    @Test
    public void aRequestToALinkIsTakenOnlyWhileTheLinkIsLive() {
        UserContext alice = signUp("flq");
        UserContext bob = signUp("flp");
        FriendLink link = alice.createFriendLink("", Optional.empty(), 0).join();
        FriendLinkBundle bundle = bob.fetchFriendLink(link.toLink()).join();
        PublicKeyHash target = alice.signer.publicKeyHash;

        FollowRequest req = new FollowRequest(Optional.empty(), Optional.empty());
        byte[] linked = BlindFollowRequest.build(bundle.boxer, req, Optional.of(link.linkId), crypto).join().serialize();
        Assert.assertTrue("padded to the size for any signing scheme", linked.length > BlindFollowRequest.LINKED_PADDING);
        Assert.assertTrue(bob.network.social.sendFollowRequest(target, linked).join());

        byte[] unknown = BlindFollowRequest.build(bundle.boxer, req,
                Optional.of(crypto.random.randomBytes(FriendLinkBundle.LINK_ID_BYTES)), crypto).join().serialize();
        Assert.assertTrue(failed(() -> bob.network.social.sendFollowRequest(target, unknown).join()));

        alice.revokeFriendLink(alice.getFriendLinks().join().get(0)).join();
        byte[] again = BlindFollowRequest.build(bundle.boxer, req, Optional.of(link.linkId), crypto).join().serialize();
        Assert.assertTrue(failed(() -> bob.network.social.sendFollowRequest(target, again).join()));

        byte[] legacy = BlindFollowRequest.build(bundle.boxer, req, crypto).join().serialize();
        Assert.assertTrue("requests without a link still reach followRequestReceiver until the PKI is retired",
                bob.network.social.sendFollowRequest(target, legacy).join());
    }

    private static boolean failed(Runnable r) {
        try {
            r.run();
            return false;
        } catch (Exception e) {
            return true;
        }
    }

    private static String error(Runnable r) {
        try {
            r.run();
            return "";
        } catch (Exception e) {
            Throwable cause = e;
            while (cause.getCause() != null)
                cause = cause.getCause();
            return cause.getMessage();
        }
    }
}
