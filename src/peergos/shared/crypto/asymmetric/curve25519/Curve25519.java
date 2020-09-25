package peergos.shared.crypto.asymmetric.curve25519;

import peergos.shared.crypto.random.JSNaCl;

public interface Curve25519 {

    byte[] crypto_box_open(byte[] cipher, byte[] nonce, byte[] theirPublicBoxingKey, byte[] secretBoxingKey);

    byte[] crypto_box(byte[] message, byte[] nonce, byte[] theirPublicBoxingKey, byte[] ourSecretBoxingKey);

    void crypto_box_keypair(byte[] pk, byte[] sk);

    class Javascript implements Curve25519 {

        JSNaCl scriptJS = new JSNaCl();

        @Override
        public byte[] crypto_box_open(byte[] cipher, byte[] nonce, byte[] theirPublicBoxingKey, byte[] secretBoxingKey) {
            return scriptJS.crypto_box_open(cipher, nonce, theirPublicBoxingKey, secretBoxingKey);
        }

        @Override
        public byte[] crypto_box(byte[] message, byte[] nonce, byte[] theirPublicBoxingKey, byte[] ourSecretBoxingKey) {
            return scriptJS.crypto_box(message, nonce, theirPublicBoxingKey, ourSecretBoxingKey);
        }

        @Override
        public void crypto_box_keypair(byte[] pk, byte[] sk) {
            byte[] bytes = scriptJS.crypto_box_keypair(pk, sk);
            for(int i=0; i < bytes.length; i++) {
                pk[i] = bytes[i];
            }
        }
    }
}
