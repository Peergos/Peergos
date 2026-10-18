package peergos.shared.routing;

import peergos.shared.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.asymmetric.curve25519.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.crypto.symmetric.*;
import peergos.shared.user.*;

import java.util.*;
import java.util.concurrent.*;

/** What a recovery key, kept offline by the user, derives (remove-pki §3.2): the next identity key the identity
 *  commits to, never derived from the password, and a symmetric key that unwraps the recovery keypair the home
 *  server holds, which decrypts the recovery copies of the login data. It is salted with the identity's name, which
 *  is public, so the recovery key alone recovers the account.
 */
public class RecoveryKey {
    private static final ScryptGenerator KDF = new ScryptGenerator(ScryptGenerator.LOGIN_MEMORY_COST, 8, 1, 64, "");
    private static final String SALT_PREFIX = "peergos-recovery:";

    public final SigningKeyPair nextIdentity;
    public final SymmetricKey wrapKey;

    private RecoveryKey(SigningKeyPair nextIdentity, SymmetricKey wrapKey) {
        this.nextIdentity = nextIdentity;
        this.wrapKey = wrapKey;
    }

    public static CompletableFuture<RecoveryKey> derive(String recoveryKey, PublicKeyHash identity, Crypto crypto) {
        String normalised = recoveryKey.trim().toLowerCase();
        if (normalised.isEmpty())
            throw new IllegalStateException("Empty recovery key");
        return crypto.hasher.hashToKeyBytes(SALT_PREFIX + identity, normalised, KDF)
                .thenApply(bytes -> {
                    byte[] sk = Arrays.copyOf(bytes, 64);
                    Arrays.fill(sk, 32, 64, (byte) 0);
                    byte[] pk = new byte[32];
                    crypto.signer.crypto_sign_keypair(pk, sk);
                    SigningKeyPair next = new SigningKeyPair(new Ed25519PublicKey(pk, crypto.signer), new Ed25519SecretKey(sk, crypto.signer));
                    SymmetricKey wrap = new TweetNaClKey(Arrays.copyOfRange(bytes, 32, 64), false, crypto.symmetricProvider, crypto.random);
                    return new RecoveryKey(next, wrap);
                });
    }
}
