package peergos.server.tests;

import org.junit.*;
import peergos.server.*;
import peergos.server.corenode.*;
import peergos.server.crypto.asymmetric.curve25519.*;
import peergos.server.crypto.random.*;
import peergos.server.util.*;
import peergos.shared.*;
import peergos.shared.corenode.*;
import peergos.shared.crypto.*;
import peergos.shared.io.ipfs.*;
import peergos.shared.storage.*;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.util.*;

/** A proof of work replayed with a request that changes nothing, or fails, mustn't count towards the pki's
 *  signup rate, or one proof could raise the difficulty for everyone. */
public class PkiRateLimitReplayTests {

    // high enough that the difficulty stays at zero, so every replay passes the proof of work check
    private static final Args args = UserTests.buildArgs()
            .with("useIPFS", "false")
            .with("max-daily-signups", "100000000");
    private static UserService service;

    @BeforeClass
    public static void init() {
        service = Main.PKI_INIT.main(args).localApi;
    }

    private static IpfsCoreNode findPki(Object node, Set<Object> seen) throws IllegalAccessException {
        if (node instanceof IpfsCoreNode)
            return (IpfsCoreNode) node;
        if (node == null || ! seen.add(node))
            return null;
        for (Class<?> c = node.getClass(); c != null; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (! CoreNode.class.isAssignableFrom(f.getType()))
                    continue;
                f.setAccessible(true);
                IpfsCoreNode found = findPki(f.get(node), seen);
                if (found != null)
                    return found;
            }
        }
        return null;
    }

    /** The events the pki is rate limiting over: each bucket holds those of one range of ages, so this is their sum */
    private static long countedEvents(IpfsCoreNode pki) throws Exception {
        Field gen = IpfsCoreNode.class.getDeclaredField("difficultyGenerator");
        gen.setAccessible(true);
        DifficultyGenerator generator = (DifficultyGenerator) gen.get(pki);
        Field rate = DifficultyGenerator.class.getDeclaredField("queryRate");
        rate.setAccessible(true);
        return Arrays.stream(((RateMonitor) rate.get(generator)).getRates()).sum();
    }

    private static SigningPrivateKeyAndPublicHash newIdentity() {
        SigningKeyPair keys = SigningKeyPair.random(new SafeRandomJava(), new Ed25519Java());
        return new SigningPrivateKeyAndPublicHash(ContentAddressedStorage.hashKey(keys.publicSigningKey), keys.secretSigningKey);
    }

    // not this server, so the chains go straight to the pki rather than being signups here
    private static final Multihash otherServer = new Cid(1, Cid.Codec.LibP2pKey, Multihash.Type.sha2_256, new byte[32]);

    private static UserPublicKeyLink chain(SigningPrivateKeyAndPublicHash signer, String username) {
        return UserPublicKeyLink.createInitial(signer, username, LocalDate.now().plusMonths(2), List.of(otherServer))
                .join().get(0);
    }

    @Test
    public void repeatedReservationsCountOnce() throws Exception {
        IpfsCoreNode pki = findPki(service.coreNode, Collections.newSetFromMap(new IdentityHashMap<>()));
        Assert.assertNotNull(pki);
        CoreNode core = service.coreNode;
        long before = countedEvents(pki);

        UserPublicKeyLink reservation = chain(newIdentity(), "replayreserved");
        for (int i = 0; i < 20; i++)
            Assert.assertTrue(core.startPaidSignup("replayreserved", reservation, ProofOfWork.empty()).join().isA());
        Assert.assertEquals("only the first reservation changes anything", 1, countedEvents(pki) - before);
    }

    @Test
    public void failedChainUpdatesDontCount() throws Exception {
        IpfsCoreNode pki = findPki(service.coreNode, Collections.newSetFromMap(new IdentityHashMap<>()));
        CoreNode core = service.coreNode;
        long before = countedEvents(pki);

        // claimed by one key, but saying it's owned by another, so it is refused after the proof of work check
        SigningPrivateKeyAndPublicHash claimer = newIdentity();
        UserPublicKeyLink.Claim claim = UserPublicKeyLink.Claim.build("replayinvalid", claimer.secret,
                LocalDate.now().plusMonths(2), List.of(otherServer)).join();
        UserPublicKeyLink invalid = new UserPublicKeyLink(newIdentity().publicKeyHash, claim);
        for (int i = 0; i < 5; i++) {
            try {
                core.updateChain("replayinvalid", List.of(invalid), ProofOfWork.empty(), "").join();
                Assert.fail("accepted a chain signed by the wrong key");
            } catch (Exception expected) {}
        }
        Assert.assertEquals("refused updates don't count", 0, countedEvents(pki) - before);

        String username = "replayvalid";
        Assert.assertTrue(core.updateChain(username, List.of(chain(newIdentity(), username)), ProofOfWork.empty(), "").join().isEmpty());
        Assert.assertEquals("a new user counts", 1, countedEvents(pki) - before);
    }
}
