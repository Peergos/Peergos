package peergos.crypto.symmetric;

import peergos.crypto.TweetNaCl;

import java.security.SecureRandom;

public class TweetNaClKey implements SymmetricKey
{
    public static final int KEY_BYTES = 32;
    public static final int NONCE_BYTES = 24;

    private final byte[] secretKey;
    private final Salsa20Poly1305 implementation;

    public TweetNaClKey(byte[] encoded, Salsa20Poly1305 implementation)
    {
        this.secretKey = encoded;
        this.implementation = implementation;
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
        long t1 = System.currentTimeMillis();
        try {
            return implementation.secretbox(data, nonce, key);
        } finally {
            System.out.println("Encryption took: "+ (System.currentTimeMillis() - t1) + "mS for " + data.length + " bytes");
        }
    }

    public static byte[] decrypt(byte[] key, byte[] cipher, byte[] nonce, Salsa20Poly1305 implementation)
    {
        long t1 = System.currentTimeMillis();
        try {
            return implementation.secretbox_open(cipher, nonce, key);
        } finally {
            System.out.println("Decryption took: "+ (System.currentTimeMillis() - t1) + "mS for " + cipher.length + " bytes");
        }
    }

    private static SecureRandom csprng = new SecureRandom();

    public byte[] createNonce()
    {
        byte[] res = new byte[NONCE_BYTES];
        csprng.nextBytes(res);
        return res;
    }

    public static TweetNaClKey random(Salsa20Poly1305 provider)
    {
        byte[] key = new byte[KEY_BYTES];
        csprng.nextBytes(key);
        return new TweetNaClKey(key, provider);
    }
}