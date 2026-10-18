package peergos.shared.routing;

import peergos.shared.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.asymmetric.curve25519.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.user.*;

import java.util.*;
import java.util.concurrent.*;

/** The next identity key an identity commits to, derived from a recovery key the user keeps offline, never from the
 *  password (remove-pki §3.2). It is salted with the identity it succeeds, which is public when it is needed.
 */
public class RecoveryKey {
    private static final ScryptGenerator KDF = new ScryptGenerator(ScryptGenerator.LOGIN_MEMORY_COST, 8, 1, 32, "");
    private static final String SALT_PREFIX = "peergos-next-identity:";

    public static CompletableFuture<SigningKeyPair> nextIdentity(String recoveryKey, PublicKeyHash current, Crypto crypto) {
        String normalised = recoveryKey.trim().toLowerCase();
        if (normalised.isEmpty())
            throw new IllegalStateException("Empty recovery key");
        return crypto.hasher.hashToKeyBytes(SALT_PREFIX + current, normalised, KDF)
                .thenApply(seed -> {
                    byte[] sk = Arrays.copyOf(seed, 64);
                    byte[] pk = new byte[32];
                    crypto.signer.crypto_sign_keypair(pk, sk);
                    return new SigningKeyPair(new Ed25519PublicKey(pk, crypto.signer), new Ed25519SecretKey(sk, crypto.signer));
                });
    }
}
