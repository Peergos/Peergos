package peergos.server.crypto.symmetric;

import peergos.server.crypto.*;
import peergos.shared.crypto.symmetric.*;

public class Salsa20Poly1305Java implements Salsa20Poly1305 {

    @Override
    public byte[] secretbox(byte[] data, byte[] nonce, byte[] key) {
        return TweetNaCl.secretbox(data, nonce, key);
    }

    @Override
    public byte[] secretbox_open(byte[] cipher, byte[] nonce, byte[] key) {
        return TweetNaCl.secretbox_open(cipher, nonce, key);
    }
}
