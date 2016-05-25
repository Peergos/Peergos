package peergos.user.fs;

import peergos.crypto.symmetric.SymmetricKey;

import java.util.*;

public class Chunk {
    public static final int ERASURE_ORIGINAL= 40;
    public static final int MAX_SIZE = Fragment.MAX_LENGTH * ERASURE_ORIGINAL;

    private final SymmetricKey key;
    private final byte[] data, mapKey;
    private final byte[] nonce;

    public Chunk(byte[] data, SymmetricKey key, byte[] mapKey, byte[] nonce) {
        this.data = data;
        this.key = key;
        this.mapKey = mapKey;
        this.nonce = nonce;
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

    public byte[] data() {
        return Arrays.copyOfRange(data, 0, data.length);
    }
}
