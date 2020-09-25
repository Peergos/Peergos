package peergos.shared.crypto.symmetric;

import peergos.shared.crypto.*;
import peergos.shared.crypto.random.JSNaCl;

import java.util.concurrent.CompletableFuture;

public interface Salsa20Poly1305 {

    byte[] secretbox(byte[] data, byte[] nonce, byte[] key);

    byte[] secretbox_open(byte[] cipher, byte[] nonce, byte[] key);

    class Javascript implements Salsa20Poly1305 {
        JSNaCl scriptJS = new JSNaCl();

        @Override
        public byte[] secretbox(byte[] data, byte[] nonce, byte[] key) {
            return scriptJS.secretbox(data, nonce, key);
        }

        @Override
        public byte[] secretbox_open(byte[] cipher, byte[] nonce, byte[] key) {
            return scriptJS.secretbox_open(cipher, nonce, key);
        }
    }

}
