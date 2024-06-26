package peergos.shared.crypto.asymmetric.curve25519;

import peergos.shared.crypto.random.JSNaCl;

import java.util.concurrent.*;

public interface Ed25519 {

    CompletableFuture<byte[]> crypto_sign_open(byte[] signed, byte[] publicSigningKey);

    CompletableFuture<byte[]> crypto_sign(byte[] message, byte[] secretSigningKey);

    void crypto_sign_keypair(byte[] pk, byte[] sk);

    class Javascript implements Ed25519 {
        JSNaCl scriptJS = new JSNaCl();

        @Override
        public CompletableFuture<byte[]> crypto_sign_open(byte[] signed, byte[] publicSigningKey) {
            return scriptJS.crypto_sign_open(signed, publicSigningKey);
        }

        @Override
        public CompletableFuture<byte[]> crypto_sign(byte[] message, byte[] secretSigningKey) {
            return scriptJS.crypto_sign(message, secretSigningKey);
        }

        @Override
        public void crypto_sign_keypair(byte[] pk, byte[] sk) {
            byte[][] bytes = scriptJS.crypto_sign_keypair(pk, sk);
            for(int i=0; i < bytes[0].length; i++) {
                pk[i] = bytes[0][i];
            }
            for(int i=0; i < bytes[1].length; i++) {
                sk[i] = bytes[1][i];
            }
        }

    }

}
