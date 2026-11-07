package peergos.server.tests;

import org.junit.*;
import peergos.server.*;
import peergos.server.util.*;
import peergos.shared.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.social.*;
import peergos.shared.user.*;

import java.util.*;

import static peergos.server.tests.PeergosNetworkUtils.ensureSignedUp;
import static peergos.server.tests.PeergosNetworkUtils.generateUsername;

/** Anyone can encrypt a follow request to our public boxing key, so its contents mustn't be able to break our
 *  social state, and unusable ones are removed rather than kept forever. */
public class FollowRequestPayloadTests {

    private static final Crypto crypto = Main.initCrypto();
    private static final Args args = UserTests.buildArgs().with("useIPFS", "false");
    private static NetworkAccess network;
    private static final Random random = new Random(41);

    @BeforeClass
    public static void init() {
        UserService service = Main.PKI_INIT.main(args).localApi;
        network = NetworkAccess.buildBuffered(service.storage, service.bats, service.coreNode, service.account,
                service.mutable, 0, service.social, service.controller, service.usage, service.serverMessages,
                crypto.hasher, Arrays.asList("peergos"), false);
    }

    private static void send(UserContext from, UserContext target, PublicBoxingKey targetBoxer, FollowRequest req) {
        BlindFollowRequest blind = BlindFollowRequest.build(targetBoxer, req, crypto).join();
        Assert.assertTrue(from.network.social.sendFollowRequest(target.signer.publicKeyHash, blind.serialize()).join());
    }

    @Test
    public void unusableRequestsAreRemovedWithoutBreakingSocialState() {
        UserContext target = ensureSignedUp(generateUsername(random), "password", network, crypto);
        UserContext attacker = ensureSignedUp(generateUsername(random), "password", network, crypto);
        UserContext friend = ensureSignedUp(generateUsername(random), "password", network, crypto);
        PublicBoxingKey targetBoxer = attacker.getPublicKeys(target.username).join().get().right;
        EntryPoint someCap = new EntryPoint(attacker.getUserRoot().join().getPointer().capability.readOnly(), target.username);

        // no sender or capability
        send(attacker, target, targetBoxer, new FollowRequest(Optional.empty(), Optional.empty()));
        // claiming to be from the target
        send(attacker, target, targetBoxer, new FollowRequest(Optional.of(someCap), Optional.empty()));
        // encrypted to someone else's key, so we can't decrypt it
        PublicBoxingKey otherBoxer = BoxingKeyPair.randomCurve25519(crypto.random, crypto.boxer).publicBoxingKey;
        send(attacker, target, otherBoxer, new FollowRequest(Optional.empty(), Optional.empty()));

        friend.sendInitialFollowRequest(target.username).join();
        Assert.assertEquals(4, target.getFollowRequests().join().size());

        List<FollowRequestWithCipherText> incoming = target.getSocialState().join().pendingIncoming;
        Assert.assertEquals(1, incoming.size());
        Assert.assertEquals(friend.username, incoming.get(0).req.entry.get().ownerName);
        Assert.assertEquals(1, target.getFollowRequests().join().size());

        target.sendReplyFollowRequest(incoming.get(0), true, true).join();
        Assert.assertTrue(target.getSocialState().join().followerRoots.containsKey(friend.username));
    }
}
