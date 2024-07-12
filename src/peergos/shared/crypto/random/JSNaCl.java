package peergos.shared.crypto.random;

import jsinterop.annotations.JsType;

import java.util.concurrent.CompletableFuture;

@JsType(namespace = "tweetNaCl", isNative = true)
public class JSNaCl {
    native public byte[] randombytes(int len);

    native public byte[] secretbox(byte[] data, byte[] nonce, byte[] key);
    native public byte[] secretbox_open(byte[] cipher, byte[] nonce, byte[] key);

    native public CompletableFuture<byte[]> crypto_sign_open(byte[] signed, byte[] publicSigningKey);
    native public CompletableFuture<byte[]> crypto_sign(byte[] message, byte[] secretSigningKey);
    native public byte[][] crypto_sign_keypair(byte[] pk, byte[] sk);

    native public byte[] crypto_box_open(byte[] cipher, byte[] nonce, byte[] theirPublicBoxingKey, byte[] secretBoxingKey);
    native public byte[] crypto_box(byte[] message, byte[] nonce, byte[] theirPublicBoxingKey, byte[] ourSecretBoxingKey);
    native public byte[] crypto_box_keypair(byte[] pk, byte[] sk);
}
