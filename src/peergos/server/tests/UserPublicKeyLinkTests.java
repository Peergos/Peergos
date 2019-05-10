package peergos.server.tests;

import org.junit.*;
import peergos.server.storage.*;
import peergos.shared.cbor.*;
import peergos.shared.corenode.UserPublicKeyLink;
import peergos.shared.crypto.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.asymmetric.curve25519.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.crypto.random.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.storage.*;

import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;


public class UserPublicKeyLinkTests {
    private final ContentAddressedStorage ipfs = new FileContentAddressedStorage(Paths.get("blockstore"));
    private final List<Multihash> id;

    public UserPublicKeyLinkTests() throws Exception {
        id = Arrays.asList(ipfs.id().get());
    }

    @BeforeClass
    public static void init() {
        PublicSigningKey.addProvider(PublicSigningKey.Type.Ed25519, new Ed25519.Java());
    }

    private PublicKeyHash putPublicSigningKey(SigningKeyPair user) throws Exception {
        PublicKeyHash owner = ContentAddressedStorage.hashKey(user.publicSigningKey);
        return ipfs.putSigningKey(
                user.secretSigningKey.signatureOnly(user.publicSigningKey.serialize()),
                owner,
                user.publicSigningKey, ipfs.startTransaction(owner).get()).get();
    }

    @Test
    public void createInitial() throws Exception {
        SigningKeyPair user = SigningKeyPair.random(new SafeRandom.Java(), new Ed25519.Java());
        UserPublicKeyLink.Claim node = UserPublicKeyLink.Claim.build("someuser", user.secretSigningKey, LocalDate.now().plusYears(2), id);

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

        List<UserPublicKeyLink> links = UserPublicKeyLink.createChain(oldSigner, newSigner, "someuser", LocalDate.now().plusYears(2), id);
        links.forEach(link -> testSerialization(link));
    }

    @Test
    public void repeatedPassword() throws Exception {
        SigningKeyPair oldUser = SigningKeyPair.random(new SafeRandom.Java(), new Ed25519.Java());
        SigningKeyPair newUser = SigningKeyPair.random(new SafeRandom.Java(), new Ed25519.Java());
        PublicKeyHash oldHash = putPublicSigningKey(oldUser);
        PublicKeyHash newHash = putPublicSigningKey(newUser);

        SigningPrivateKeyAndPublicHash oldSigner = new SigningPrivateKeyAndPublicHash(oldHash, oldUser.secretSigningKey);
        SigningPrivateKeyAndPublicHash newSigner = new SigningPrivateKeyAndPublicHash(newHash, newUser.secretSigningKey);

        String username = "someuser";
        LocalDate expiry = LocalDate.now().plusYears(2);
        List<UserPublicKeyLink> initial = UserPublicKeyLink.createInitial(oldSigner, username, expiry, id);
        List<UserPublicKeyLink> newPassword = UserPublicKeyLink.createChain(oldSigner, newSigner, username, expiry, id);
        List<UserPublicKeyLink> changed = UserPublicKeyLink.merge(initial, newPassword, ipfs).join();
        List<UserPublicKeyLink> backToOldPassword = UserPublicKeyLink.createChain(newSigner, oldSigner, username, expiry, id);
        List<UserPublicKeyLink> finalChain = Arrays.asList(changed.get(0), backToOldPassword.get(0), backToOldPassword.get(1));
        try {
            UserPublicKeyLink.merge(changed, finalChain, ipfs).join();
        } catch (Exception e) {
            return;
        }
        throw new IllegalStateException("Should have failed!");
    }
}
