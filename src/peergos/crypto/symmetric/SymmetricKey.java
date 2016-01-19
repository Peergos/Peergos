package peergos.crypto.symmetric;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import peergos.crypto.TweetNaCl;

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

    public byte[] encrypt(byte[] data, byte[] nonce)
    {
        return encrypt(secretKey, data, nonce);
    }

    public byte[] decrypt(byte[] data, byte[] nonce)
    {
        return decrypt(secretKey, data, nonce);
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

    public byte[] createNonce()
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
