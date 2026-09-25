package peergos.server.tests;

import org.junit.*;
import org.junit.runner.*;
import org.junit.runners.*;
import peergos.server.*;
import peergos.server.storage.admin.*;
import peergos.server.util.*;
import peergos.shared.*;
import peergos.shared.storage.controller.*;
import peergos.shared.user.*;
import peergos.shared.util.*;

import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

@RunWith(Parameterized.class)
public class TokenSignupTests {

    private static Args args = UserTests.buildArgs()
            .with("useIPFS", "false")
            .with("max-users", "1");
    protected static final Crypto crypto = Main.initCrypto();

    private final NetworkAccess network;
    private final UserService service;

    public TokenSignupTests(NetworkAccess network, UserService service) {
        this.network = network;
        this.service = service;
    }

    @Parameterized.Parameters()
    public static Collection<Object[]> parameters() throws Exception {
        UserService service = Main.PKI_INIT.main(args).localApi;
        WriteSynchronizer synchronizer = new WriteSynchronizer(service.mutable, service.storage, crypto.hasher);
        MutableTree mutableTree = new MutableTreeImpl(service.mutable, service.storage, crypto.hasher, synchronizer);
        // use actual http messager
        ServerMessager.HTTP serverMessager = new ServerMessager.HTTP(new JavaPoster(new URI("http://localhost:" + args.getArg("port")).toURL(), false));
        NetworkAccess network = new NetworkAccess(service.coreNode, service.account, service.social, service.storage,
                service.bats, Optional.empty(), service.mutable, mutableTree, synchronizer, service.controller, service.usage,
                serverMessager, service.crypto.hasher,
                Arrays.asList("peergos"), false);
        return Arrays.asList(new Object[][] {
                {network, service}
        });
    }

    @AfterClass
    public static void cleanup() {
        try {Thread.sleep(2000);}catch (InterruptedException e) {}
        Path peergosDir = args.fromPeergosDir("", "");
        System.out.println("Deleting " + peergosDir);
        UserTests.deleteFiles(peergosDir.toFile());
    }

    @Test
    public void signupWithToken() {
        String username = "q";
        String password = "test";
        String badtoken = "notvalid";
        // invalid token fails
        try {
            UserContext.signUp(username, password, badtoken, network, crypto).join();
            throw new RuntimeException("Shouldn't get here!");
        } catch (CompletionException e) {}

        String token = ((Admin)service.controller).generateSignupToken(crypto.random);
        UserContext.signUp(username, password, token, network, crypto).join();
    }

    /** The instance is full, so a signup needs a token: the admin can now make them without a
     *  shell on the server, and each one lets exactly one person in. */
    @Test
    public void adminCreatesSingleUseSignupTokens() {
        UserContext admin = PeergosNetworkUtils.ensureSignedUp("peergos", "testpassword", network, crypto);
        List<String> tokens = admin.createSignupTokens(2).join();
        Assert.assertEquals(2, tokens.size());
        Assert.assertNotEquals(tokens.get(0), tokens.get(1));
        for (String token : tokens)
            Assert.assertTrue("a 32 byte token in hex: " + token, token.matches("[0-9a-f]{64}"));

        String password = "test";
        refused(() -> UserContext.signUp("invitee1", password, "", network, crypto).join(), "not currently accepting new sign ups");

        UserContext.signUp("invitee1", password, tokens.get(0), network, crypto).join();
        refused(() -> UserContext.signUp("invitee2", password, tokens.get(0), network, crypto).join(), "Invalid signup token");
        UserContext.signUp("invitee2", password, tokens.get(1), network, crypto).join();
    }

    @Test
    public void onlyAnAdminCreatesSignupTokens() {
        String token = ((Admin)service.controller).generateSignupToken(crypto.random);
        UserContext user = UserContext.signUp("notadmin", "test", token, network, crypto).join();
        refused(() -> user.createSignupTokens(1).join(), "not an admin");
    }

    /** An admin with nothing pending still has to know it is one, to reach the invites at all. */
    @Test
    public void usersLearnOnlyWhetherTheyThemselvesAreAdmins() throws Exception {
        UserContext admin = PeergosNetworkUtils.ensureSignedUp("peergos", "testpassword", network, crypto);
        Assert.assertTrue(admin.getPendingSpaceRequests().join().isEmpty());
        Assert.assertTrue(admin.isAdmin().join());

        String token = ((Admin)service.controller).generateSignupToken(crypto.random);
        UserContext user = UserContext.signUp("notadmin2", "test", token, network, crypto).join();
        Assert.assertFalse(user.isAdmin().join());

        // asking about the admin takes the admin's key
        InstanceAdmin http = new HttpInstanceAdmin(new JavaPoster(new URI("http://localhost:" + args.getArg("port")).toURL(), false));
        byte[] signedByUser = new TimeLimitedClient.SignedRequest(Constants.ADMIN_URL + HttpInstanceAdmin.IS_ADMIN, System.currentTimeMillis())
                .sign(user.signer.secret).join();
        refused(() -> http.isAdmin(admin.signer.publicKeyHash, signedByUser).join(), "InvalidSignatureException");
    }

    /** Unused invites stay listed until someone signs up with them or the admin withdraws them. */
    @Test
    public void adminListsAndRevokesUnusedSignupTokens() {
        UserContext admin = PeergosNetworkUtils.ensureSignedUp("peergos", "testpassword", network, crypto);
        List<String> made = admin.createSignupTokens(3).join();
        Assert.assertTrue(admin.listSignupTokens().join().containsAll(made));

        Assert.assertTrue(admin.revokeSignupToken(made.get(0)).join());
        Assert.assertFalse(admin.listSignupTokens().join().contains(made.get(0)));
        refused(() -> UserContext.signUp("invitee3", "test", made.get(0), network, crypto).join(), "Invalid signup token");
        Assert.assertFalse("already gone", admin.revokeSignupToken(made.get(0)).join());

        UserContext.signUp("invitee3", "test", made.get(1), network, crypto).join();
        List<String> left = admin.listSignupTokens().join();
        Assert.assertFalse("a used token leaves the list", left.contains(made.get(1)));
        Assert.assertTrue(left.contains(made.get(2)));
    }

    @Test
    public void onlyAnAdminListsOrRevokesSignupTokens() {
        UserContext admin = PeergosNetworkUtils.ensureSignedUp("peergos", "testpassword", network, crypto);
        String token = admin.createSignupTokens(1).join().get(0);
        String other = ((Admin)service.controller).generateSignupToken(crypto.random);
        UserContext user = UserContext.signUp("notadmin3", "test", other, network, crypto).join();
        refused(() -> user.listSignupTokens().join(), "not an admin");
        refused(() -> user.revokeSignupToken(token).join(), "not an admin");
        Assert.assertTrue(admin.listSignupTokens().join().contains(token));
    }

    /** Over http: a list request is spent once, and a request to withdraw one token withdraws no other. */
    @Test
    public void listAndRevokeRequestsAreBound() throws Exception {
        UserContext admin = PeergosNetworkUtils.ensureSignedUp("peergos", "testpassword", network, crypto);
        InstanceAdmin http = new HttpInstanceAdmin(new JavaPoster(new URI("http://localhost:" + args.getArg("port")).toURL(), false));
        List<String> made = admin.createSignupTokens(2).join();

        Thread.sleep(5);
        byte[] list = new TimeLimitedClient.SignedRequest(Constants.ADMIN_URL + HttpInstanceAdmin.LIST_TOKENS, System.currentTimeMillis())
                .sign(admin.signer.secret).join();
        Assert.assertTrue(http.listSignupTokens(admin.signer.publicKeyHash, list).join().containsAll(made));
        refused(() -> http.listSignupTokens(admin.signer.publicKeyHash, list).join(), "Replay attack");

        Thread.sleep(5);
        byte[] revokeFirst = new TimeLimitedClient.SignedRequest(Constants.ADMIN_URL + HttpInstanceAdmin.REVOKE_TOKEN + "/" + made.get(0), System.currentTimeMillis())
                .sign(admin.signer.secret).join();
        refused(() -> http.revokeSignupToken(admin.signer.publicKeyHash, made.get(1), revokeFirst).join(), "Illegal path");
        Assert.assertTrue(http.revokeSignupToken(admin.signer.publicKeyHash, made.get(0), revokeFirst).join());
        List<String> left = admin.listSignupTokens().join();
        Assert.assertFalse(left.contains(made.get(0)));
        Assert.assertTrue(left.contains(made.get(1)));
    }

    /** The admin's key signs times for everyday calls - quota, usage, follow requests - which pass
     *  through whichever server they log in on. None of those may be spent on signup tokens. */
    @Test
    public void everydaySignedTimesDoNotCreateSignupTokens() throws Exception {
        UserContext admin = PeergosNetworkUtils.ensureSignedUp("peergos", "testpassword", network, crypto);
        InstanceAdmin http = new HttpInstanceAdmin(new JavaPoster(new URI("http://localhost:" + args.getArg("port")).toURL(), false));
        var instance = network.dhtClient.id().join();

        Thread.sleep(5);
        byte[] quotaAuth = TimeLimitedClient.signNow(admin.signer.secret).join();
        network.spaceUsage.getQuota(admin.signer.publicKeyHash, quotaAuth).join();
        refused(() -> http.createSignupTokens(admin.signer.publicKeyHash, instance, quotaAuth, 1).join(), "SignedRequest");

        // nor may a request signed for another path
        byte[] otherPath = new TimeLimitedClient.SignedRequest(Constants.ADMIN_URL + HttpInstanceAdmin.PENDING, System.currentTimeMillis())
                .sign(admin.signer.secret).join();
        refused(() -> http.createSignupTokens(admin.signer.publicKeyHash, instance, otherPath, 1).join(), "Illegal path");
    }

    private static byte[] tokensRequest(UserContext admin) {
        return new TimeLimitedClient.SignedRequest(Constants.ADMIN_URL + HttpInstanceAdmin.TOKENS, System.currentTimeMillis())
                .sign(admin.signer.secret).join();
    }

    /** Fails, and for the reason given: a refusal for some other reason would pass unnoticed otherwise. */
    private static void refused(Runnable call, String reason) {
        try {
            call.run();
        } catch (CompletionException e) {
            String message = String.valueOf(e.getCause() == null ? e.getMessage() : e.getCause().getMessage());
            Assert.assertTrue("Refused, but for another reason: " + message, message.contains(reason));
            return;
        }
        Assert.fail("Should have been refused: " + reason);
    }

    /** Over http, as the web ui calls it: a signed request is good for one call, and the count is bounded. */
    @Test
    public void signupTokensOverHttp() throws Exception {
        UserContext admin = PeergosNetworkUtils.ensureSignedUp("peergos", "testpassword", network, crypto);
        InstanceAdmin http = new HttpInstanceAdmin(new JavaPoster(new URI("http://localhost:" + args.getArg("port")).toURL(), false));
        var instance = network.dhtClient.id().join();

        byte[] signed = tokensRequest(admin);
        Assert.assertEquals(3, http.createSignupTokens(admin.signer.publicKeyHash, instance, signed, 3).join().size());
        refused(() -> http.createSignupTokens(admin.signer.publicKeyHash, instance, signed, 1).join(), "Replay attack");

        for (int count : new int[] {0, Admin.MAX_SIGNUP_TOKENS_PER_REQUEST + 1}) {
            Thread.sleep(5);
            byte[] fresh = tokensRequest(admin);
            refused(() -> http.createSignupTokens(admin.signer.publicKeyHash, instance, fresh, count).join(),
                    "created 1 to " + Admin.MAX_SIGNUP_TOKENS_PER_REQUEST);
        }
    }
}
