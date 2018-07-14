package peergos.server.tests;

import org.junit.*;
import peergos.server.corenode.*;
import peergos.server.storage.*;
import peergos.shared.cbor.*;
import peergos.shared.corenode.CoreNode;
import peergos.shared.corenode.UserPublicKeyLink;
import peergos.shared.crypto.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.asymmetric.curve25519.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.crypto.random.*;
import peergos.shared.storage.*;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;


public class UserPublicKeyLinkTests {
    private final ContentAddressedStorage ipfs = RAMStorage.getSingleton();

    @BeforeClass
    public static void init() throws Exception {
        PublicSigningKey.addProvider(PublicSigningKey.Type.Ed25519, new Ed25519.Java());
    }

    private PublicKeyHash putPublicSigningKey(SigningKeyPair user) throws Exception {
        return ipfs.putSigningKey(
                user.secretSigningKey.signatureOnly(user.publicSigningKey.serialize()),
                ContentAddressedStorage.hashKey(user.publicSigningKey),
                user.publicSigningKey).get();
    }

    @Test
    public void createInitial() throws Exception {
        SigningKeyPair user = SigningKeyPair.random(new SafeRandom.Java(), new Ed25519.Java());
        UserPublicKeyLink.UsernameClaim node = UserPublicKeyLink.UsernameClaim.create("someuser", user.secretSigningKey, LocalDate.now().plusYears(2));

        PublicKeyHash owner = putPublicSigningKey(user);
        UserPublicKeyLink upl = new UserPublicKeyLink(owner, node);
        testSerialization(upl);
    }

    public void testSerialization(UserPublicKeyLink link) {
        byte[] serialized1 = link.serialize();
        UserPublicKeyLink upl2 = UserPublicKeyLink.fromCbor(CborObject.fromByteArray(serialized1));
        byte[] serialized2 = upl2.serialize();
        if (!Arrays.equals(serialized1, serialized2))
            throw new IllegalStateException("toByteArray not inverse of fromByteArray!");
    }

    @Test
    public void createChain() throws Exception {
        SigningKeyPair oldUser = SigningKeyPair.random(new SafeRandom.Java(), new Ed25519.Java());
        SigningKeyPair newUser = SigningKeyPair.random(new SafeRandom.Java(), new Ed25519.Java());
        PublicKeyHash oldHash = putPublicSigningKey(oldUser);
        PublicKeyHash newHash = putPublicSigningKey(newUser);

        SigningPrivateKeyAndPublicHash oldSigner = new SigningPrivateKeyAndPublicHash(oldHash, oldUser.secretSigningKey);
        SigningPrivateKeyAndPublicHash newSigner = new SigningPrivateKeyAndPublicHash(newHash, newUser.secretSigningKey);

        List<UserPublicKeyLink> links = UserPublicKeyLink.createChain(oldSigner, newSigner, "someuser", LocalDate.now().plusYears(2));
        links.forEach(link -> testSerialization(link));
    }

    @Test
    public void coreNode() throws Exception {
        CoreNode core = getDefaultCoreNode();
        SigningKeyPair user = SigningKeyPair.insecureRandom();
        String username = "someuser";

        // register the username
        UserPublicKeyLink.UsernameClaim node = UserPublicKeyLink.UsernameClaim.create(username, user.secretSigningKey, LocalDate.now().plusMonths(2));
        PublicKeyHash userHash = putPublicSigningKey(user);
        UserPublicKeyLink upl = new UserPublicKeyLink(userHash, node);
        boolean success = core.updateChain(username, Arrays.asList(upl)).get();
        List<UserPublicKeyLink> chain = core.getChain(username).get();
        if (chain.size() != 1 || !chain.get(0).equals(upl))
            throw new IllegalStateException("Retrieved chain element different "+chain +" != "+Arrays.asList(upl));

        // now change the expiry
        UserPublicKeyLink.UsernameClaim node2 = UserPublicKeyLink.UsernameClaim.create(username, user.secretSigningKey, LocalDate.now().plusMonths(3));
        UserPublicKeyLink upl2 = new UserPublicKeyLink(userHash, node2);
        boolean success2 = core.updateChain(username, Arrays.asList(upl2)).get();
        List<UserPublicKeyLink> chain2 = core.getChain(username).get();
        if (chain2.size() != 1 || !chain2.get(0).equals(upl2))
            throw new IllegalStateException("Retrieved chain element different "+chain2 +" != "+Arrays.asList(upl2));

        // now change the keys
        SigningKeyPair user2 = SigningKeyPair.insecureRandom();
        PublicKeyHash user2Hash = putPublicSigningKey(user2);
        SigningPrivateKeyAndPublicHash oldUser = new SigningPrivateKeyAndPublicHash(userHash, user.secretSigningKey);
        SigningPrivateKeyAndPublicHash newUser = new SigningPrivateKeyAndPublicHash(user2Hash, user2.secretSigningKey);
        List<UserPublicKeyLink> chain3 = UserPublicKeyLink.createChain(oldUser, newUser, username, LocalDate.now().plusWeeks(1));
        boolean success3 = core.updateChain(username, chain3).get();
        List<UserPublicKeyLink> chain3Retrieved = core.getChain(username).get();
        if (!chain3.equals(chain3Retrieved))
            throw new IllegalStateException("Retrieved chain element different");

        // update the expiry at the end of the chain
        UserPublicKeyLink.UsernameClaim node4 = UserPublicKeyLink.UsernameClaim.create(username, user2.secretSigningKey, LocalDate.now().plusWeeks(2));
        UserPublicKeyLink upl4 = new UserPublicKeyLink(user2Hash, node4);
        List<UserPublicKeyLink> chain4 = Arrays.asList(upl4);
        boolean success4 = core.updateChain(username, chain4).get();
        List<UserPublicKeyLink> chain4Retrieved = core.getChain(username).get();
        if (!chain4.equals(Arrays.asList(chain4Retrieved.get(chain4Retrieved.size()-1))))
            throw new IllegalStateException("Retrieved chain element different after expiry update");

        // check username lookup
        String uname = core.getUsername(user2Hash).get();
        if (!uname.equals(username))
            throw new IllegalStateException("Returned username is different! "+uname + " != "+username);

        // try to claim the same username with a different key
        SigningKeyPair user3 = SigningKeyPair.insecureRandom();
        PublicKeyHash user3Hash = putPublicSigningKey(user3);
        UserPublicKeyLink.UsernameClaim node3 = UserPublicKeyLink.UsernameClaim.create(username, user3.secretSigningKey, LocalDate.now().plusMonths(2));
        UserPublicKeyLink upl3 = new UserPublicKeyLink(user3Hash, node3);
        try {
            boolean shouldFail = core.updateChain(username, Arrays.asList(upl3)).get();
            throw new RuntimeException("Should have failed before here!");
        } catch (ExecutionException e) {}
    }

    static CoreNode getDefaultCoreNode() {
        try {
            return UserRepository.buildSqlLite(":memory:", RAMStorage.getSingleton(), CoreNode.MAX_USERNAME_COUNT);
        } catch (SQLException s) {
            throw new IllegalStateException(s);
        }
    }
}
