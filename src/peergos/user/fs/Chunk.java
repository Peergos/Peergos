package peergos.user.fs;

import peergos.crypto.*;
import peergos.crypto.symmetric.SymmetricKey;

import java.util.*;

public class Chunk {
    public static final int ERASURE_ORIGINAL= 40;
    public static final int MAX_SIZE = Fragment.MAX_LENGTH * ERASURE_ORIGINAL;

    private final SymmetricKey key;
    private final byte[] data, nonce = TweetNaCl.securedRandom(TweetNaCl.SECRETBOX_NONCE_BYTES), mapKey = TweetNaCl.securedRandom(32);

    public Chunk(byte[] data, SymmetricKey key) {
        this.key = key;
        this.data = data;
    }

    public EncryptedChunk encrypt() {
        return new EncryptedChunk(key.encrypt(data, nonce));
    }

    public SymmetricKey key() {
        return key;
    }

    public byte[] mapKey() {
        return Arrays.copyOfRange(mapKey, 0, mapKey.length);
    }

    public byte[] nonce() {
        return Arrays.copyOfRange(nonce, 0, nonce.length);
    }
}
