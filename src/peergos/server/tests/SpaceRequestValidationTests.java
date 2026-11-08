package peergos.server.tests;

import org.junit.*;
import peergos.server.*;
import peergos.server.space.*;
import peergos.server.sql.*;
import peergos.server.storage.*;
import peergos.server.util.*;
import peergos.shared.*;
import peergos.shared.storage.*;
import peergos.shared.user.*;

import java.util.*;

import static peergos.server.tests.PeergosNetworkUtils.ensureSignedUp;
import static peergos.server.tests.PeergosNetworkUtils.generateUsername;

/** A space request filed under someone else's name, or one that doesn't exist, used to break the admin's list of
 *  pending requests and couldn't be removed. */
public class SpaceRequestValidationTests {

    private static final Crypto crypto = Main.initCrypto();
    private static final Args args = UserTests.buildArgs().with("useIPFS", "false");
    private static UserService service;
    private static NetworkAccess network;
    private static final Random random = new Random(53);

    @BeforeClass
    public static void init() {
        service = Main.PKI_INIT.main(args).localApi;
        network = NetworkAccess.buildBuffered(service.storage, service.bats, service.coreNode, service.account,
                service.mutable, 0, service.social, service.controller, service.usage, service.serverMessages,
                crypto.hasher, Arrays.asList("peergos"), false);
    }

    private static byte[] sign(UserContext signer, String label, long utcMillis) {
        QuotaControl.SpaceRequest req = new QuotaControl.SpaceRequest(label, 1_000_000, false, utcMillis, Optional.empty());
        return signer.signer.secret.signMessage(req.serialize()).join();
    }

    @Test
    public void requestsAreCheckedOnArrivalAndWhenListed() {
        UserContext victim = ensureSignedUp(generateUsername(random), "password", network, crypto);
        UserContext attacker = ensureSignedUp(generateUsername(random), "password", network, crypto);
        JdbcSpaceRequests requests = JdbcSpaceRequests.build(Main.buildEphemeralSqlite(), new SqliteCommands());
        UserQuotas quotas = new UserQuotas(JdbcQuotas.build(Main.buildEphemeralSqlite(), new SqliteCommands()),
                1024 * 1024, 100, requests, service.storage, false);
        quotas.setPki(service.coreNode);
        quotas.setQuota(attacker.username, 1024 * 1024);
        long now = System.currentTimeMillis();

        for (String label : List.of(victim.username, "no-such-user")) {
            try {
                quotas.requestQuota(attacker.signer.publicKeyHash, sign(attacker, label, now), 0).join();
                Assert.fail("accepted a request labelled " + label);
            } catch (Exception expected) {
                Assert.assertTrue(expected.getMessage(), expected.getMessage().contains("signed by " + attacker.username));
            }
        }
        try {
            quotas.requestQuota(attacker.signer.publicKeyHash, sign(attacker, attacker.username, now + 3_600_000), 0).join();
            Assert.fail("accepted a request from the future");
        } catch (Exception expected) {
            Assert.assertTrue(expected.getMessage(), expected.getMessage().contains("in the future"));
        }

        quotas.requestQuota(attacker.signer.publicKeyHash, sign(attacker, attacker.username, now), 0).join();
        byte[] latest = sign(attacker, attacker.username, now + 1);
        quotas.requestQuota(attacker.signer.publicKeyHash, latest, 0).join();

        // as stored before requests were checked on arrival
        requests.addSpaceRequest(victim.username, sign(attacker, victim.username, now));
        requests.addSpaceRequest("no-such-user", sign(attacker, "no-such-user", now));
        Assert.assertEquals(3, requests.getSpaceRequests().size());

        List<QuotaControl.LabelledSignedSpaceRequest> listed = quotas.getSpaceRequests();
        Assert.assertEquals(1, listed.size());
        Assert.assertEquals(attacker.username, listed.get(0).username);
        Assert.assertArrayEquals(latest, listed.get(0).signedRequest);
        Assert.assertEquals("invalid requests are removed", 1, requests.getSpaceRequests().size());
    }

    @Test
    public void adminSeesValidRequestsDespiteInvalidOnes() {
        UserContext user = ensureSignedUp(generateUsername(random), "password", network, crypto);
        UserContext admin = ensureSignedUp("peergos", "testpassword", network.clear(), crypto);
        long now = System.currentTimeMillis();
        List<QuotaControl.LabelledSignedSpaceRequest> mixed = List.of(
                new QuotaControl.LabelledSignedSpaceRequest("no-such-user", sign(user, "no-such-user", now)),
                new QuotaControl.LabelledSignedSpaceRequest(admin.username, sign(user, admin.username, now)),
                new QuotaControl.LabelledSignedSpaceRequest(user.username, sign(user, user.username, now)));

        List<DecodedSpaceRequest> decoded = admin.decodeSpaceRequests(mixed).join();
        Assert.assertEquals(1, decoded.size());
        Assert.assertEquals(user.username, decoded.get(0).getUsername());
    }
}
