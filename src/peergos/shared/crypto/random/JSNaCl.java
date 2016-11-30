package peergos.shared.crypto.random;

import jsinterop.annotations.JsType;

@JsType(namespace = "tweetNaCl", isNative = true)
public class JSNaCl {
    native public byte[] randombytes(int len);

    native public byte[] secretbox(byte[] data, byte[] nonce, byte[] key);
    native public byte[] secretbox_open(byte[] cipher, byte[] nonce, byte[] key);

    native public byte[] crypto_sign_open(byte[] signed, byte[] publicSigningKey);
    native public byte[] crypto_sign(byte[] message, byte[] secretSigningKey);
    native public void crypto_sign_keypair(byte[] pk, byte[] sk);
}
