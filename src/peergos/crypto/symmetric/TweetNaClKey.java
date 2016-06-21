package peergos.crypto.symmetric;

import peergos.crypto.random.*;

import java.util.*;

public class TweetNaClKey implements SymmetricKey
{
    public static final int KEY_BYTES = 32;
    public static final int NONCE_BYTES = 24;

    private final byte[] secretKey;
    private final Salsa20Poly1305 implementation;
    private final SafeRandom random;

    public TweetNaClKey(byte[] encoded, Salsa20Poly1305 implementation, SafeRandom random)
    {
        this.secretKey = encoded;
        this.implementation = implementation;
        this.random = random;
    }

    public Type type() {
        return Type.TweetNaCl;
    }

    public byte[] getKey()
    {
        return secretKey;
    }

    public byte[] encrypt(byte[] data, byte[] nonce)
    {
        return encrypt(secretKey, data, nonce, implementation);
    }

    public byte[] decrypt(byte[] data, byte[] nonce)
    {
        return decrypt(secretKey, data, nonce, implementation);
    }

    public static byte[] encrypt(byte[] key, byte[] data, byte[] nonce, Salsa20Poly1305 implementation)
    {
        return implementation.secretbox(data, nonce, key);
    }

    public static byte[] decrypt(byte[] key, byte[] cipher, byte[] nonce, Salsa20Poly1305 implementation)
    {
        return implementation.secretbox_open(cipher, nonce, key);
    }

    public byte[] createNonce()
    {
        byte[] res = new byte[NONCE_BYTES];
        random.randombytes(res, 0, res.length);
        return res;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        TweetNaClKey that = (TweetNaClKey) o;

        return Arrays.equals(secretKey, that.secretKey);

    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(secretKey);
    }

    public static TweetNaClKey random(Salsa20Poly1305 provider, SafeRandom random)
    {
        byte[] key = new byte[KEY_BYTES];
        random.randombytes(key, 0, key.length);
        return new TweetNaClKey(key, provider, random);
    }
}