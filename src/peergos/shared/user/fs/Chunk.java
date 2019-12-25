package peergos.shared.user.fs;

import peergos.shared.crypto.symmetric.SymmetricKey;

import java.util.*;
import java.util.concurrent.CompletableFuture;

public class Chunk {

    public static final int MAX_SIZE = 5 * 1024 * 1024;

    private final SymmetricKey dataKey;
    private final byte[] data, mapKey;
    private final byte[] nonce;

    public Chunk(byte[] data, SymmetricKey dataKey, byte[] mapKey, byte[] nonce) {
        this.data = data;
        this.dataKey = dataKey;
        this.mapKey = mapKey;
        this.nonce = nonce;
    }

    public SymmetricKey key() {
        return dataKey;
    }

    public byte[] mapKey() {
        return Arrays.copyOfRange(mapKey, 0, mapKey.length);
    }

    public byte[] nonce() {
        return Arrays.copyOfRange(nonce, 0, nonce.length);
    }

    public byte[] data() {
        return data;
    }

    public int length() {
        return data.length;
    }
}
