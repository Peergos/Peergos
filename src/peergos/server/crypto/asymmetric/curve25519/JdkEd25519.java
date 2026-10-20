package peergos.server.crypto.asymmetric.curve25519;

import peergos.server.crypto.*;
import peergos.shared.crypto.asymmetric.curve25519.*;
import peergos.shared.util.*;

import java.security.*;
import java.security.spec.*;
import java.util.*;
import java.util.concurrent.*;

/** Ed25519 from the JDK, about 4x faster to verify than JNI TweetNaCl. Keeps TweetNaCl's formats: a 64 byte secret
 *  key of seed then public key, and signatures prepended to the message.
 */
public class JdkEd25519 implements Ed25519 {
    private static final byte[] X509_PREFIX = ArrayOps.hexToBytes("302a300506032b6570032100");

    /** This, if the JDK has Ed25519 and it agrees with the fallback, else the fallback. Android only has it from
     *  API 33, and older ones lack the classes altogether.
     */
    public static Ed25519 orElse(Ed25519 fallback) {
        try {
            JdkEd25519 jdk = new JdkEd25519();
            byte[] pk = new byte[32], sk = new byte[64];
            Arrays.fill(sk, 0, 32, (byte) 7);
            fallback.crypto_sign_keypair(pk, sk);
            byte[] message = "peergos".getBytes();
            byte[] signed = jdk.crypto_sign(message, sk).join();
            if (Arrays.equals(signed, fallback.crypto_sign(message, sk).join())
                    && Arrays.equals(message, jdk.crypto_sign_open(signed, pk).join()))
                return jdk;
        } catch (Throwable t) {}
        return fallback;
    }

    @Override
    public CompletableFuture<byte[]> crypto_sign_open(byte[] signed, byte[] publicSigningKey) {
        if (signed.length < TweetNaCl.SIGNATURE_SIZE_BYTES || publicSigningKey.length != 32)
            throw new TweetNaCl.InvalidSignatureException();
        boolean valid;
        try {
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(KeyFactory.getInstance("Ed25519")
                    .generatePublic(new X509EncodedKeySpec(ArrayOps.concat(X509_PREFIX, publicSigningKey))));
            verifier.update(signed, TweetNaCl.SIGNATURE_SIZE_BYTES, signed.length - TweetNaCl.SIGNATURE_SIZE_BYTES);
            valid = verifier.verify(signed, 0, TweetNaCl.SIGNATURE_SIZE_BYTES);
        } catch (GeneralSecurityException e) {
            valid = false;
        }
        if (! valid)
            throw new TweetNaCl.InvalidSignatureException();
        return Futures.of(Arrays.copyOfRange(signed, TweetNaCl.SIGNATURE_SIZE_BYTES, signed.length));
    }

    @Override
    public CompletableFuture<byte[]> crypto_sign(byte[] message, byte[] secretSigningKey) {
        try {
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(KeyFactory.getInstance("Ed25519")
                    .generatePrivate(new EdECPrivateKeySpec(NamedParameterSpec.ED25519, Arrays.copyOfRange(secretSigningKey, 0, 32))));
            signer.update(message);
            return Futures.of(ArrayOps.concat(signer.sign(), message));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Seeded, like TweetNaCl's: the caller has put the seed in the first 32 bytes of sk. */
    @Override
    public void crypto_sign_keypair(byte[] pk, byte[] sk) {
        TweetNaCl.crypto_sign_keypair(pk, sk, true);
    }
}
