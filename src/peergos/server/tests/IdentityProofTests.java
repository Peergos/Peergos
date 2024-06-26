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
import peergos.shared.user.fs.*;

import java.util.regex.*;

public class IdentityProofTests {
    private static final Crypto crypto = Main.initCrypto();

    @Test
    public void identityProof() {
        String username = "long-username-with-maxximum-size";
        SigningKeyPair pair = SigningKeyPair.random(crypto.random, crypto.signer);
        PublicSigningKey peergosIdentityKey = pair.publicSigningKey;
        PublicKeyHash publicHash = ContentAddressedStorage.hashKey(pair.publicSigningKey);
        SigningPrivateKeyAndPublicHash signer = new SigningPrivateKeyAndPublicHash(publicHash, pair.secretSigningKey);

        IdentityLinkProof proof = IdentityLinkProof.buildAndSign(signer, username, "twitterusername", "Twitter").join();
        Assert.assertTrue(proof.isValid(peergosIdentityKey).join());

        String toPost = proof.postText("https://peergos.net/public/" + username + "/.profile/ids/" + proof.getFilename() + "?open=true");
        int twitterCharacterCount = toPost.substring(0, toPost.indexOf("https://")).length() + 23;
        Assert.assertTrue(twitterCharacterCount < 280);

        IdentityLinkProof parsed = IdentityLinkProof.parse(toPost);
        Assert.assertTrue(parsed.isValid(peergosIdentityKey).join());

        // Now do an encrypted version
        SymmetricKey key = SymmetricKey.random();
        IdentityLinkProof withKey = proof.withKey(key);
        String encrypted = withKey.encryptedPostText();
        Assert.assertTrue(encrypted.length() < 280);

        IdentityLink decrypted = IdentityLink.decrypt(encrypted, key, peergosIdentityKey).join();
        Assert.assertTrue(decrypted.equals(proof.claim));

        // test mimetype detection
        String mimeType = MimeTypes.calculateMimeType(withKey.serialize(), proof.getFilename());
        Assert.assertTrue(mimeType.equals(MimeTypes.PEERGOS_IDENTITY));
    }

    @Test
    public void usernameRegexes() {
        Assert.assertTrue(Pattern.compile(IdentityLink.KnownService.Website.usernameRegex).matcher("example.com").matches());
        Assert.assertTrue(Pattern.compile(IdentityLink.KnownService.Website.usernameRegex).matcher("cool.example.com").matches());
        Assert.assertFalse(Pattern.compile(IdentityLink.KnownService.Website.usernameRegex).matcher("example-.com").matches());

        Assert.assertTrue(Pattern.compile(IdentityLink.KnownService.Mastodon.usernameRegex).matcher("peergos@mastodon.social").matches());
        Assert.assertFalse(Pattern.compile(IdentityLink.KnownService.Mastodon.usernameRegex).matcher("first.last@mastodon.social").matches());
    }
}
