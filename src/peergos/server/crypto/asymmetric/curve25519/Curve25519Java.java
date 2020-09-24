package peergos.server.crypto.asymmetric.curve25519;

import peergos.server.crypto.*;
import peergos.shared.crypto.asymmetric.curve25519.*;

public class Curve25519Java implements Curve25519 {

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
