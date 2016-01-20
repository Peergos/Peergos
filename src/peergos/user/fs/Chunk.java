package peergos.user.fs;

import peergos.crypto.symmetric.SymmetricKey;
import peergos.util.ByteArrayWrapper;

import java.security.SecureRandom;
import java.util.List;
import java.util.Random;

public class Chunk {
    public static final long ERASURE_ORIGINAL= 40;
    public static final long MAX_SIZE = Fragment.MAX_LENGTH * ERASURE_ORIGINAL;

    private static final Random random = new SecureRandom();

    private final SymmetricKey key;
    private final ByteArrayWrapper data, nonce, mapKey;

    public Chunk(SymmetricKey key, ByteArrayWrapper data) {
        this(key, data, build(SymmetricKey.NONCE_BYTES), build(Location.MAP_KEY_LENGTH));
    }

    public Chunk(SymmetricKey key, ByteArrayWrapper data, ByteArrayWrapper nonce, ByteArrayWrapper mapKey) {
        this.key = key;
        this.data = data;
        this.nonce = nonce;
        this.mapKey = mapKey;
    }

    ByteArrayWrapper toEncrypted() {
        return new ByteArrayWrapper(key.encrypt(data.data, nonce.data));
    }

    //TODO : generateFragments


    private static ByteArrayWrapper build(int length) {
        byte[] b = new  byte[length];
        random.nextBytes(b);
        return new ByteArrayWrapper(b);
    }
}
