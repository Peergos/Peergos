package peergos.shared.crypto.asymmetric.curve25519;

import peergos.shared.crypto.*;

public class JavaEd25519 implements Ed25519 {

    @Override
    public byte[] crypto_sign_open(byte[] signed, byte[] publicSigningKey) {
        return TweetNaCl.crypto_sign_open(signed, publicSigningKey);
    }

    @Override
    public byte[] crypto_sign(byte[] message, byte[] secretSigningKey) {
        return TweetNaCl.crypto_sign(message, secretSigningKey);
    }

    @Override
    public void crypto_sign_keypair(byte[] pk, byte[] sk) {
        TweetNaCl.crypto_sign_keypair(pk, sk, true);
    }
}
