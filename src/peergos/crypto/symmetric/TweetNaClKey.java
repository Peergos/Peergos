package peergos.crypto.symmetric;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import peergos.crypto.TweetNaCl;

import java.security.SecureRandom;
import java.security.Security;

public class TweetNaClKey implements SymmetricKey
{
    public static final int KEY_BYTES = 32;
    public static final int NONCE_BYTES = 24;

    private final byte[] secretKey;

    public TweetNaClKey(byte[] encoded)
    {
        this.secretKey = encoded;
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

    public static TweetNaClKey random()
    {
        byte[] key = new byte[KEY_BYTES];
        csprng.nextBytes(key);
        return new TweetNaClKey(key);
    }

    static
    {
        Security.addProvider(new BouncyCastleProvider());
    }
}
