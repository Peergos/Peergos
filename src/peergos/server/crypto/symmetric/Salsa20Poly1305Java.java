package peergos.server.crypto.symmetric;

import peergos.server.crypto.*;
import peergos.shared.crypto.symmetric.*;

import java.util.concurrent.CompletableFuture;

public class Salsa20Poly1305Java implements Salsa20Poly1305 {

    @Override
    public byte[] secretbox(byte[] data, byte[] nonce, byte[] key) {
        return TweetNaCl.secretbox(data, nonce, key);
    }

    @Override
    public byte[] secretbox_open(byte[] cipher, byte[] nonce, byte[] key) {
        return TweetNaCl.secretbox_open(cipher, nonce, key);
    }

    @Override
    public CompletableFuture<byte[]> secretboxAsync(byte[] data, byte[] nonce, byte[] key) {
        byte[] encrypted = TweetNaCl.secretbox(data, nonce, key);
        CompletableFuture<byte[]> res = new CompletableFuture<>();
        res.complete(encrypted);
        return res;
    }

    @Override
    public CompletableFuture<byte[]> secretbox_openAsync(byte[] cipher, byte[] nonce, byte[] key) {
        byte[] decrypted = TweetNaCl.secretbox_open(cipher, nonce, key);
        CompletableFuture<byte[]> res = new CompletableFuture<>();
        res.complete(decrypted);
        return res;
    }
}
