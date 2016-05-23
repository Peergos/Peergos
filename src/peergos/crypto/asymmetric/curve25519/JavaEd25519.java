package peergos.crypto.asymmetric.curve25519;

import peergos.crypto.*;

public class JavaEd25519 implements Ed25519 {

    @Override
    public byte[] crypto_sign_open(byte[] signed, byte[] publicSigningKey) {
        return TweetNaCl.crypto_sign_open(signed, publicSigningKey);
    }

    @Override
    public byte[] crypto_sign(byte[] message, byte[] secretSigningKey) {
        return TweetNaCl.crypto_sign(message, secretSigningKey);
    }
}
