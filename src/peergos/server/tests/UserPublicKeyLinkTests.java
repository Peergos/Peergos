package peergos.server.tests;

import org.junit.*;
import peergos.server.corenode.SQLiteCoreNode;
import peergos.shared.corenode.CoreNode;
import peergos.shared.corenode.UserPublicKeyLink;
import peergos.shared.crypto.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.asymmetric.curve25519.*;
import peergos.shared.crypto.random.*;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;


public class UserPublicKeyLinkTests {

    @BeforeClass
    public static void init() throws Exception {
        PublicSigningKey.addProvider(PublicSigningKey.Type.Ed25519, new Ed25519.Java());
        // use insecure random otherwise tests take ages
        UserTests.setFinalStatic(TweetNaCl.class.getDeclaredField("prng"), new Random(1));
    }

    @Test
    public void createInitial() {
        SigningKeyPair user = SigningKeyPair.random(new SafeRandom.Java(), new Ed25519.Java());
        UserPublicKeyLink.UsernameClaim node = UserPublicKeyLink.UsernameClaim.create("someuser", user, LocalDate.now().plusYears(2));
        UserPublicKeyLink upl = new UserPublicKeyLink(user.toUserPublicKey(), node);
        testSerialization(upl);
    }

    public void testSerialization(UserPublicKeyLink link) {
        byte[] serialized1 = link.toByteArray();
        UserPublicKeyLink upl2 = UserPublicKeyLink.fromByteArray(link.owner, serialized1);
        byte[] serialized2 = upl2.toByteArray();
        if (!Arrays.equals(serialized1, serialized2))
            throw new IllegalStateException("toByteArray not inverse of fromByteArray!");
    }

    @Test
    public void createChain() {
        SigningKeyPair oldUser = SigningKeyPair.random(new SafeRandom.Java(), new Ed25519.Java());
        SigningKeyPair newUser = SigningKeyPair.random(new SafeRandom.Java(), new Ed25519.Java());

        List<UserPublicKeyLink> links = UserPublicKeyLink.createChain(oldUser, newUser, "someuser", LocalDate.now().plusYears(2));
        links.forEach(link -> testSerialization(link));
    }

    @Test
    public void coreNode() throws Exception {
        CoreNode core = getDefaultCoreNode();
        SigningKeyPair user = SigningKeyPair.insecureRandom();
        String username = "someuser";

        // register the username
        UserPublicKeyLink.UsernameClaim node = UserPublicKeyLink.UsernameClaim.create(username, user, LocalDate.now().plusYears(2));
        UserPublicKeyLink upl = new UserPublicKeyLink(user.toUserPublicKey(), node);
        boolean success = core.updateChain(username, Arrays.asList(upl)).get();
        List<UserPublicKeyLink> chain = core.getChain(username).get();
        if (chain.size() != 1 || !chain.get(0).equals(upl))
            throw new IllegalStateException("Retrieved chain element different "+chain +" != "+Arrays.asList(upl));

        // now change the expiry
        UserPublicKeyLink.UsernameClaim node2 = UserPublicKeyLink.UsernameClaim.create(username, user, LocalDate.now().plusYears(3));
        UserPublicKeyLink upl2 = new UserPublicKeyLink(user.toUserPublicKey(), node2);
        boolean success2 = core.updateChain(username, Arrays.asList(upl2)).get();
        List<UserPublicKeyLink> chain2 = core.getChain(username).get();
        if (chain2.size() != 1 || !chain2.get(0).equals(upl2))
            throw new IllegalStateException("Retrieved chain element different "+chain2 +" != "+Arrays.asList(upl2));

        // now change the keys
        SigningKeyPair user2 = SigningKeyPair.insecureRandom();
        List<UserPublicKeyLink> chain3 = UserPublicKeyLink.createChain(user, user2, username, LocalDate.now().plusWeeks(1));
        boolean success3 = core.updateChain(username, chain3).get();
        List<UserPublicKeyLink> chain3Retrieved = core.getChain(username).get();
        if (!chain3.equals(chain3Retrieved))
            throw new IllegalStateException("Retrieved chain element different");

        // update the expiry at the end of the chain
        UserPublicKeyLink.UsernameClaim node4 = UserPublicKeyLink.UsernameClaim.create(username, user2, LocalDate.now().plusWeeks(2));
        UserPublicKeyLink upl4 = new UserPublicKeyLink(user2.toUserPublicKey(), node4);
        List<UserPublicKeyLink> chain4 = Arrays.asList(upl4);
        boolean success4 = core.updateChain(username, chain4).get();
        List<UserPublicKeyLink> chain4Retrieved = core.getChain(username).get();
        if (!chain4.equals(Arrays.asList(chain4Retrieved.get(chain4Retrieved.size()-1))))
            throw new IllegalStateException("Retrieved chain element different after expiry update");

        // check username lookup
        String uname = core.getUsername(user2.toUserPublicKey()).get();
        if (!uname.equals(username))
            throw new IllegalStateException("Returned username is different! "+uname + " != "+username);

        // try to claim the same username with a different key
        SigningKeyPair user3 = SigningKeyPair.insecureRandom();
        UserPublicKeyLink.UsernameClaim node3 = UserPublicKeyLink.UsernameClaim.create(username, user3, LocalDate.now().plusYears(2));
        UserPublicKeyLink upl3 = new UserPublicKeyLink(user3.toUserPublicKey(), node3);
        try {
            boolean shouldFail = core.updateChain(username, Arrays.asList(upl3)).get();
            throw new RuntimeException("Should have failed before here!");
        } catch (ExecutionException e) {}
    }

    static CoreNode getDefaultCoreNode() {
        try {
            return SQLiteCoreNode.build(":memory:");
        } catch (SQLException s) {
            throw new IllegalStateException(s);
        }
    }
}
