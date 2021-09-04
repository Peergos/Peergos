package peergos.server.tests;

import org.junit.*;
import peergos.server.*;
import peergos.shared.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.crypto.symmetric.*;
import peergos.shared.storage.*;
import peergos.shared.user.*;

public class IdentityProofTests {
    private static final Crypto crypto = Main.initCrypto();

    @Test
    public void identityProof() {
        String username = "long-username-with-maxximum-size";
        SigningKeyPair pair = SigningKeyPair.random(crypto.random, crypto.signer);
        PublicSigningKey peergosIdentityKey = pair.publicSigningKey;
        PublicKeyHash publicHash = ContentAddressedStorage.hashKey(pair.publicSigningKey);
        SigningPrivateKeyAndPublicHash signer = new SigningPrivateKeyAndPublicHash(publicHash, pair.secretSigningKey);

        AlternateIdentityProof proof = AlternateIdentityProof.buildAndSign(signer, username, "twitterusername", "Twitter");
        Assert.assertTrue(proof.isValid(peergosIdentityKey));

        String toPost = proof.alternatePostText("100");
        Assert.assertTrue(toPost.length() < 280);

        AlternateIdentityProof parsed = AlternateIdentityProof.parse(toPost);
        Assert.assertTrue(parsed.isValid(peergosIdentityKey));

        // Now do an encrypted version
        SymmetricKey key = SymmetricKey.random();
        AlternateIdentityProof withKey = proof.withKey(key);
        String encrypted = withKey.encryptedPostText();
        Assert.assertTrue(encrypted.length() < 280);

        AlternateIdentityClaim decrypted = AlternateIdentityClaim.decrypt(encrypted, key, peergosIdentityKey);
        Assert.assertTrue(decrypted.equals(proof.claim));
    }
}
