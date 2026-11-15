package peergos.shared.crypto.asymmetric.mldsa;

import peergos.shared.crypto.random.JSNaCl;

import java.util.concurrent.CompletableFuture;

/** ML-DSA-44 (FIPS 204), pure mode with an empty context. Secret keys are the 32 byte seed. */
public interface Mldsa {
    int SEED_BYTES = 32;
    int PUBLIC_KEY_BYTES = 1312;
    int SIGNATURE_BYTES = 2420;

    byte[] publicKey(byte[] seed);

    CompletableFuture<byte[]> sign(byte[] message, byte[] seed);

    CompletableFuture<Boolean> verify(byte[] signature, byte[] message, byte[] publicKey);

    class Javascript implements Mldsa {
        JSNaCl js = new JSNaCl();

        @Override
        public byte[] publicKey(byte[] seed) {
            return js.mldsa44_public_key(seed);
        }

        @Override
        public CompletableFuture<byte[]> sign(byte[] message, byte[] seed) {
            return js.mldsa44_sign(message, seed);
        }

        @Override
        public CompletableFuture<Boolean> verify(byte[] signature, byte[] message, byte[] publicKey) {
            return js.mldsa44_verify(signature, message, publicKey);
        }
    }

    class Unavailable implements Mldsa {
        @Override
        public byte[] publicKey(byte[] seed) {
            throw new IllegalStateException("ML-DSA is not available on this platform");
        }

        @Override
        public CompletableFuture<byte[]> sign(byte[] message, byte[] seed) {
            throw new IllegalStateException("ML-DSA is not available on this platform");
        }

        @Override
        public CompletableFuture<Boolean> verify(byte[] signature, byte[] message, byte[] publicKey) {
            throw new IllegalStateException("ML-DSA is not available on this platform");
        }
    }
}
