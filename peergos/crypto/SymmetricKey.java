package peergos.crypto;

import org.bouncycastle.jce.provider.BouncyCastleProvider;

import java.security.*;

public class SymmetricKey
{
    public static final int KEY_BYTES = 32;
    public static final int NONCE_BYTES = 24;

    private final byte[] secretKey;

    public SymmetricKey(byte[] encoded)
    {
        this.secretKey = encoded;
    }

    public byte[] getKey()
    {
        return secretKey;
    }

    public byte[] encrypt(byte[] data, byte[] initVector)
    {
        return encrypt(secretKey, data, initVector);
    }

    public byte[] decrypt(byte[] data, byte[] initVector)
    {
        return decrypt(secretKey, data, initVector);
    }

    public static byte[] encrypt(byte[] key, byte[] data, byte[] nonce)
    {
        return TweetNaCl.secretbox(data, nonce, key);
    }

    public static byte[] decrypt(byte[] key, byte[] cipher, byte[] nonce)
    {
        return TweetNaCl.secretbox_open(cipher, nonce, key);
    }

    private static SecureRandom csprng = new SecureRandom();

    public static byte[] createNonce()
    {
        byte[] res = new byte[NONCE_BYTES];
        csprng.nextBytes(res);
        return res;
    }

    public static SymmetricKey random()
    {
        byte[] key = new byte[KEY_BYTES];
        csprng.nextBytes(key);
        return new SymmetricKey(key);
    }

    static
    {
        Security.addProvider(new BouncyCastleProvider());
    }
}
