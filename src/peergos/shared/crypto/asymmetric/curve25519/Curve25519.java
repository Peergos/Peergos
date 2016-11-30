package peergos.shared.crypto.asymmetric.curve25519;

import peergos.shared.crypto.TweetNaCl;
import peergos.shared.crypto.random.JSNaCl;

public interface Curve25519 {

    byte[] crypto_box_open(byte[] cipher, byte[] nonce, byte[] theirPublicBoxingKey, byte[] secretBoxingKey);

    byte[] crypto_box(byte[] message, byte[] nonce, byte[] theirPublicBoxingKey, byte[] ourSecretBoxingKey);

    void crypto_box_keypair(byte[] pk, byte[] sk);

    class Java implements Curve25519 {

        @Override
        public byte[] crypto_box_open(byte[] cipher, byte[] nonce, byte[] theirPublicBoxingKey, byte[] secretBoxingKey) {
            return TweetNaCl.crypto_box_open(cipher, nonce, theirPublicBoxingKey, secretBoxingKey);
        }

        @Override
        public byte[] crypto_box(byte[] message, byte[] nonce, byte[] theirPublicBoxingKey, byte[] ourSecretBoxingKey) {
            return TweetNaCl.crypto_box(message, nonce, theirPublicBoxingKey, ourSecretBoxingKey);
        }

        @Override
        public void crypto_box_keypair(byte[] pk, byte[] sk) {
            TweetNaCl.crypto_box_keypair(pk, sk, true);
        }
    }
    class Javascript implements Curve25519 {

        JSNaCl scriptJS = new JSNaCl();

        @Override
        public byte[] crypto_box_open(byte[] cipher, byte[] nonce, byte[] theirPublicBoxingKey, byte[] secretBoxingKey) {
            return TweetNaCl.crypto_box_open(cipher, nonce, theirPublicBoxingKey, secretBoxingKey);
            //return scriptJS.crypto_box_open(cipher, nonce, theirPublicBoxingKey, secretBoxingKey);
        }

        @Override
        public byte[] crypto_box(byte[] message, byte[] nonce, byte[] theirPublicBoxingKey, byte[] ourSecretBoxingKey) {
            return TweetNaCl.crypto_box(message, nonce, theirPublicBoxingKey, ourSecretBoxingKey);
            //return scriptJS.crypto_box(message, nonce, theirPublicBoxingKey, ourSecretBoxingKey);
        }

        @Override
        public void crypto_box_keypair(byte[] pk, byte[] sk) {
            TweetNaCl.crypto_box_keypair(pk, sk, true);
            //scriptJS.crypto_box_keypair(pk, sk, true);
        }
    }
}
