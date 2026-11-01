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
import peergos.shared.user.fs.*;
import peergos.shared.util.*;

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

    @Test
    public void friendsThroughALinkAndTheBackChannelsItLeaves() {
        UserContext alice = signUp("fma");
        UserContext bob = signUp("fmb");
        FriendLink link = alice.createFriendLink("", Optional.empty(), 0).join();
        Assert.assertTrue(bob.sendFollowRequestToLink(link.toLink()).join());

        List<BlindFollowRequest> waiting = alice.getFollowRequests().join();
        Assert.assertEquals(1, waiting.size());
        Assert.assertArrayEquals("it went to the link, not the legacy receiver", link.linkId, waiting.get(0).linkId.get());
        List<FollowRequestWithCipherText> requests = alice.processFollowRequests().join();
        Assert.assertEquals(1, requests.size());
        FollowRequest request = requests.get(0).req;
        Assert.assertEquals(bob.signer.publicKeyHash, request.linked.get().from);
        Assert.assertEquals(bob.username, request.entry.get().ownerName);
        Assert.assertTrue(alice.sendReplyFollowRequest(requests.get(0), true, true).join());

        bob.processFollowRequests().join();
        Assert.assertTrue(alice.getFollowing().join().contains(bob.username));
        Assert.assertTrue(alice.getFollowerNames().join().contains(bob.username));
        Assert.assertTrue(bob.getFollowing().join().contains(alice.username));
        Assert.assertTrue(bob.getFollowerNames().join().contains(alice.username));

        FriendLinkStore bobs = bob.getFriendLinkStore().join();
        FriendLinkStore alices = alice.getFriendLinkStore().join();
        FriendLinkStore.BackChannel toAlice = bobs.backChannel(alice.signer.publicKeyHash).get();
        FriendLinkStore.BackChannel toBob = alices.backChannel(bob.signer.publicKeyHash).get();
        Assert.assertArrayEquals(alices.replyLinkFor(bob.signer.publicKeyHash).get().linkId, toAlice.linkId);
        Assert.assertArrayEquals(bobs.replyLinkFor(alice.signer.publicKeyHash).get().linkId, toBob.linkId);
        Assert.assertEquals("the link counted one use", 1, alice.getFriendLinks().join().get(0).uses);
    }

    @Test
    public void aSingleUseLinkTakesOneRequestAndThenStops() {
        UserContext alice = signUp("fsa");
        UserContext bob = signUp("fsb");
        UserContext carol = signUp("fsc");
        FriendLink link = alice.createFriendLink("once", Optional.empty(), 1).join();
        Assert.assertTrue(bob.sendFollowRequestToLink(link.toLink()).join());
        Assert.assertTrue("the server can't tell a second use from the first",
                carol.sendFollowRequestToLink(link.toLink()).join());

        List<FollowRequestWithCipherText> requests = alice.processFollowRequests().join();
        Assert.assertEquals(1, requests.size());
        Assert.assertEquals(1, alice.getFollowRequests().join().size());
        Assert.assertEquals("the request still waiting is counted once", 1, alice.processFollowRequests().join().size());
        Assert.assertTrue(alice.getFriendLinks().join().get(0).isUsedUp());
        Assert.assertTrue("a used up link is taken off the server",
                failed(() -> carol.fetchFriendLink(link.toLink()).join()));
    }

    @Test
    public void aForgedOrReplayedRequestIsDropped() {
        UserContext alice = signUp("ffa");
        UserContext bob = signUp("ffb");
        UserContext carol = signUp("ffc");
        FriendLink link = alice.createFriendLink("", Optional.empty(), 0).join();
        FriendLink other = alice.createFriendLink("", Optional.empty(), 0).join();
        FriendLinkBundle bundle = carol.fetchFriendLink(link.toLink()).join();
        BoxingKeyPair reply = BoxingKeyPair.randomCurve25519(crypto.random, crypto.boxer);
        byte[] replyLink = crypto.random.randomBytes(FriendLinkBundle.LINK_ID_BYTES);
        FollowRequest unsigned = new FollowRequest(Optional.of(new EntryPoint(AbsoluteCapability.createNull(), bob.username)), Optional.empty());

        FollowRequest.Linked claimingBob = new FollowRequest.Linked(alice.signer.publicKeyHash, Optional.of(link.linkId),
                bob.signer.publicKeyHash, reply.publicBoxingKey, replyLink, bob.username);
        FollowRequest forged = unsigned.sign(claimingBob, carol.signer.secret).join();
        send(carol, alice, bundle.boxer, forged, link.linkId);

        FollowRequest.Linked forOtherLink = new FollowRequest.Linked(alice.signer.publicKeyHash, Optional.of(other.linkId),
                carol.signer.publicKeyHash, reply.publicBoxingKey, replyLink, carol.username);
        FollowRequest carols = new FollowRequest(Optional.of(new EntryPoint(AbsoluteCapability.createNull(), carol.username)), Optional.empty())
                .sign(forOtherLink, carol.signer.secret).join();
        send(carol, alice, bundle.boxer, carols, link.linkId);

        Assert.assertEquals(2, alice.getFollowRequests().join().size());
        Assert.assertTrue(alice.processFollowRequests().join().isEmpty());
        Assert.assertTrue("dropped requests are removed from the server", alice.getFollowRequests().join().isEmpty());
    }

    @Test
    public void askingAgainLaterGoesOverTheBackChannel() {
        UserContext alice = signUp("fga");
        UserContext bob = signUp("fgb");
        linkFriends(alice, bob);
        alice.block(bob.username).join();
        alice.unblock(bob.username).join();
        alice.followAgain(bob.username).join();

        Assert.assertTrue(alice.sendInitialFollowRequest(bob.username).join());
        List<BlindFollowRequest> waiting = bob.getFollowRequests().join();
        Assert.assertEquals(1, waiting.size());
        Assert.assertArrayEquals(bob.getFriendLinkStore().join().replyLinkFor(alice.signer.publicKeyHash).get().linkId,
                waiting.get(0).linkId.get());
        // Bob still counts Alice as a friend, so his reply reaffirms it rather than asking him
        for (FollowRequestWithCipherText request : bob.processFollowRequests().join())
            Assert.assertTrue(bob.sendReplyFollowRequest(request, true, true).join());
        List<BlindFollowRequest> replies = alice.getFollowRequests().join();
        Assert.assertEquals(1, replies.size());
        Assert.assertArrayEquals("the reply came back on Alice's reply link for Bob",
                alice.getFriendLinkStore().join().replyLinkFor(bob.signer.publicKeyHash).get().linkId,
                replies.get(0).linkId.get());
        alice.processFollowRequests().join();
        Assert.assertTrue(alice.getFollowRequests().join().isEmpty());
        Assert.assertTrue(alice.getSocialState().join().getFriends().contains(bob.username));
        Assert.assertTrue(bob.getSocialState().join().getFriends().contains(alice.username));
    }

    @Test
    public void legacyFriendsSwapBackChannelsOnceOffered() {
        UserContext alice = signUp("foa");
        UserContext bob = signUp("fob");
        UserContext carol = signUp("foc");
        PeergosNetworkUtils.friendBetweenGroups(Arrays.asList(alice), Arrays.asList(bob));
        Assert.assertTrue(alice.getFriendLinkStore().join().backChannels.isEmpty());

        Assert.assertEquals(Integer.valueOf(1), alice.offerBackChannels().join());
        Assert.assertEquals("an offer is made once", Integer.valueOf(0), alice.offerBackChannels().join());
        Assert.assertTrue("an offer asks nothing", bob.processFollowRequests().join().isEmpty());
        Assert.assertTrue(bob.getFollowRequests().join().isEmpty());
        FriendLinkStore.BackChannel toAlice = bob.getFriendLinkStore().join().backChannel(alice.signer.publicKeyHash).get();
        Assert.assertArrayEquals(alice.getFriendLinkStore().join().replyLinkFor(bob.signer.publicKeyHash).get().linkId, toAlice.linkId);

        Assert.assertEquals("Bob's offer goes back over Alice's back-channel", Integer.valueOf(1), bob.offerBackChannels().join());
        List<BlindFollowRequest> waiting = alice.getFollowRequests().join();
        Assert.assertArrayEquals(toAlice.linkId, waiting.get(0).linkId.get());
        Assert.assertTrue(alice.processFollowRequests().join().isEmpty());
        Assert.assertTrue(alice.getFriendLinkStore().join().backChannel(bob.signer.publicKeyHash).isPresent());

        Assert.assertEquals("a stranger isn't a contact, so gets no offer", Integer.valueOf(0), carol.offerBackChannels().join());
        FollowRequest offer = FollowRequest.backChannelOffer(carol.username);
        FollowRequest.Linked header = new FollowRequest.Linked(alice.signer.publicKeyHash, Optional.empty(),
                carol.signer.publicKeyHash, BoxingKeyPair.randomCurve25519(crypto.random, crypto.boxer).publicBoxingKey,
                crypto.random.randomBytes(FriendLinkBundle.LINK_ID_BYTES), carol.username);
        PublicBoxingKey legacy = carol.getPublicKeys(alice.username).join().get().right;
        byte[] blind = BlindFollowRequest.build(legacy, offer.sign(header, carol.signer.secret).join(), crypto).join().serialize();
        Assert.assertTrue(carol.network.social.sendFollowRequest(alice.signer.publicKeyHash, blind).join());
        Assert.assertTrue(alice.processFollowRequests().join().isEmpty());
        Assert.assertTrue("an offer from a stranger is dropped and not kept",
                alice.getFriendLinkStore().join().backChannel(carol.signer.publicKeyHash).isEmpty());
        Assert.assertTrue(alice.getFollowRequests().join().isEmpty());
    }

    @Test
    public void anIntroductionTravelsWithAReshareAndLabelsTheRequest() {
        UserContext alice = signUp("fia");
        UserContext bob = signUp("fib");
        UserContext carol = signUp("fic");
        linkFriends(carol, bob);
        Assert.assertTrue("Bob gave Carol his introduction",
                carol.getFriendLinkStore().join().introductions.containsKey(bob.signer.publicKeyHash));
        PeergosNetworkUtils.friendBetweenGroups(Arrays.asList(alice), Arrays.asList(carol));

        String filename = "from-bob.txt";
        byte[] data = "hello".getBytes();
        bob.getUserRoot().join().uploadOrReplaceFile(filename, new AsyncReader.ArrayBacked(data), data.length,
                bob.network, crypto, () -> false, x -> {}).join();
        bob.shareReadAccessWith(PathUtil.get(bob.username, filename), Collections.singleton(carol.username)).join();
        UserContext freshCarol = login(carol);
        Assert.assertTrue(freshCarol.getByPath(bob.username + "/" + filename).join().isPresent());
        freshCarol.shareReadAccessWith(PathUtil.get(bob.username, filename), Collections.singleton(alice.username)).join();

        UserContext freshAlice = login(alice);
        SocialFeed feed = freshAlice.getSocialFeed().join().update().join();
        SharedItem item = feed.getShared(0, feed.getFeedSize() + 1, crypto, freshAlice.network).join().stream()
                .filter(i -> i.owner.equals(bob.username))
                .findFirst().get();
        String intro = freshAlice.getIntroduction(item).join();
        Assert.assertNotNull("Carol passed on Bob's introduction", intro);
        Assert.assertEquals(bob.signer.publicKeyHash, FriendLink.fromLink(intro).identity);

        Assert.assertTrue(freshAlice.sendFollowRequestToLink(intro).join());
        List<FollowRequestWithCipherText> requests = bob.processFollowRequests().join();
        Assert.assertEquals(1, requests.size());
        Assert.assertEquals(alice.username, requests.get(0).req.entry.get().ownerName);
        Assert.assertEquals("Bob sees who introduced Alice, from his own link store", carol.username, requests.get(0).getIntroducer());

        Assert.assertTrue(bob.getIntroductionsEnabled().join());
        Assert.assertTrue(bob.setIntroductionsEnabled(false).join());
        Assert.assertFalse(bob.getIntroductionsEnabled().join());
        Assert.assertTrue("turning introductions off revokes them", failed(() -> freshAlice.fetchFriendLink(intro).join()));
        Assert.assertTrue(bob.getFriendLinkStore().join().ofKind(FriendLinkStore.Kind.INTRODUCTION).isEmpty());
    }

    private static UserContext login(UserContext c) {
        return UserContext.signIn(c.username, "password", UserTests::noMfa, Builder.buildLocalJavaNetworkAccess(port).join(), crypto).join();
    }

    /** Bob asks through a link of Alice's, and she accepts and follows back. */
    private static void linkFriends(UserContext alice, UserContext bob) {
        FriendLink link = alice.createFriendLink("", Optional.empty(), 0).join();
        Assert.assertTrue(bob.sendFollowRequestToLink(link.toLink()).join());
        List<FollowRequestWithCipherText> requests = alice.processFollowRequests().join();
        Assert.assertEquals(1, requests.size());
        Assert.assertTrue(alice.sendReplyFollowRequest(requests.get(0), true, true).join());
        bob.processFollowRequests().join();
    }

    private static void send(UserContext from, UserContext to, PublicBoxingKey boxer, FollowRequest req, byte[] linkId) {
        byte[] blind = BlindFollowRequest.build(boxer, req, Optional.of(linkId), crypto).join().serialize();
        Assert.assertTrue(from.network.social.sendFollowRequest(to.signer.publicKeyHash, blind).join());
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
