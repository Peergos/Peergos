package peergos.server.tests;

import org.junit.*;
import peergos.server.*;
import peergos.server.storage.*;
import peergos.server.util.*;
import peergos.shared.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.storage.*;
import peergos.shared.user.*;

import java.util.*;

import static peergos.server.tests.PeergosNetworkUtils.*;

public class FriendVerificationTests {

    private static Args args = UserTests.useMemoryDbs(UserTests.buildArgs())
            .with("enable-gc", "false");
    private static UserService service;
    private static final Crypto crypto = Main.initCrypto();
    private final Random random = new Random();
    private final NetworkAccess network;

    public FriendVerificationTests() {
        this.network = NetworkAccess.buildBuffered(new CachingStorage(service.storage, 1_000, 50 * 1024),
                service.bats, service.coreNode, service.account, service.mutable, 0, service.social,
                service.controller, service.usage, service.serverMessages, crypto.hasher,
                Arrays.asList("peergos"), false);
    }

    @BeforeClass
    public static void init() {
        service = Main.PKI_INIT.main(args).localApi;
    }

    @Test
    public void verificationOnlyHoldsForTheVerifiedKeys() {
        String password = "notagoodone";
        UserContext a = ensureSignedUp("a-" + generateUsername(random), password, network, crypto);
        UserContext b = ensureSignedUp("b-" + generateUsername(random), password, network, crypto);
        UserContext c = ensureSignedUp("c-" + generateUsername(random), password, network, crypto);
        List<PublicKeyHash> bKeys = a.generateFingerPrint(b.username).join().left;
        Assert.assertEquals("verified", a.verificationStatus(b.username, new FriendAnnotation(b.username, true, bKeys)).join());
        Assert.assertEquals("unverified", a.verificationStatus(b.username, new FriendAnnotation(b.username, false, bKeys)).join());
        Assert.assertEquals("unverified", a.verificationStatus(b.username, null).join());
        // verified with keys b doesn't have (as if they had been swapped since): no longer verified
        List<PublicKeyHash> otherKeys = a.generateFingerPrint(c.username).join().left;
        Assert.assertEquals("changed", a.verificationStatus(b.username, new FriendAnnotation(b.username, true, otherKeys)).join());
    }
}
