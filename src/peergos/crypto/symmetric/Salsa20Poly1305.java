package peergos.crypto.symmetric;

import peergos.crypto.*;

public interface Salsa20Poly1305 {

    byte[] secretbox(byte[] data, byte[] nonce, byte[] key);

    byte[] secretbox_open(byte[] cipher, byte[] nonce, byte[] key);

    class Java implements Salsa20Poly1305 {

        @Override
        public byte[] secretbox(byte[] data, byte[] nonce, byte[] key) {
            return TweetNaCl.secretbox(data, nonce, key);
        }

        @Override
        public byte[] secretbox_open(byte[] cipher, byte[] nonce, byte[] key) {
            return TweetNaCl.secretbox_open(cipher, nonce, key);
        }
    }

}
