package peergos.server.crypto.asymmetric.mldsa;

import peergos.shared.crypto.asymmetric.mldsa.*;
import peergos.shared.util.*;

import java.security.*;
import java.security.spec.*;
import java.util.*;
import java.util.concurrent.*;

/** ML-DSA-44 from the JDK (24+). Keys cross the API as raw FIPS 204 bytes: the 32 byte seed and the 1312 byte
 *  public key, wrapped in their fixed PKCS#8 and X.509 prefixes.
 */
public class JdkMldsa implements Mldsa {
    private static final byte[] X509_PREFIX = ArrayOps.hexToBytes("30820532300b06096086480165030403110382052100");
    private static final byte[] PKCS8_SEED_PREFIX = ArrayOps.hexToBytes("3034020100300b0609608648016503040311042280" + "20");

    /** This, if the JDK has ML-DSA-44, else a provider that refuses. Android's providers have no ML-DSA. */
    public static Mldsa orElseUnavailable() {
        try {
            JdkMldsa jdk = new JdkMldsa();
            byte[] seed = new byte[SEED_BYTES];
            Arrays.fill(seed, (byte) 7);
            byte[] pk = jdk.publicKey(seed);
            byte[] message = "peergos".getBytes();
            if (jdk.verify(jdk.sign(message, seed).join(), message, pk).join())
                return jdk;
        } catch (Throwable t) {}
        return new Mldsa.Unavailable();
    }

    private static class SeedRandom extends SecureRandom {
        private final byte[] seed;
        private boolean used = false;

        SeedRandom(byte[] seed) {
            this.seed = seed;
        }

        @Override
        public void nextBytes(byte[] bytes) {
            if (used || bytes.length != seed.length)
                throw new IllegalStateException("Unexpected use of ML-DSA keygen randomness");
            used = true;
            System.arraycopy(seed, 0, bytes, 0, seed.length);
        }
    }

    @Override
    public byte[] publicKey(byte[] seed) {
        if (seed.length != SEED_BYTES)
            throw new IllegalStateException("Invalid ML-DSA-44 seed size: " + seed.length);
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("ML-DSA-44");
            g.initialize(new NamedParameterSpec("ML-DSA-44"), new SeedRandom(seed));
            KeyPair kp = g.generateKeyPair();
            byte[] encodedPrivate = kp.getPrivate().getEncoded();
            if (! Arrays.equals(encodedPrivate, ArrayOps.concat(PKCS8_SEED_PREFIX, seed)))
                throw new IllegalStateException("JDK ML-DSA keygen didn't use the given seed");
            byte[] encoded = kp.getPublic().getEncoded();
            return Arrays.copyOfRange(encoded, X509_PREFIX.length, encoded.length);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public CompletableFuture<byte[]> sign(byte[] message, byte[] seed) {
        try {
            PrivateKey sk = KeyFactory.getInstance("ML-DSA-44")
                    .generatePrivate(new PKCS8EncodedKeySpec(ArrayOps.concat(PKCS8_SEED_PREFIX, seed)));
            Signature signer = Signature.getInstance("ML-DSA-44");
            signer.initSign(sk);
            signer.update(message);
            return Futures.of(signer.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public CompletableFuture<Boolean> verify(byte[] signature, byte[] message, byte[] publicKey) {
        if (signature.length != SIGNATURE_BYTES || publicKey.length != PUBLIC_KEY_BYTES)
            return Futures.of(false);
        try {
            PublicKey pk = KeyFactory.getInstance("ML-DSA-44")
                    .generatePublic(new X509EncodedKeySpec(ArrayOps.concat(X509_PREFIX, publicKey)));
            Signature verifier = Signature.getInstance("ML-DSA-44");
            verifier.initVerify(pk);
            verifier.update(message);
            return Futures.of(verifier.verify(signature));
        } catch (GeneralSecurityException e) {
            return Futures.of(false);
        }
    }
}
