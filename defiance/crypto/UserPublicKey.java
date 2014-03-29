package defiance.crypto;

import org.bouncycastle.jce.provider.BouncyCastleProvider;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import java.io.UnsupportedEncodingException;
import java.security.*;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;

public class UserPublicKey
{
    public static final int RSA_KEY_SIZE = 4096;
    public static final int MAX_USERNAME_BYTES = 28;
    public static final int PUBLIC_KEY_DUPLICATION = 10;
    public static final String AUTH = "RSA";
    public static final String FILE = "AES";
    public static final String HASH = "SHA-256";
    public static final String SECURE_RANDOM = "SHA1PRNG"; // TODO: need to figure out an implementation using HMAC-SHA-256

    private final Key publicKey;

    public UserPublicKey(Key pub)
    {
        this.publicKey = pub;
    }

    public UserPublicKey(byte[] encodedPublicKey)
    {
        try {
            publicKey = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(encodedPublicKey));
        } catch (NoSuchAlgorithmException e)
        {
            throw new IllegalStateException("Couldn't create public key");
        } catch (InvalidKeySpecException e)
        {
            throw new IllegalStateException("Couldn't create public key");
        }
    }

    public byte[] getPublicKey()
    {
        return publicKey.getEncoded();
    }

    public byte[] encryptMessageFor(byte[] input)
    {
        try {
            Cipher c = Cipher.getInstance(AUTH);
            c.init(Cipher.ENCRYPT_MODE, publicKey);
            return c.doFinal(input);
        } catch (NoSuchAlgorithmException e)
        {
            e.printStackTrace();
            return null;
        } catch (NoSuchPaddingException e)
        {
            e.printStackTrace();
            return null;
        } catch (InvalidKeyException e)
        {
            e.printStackTrace();
            return null;
        } catch (IllegalBlockSizeException e)
        {
            e.printStackTrace();
            return null;
        } catch (BadPaddingException e)
        {
            e.printStackTrace();
            return null;
        }
    }

    public byte[] unsignMessage(byte[] input)
    {
        try {
            Cipher c = Cipher.getInstance(AUTH);
            c.init(Cipher.DECRYPT_MODE, publicKey);
            return c.doFinal(input);
        } catch (NoSuchAlgorithmException e)
        {
            e.printStackTrace();
            return null;
        } catch (NoSuchPaddingException e)
        {
            e.printStackTrace();
            return null;
        } catch (InvalidKeyException e)
        {
            e.printStackTrace();
            return null;
        } catch (IllegalBlockSizeException e)
        {
            e.printStackTrace();
            return null;
        } catch (BadPaddingException e)
        {
            e.printStackTrace();
            return null;
        }
    }

    public static void init() {}

    static
    {
        Security.addProvider(new BouncyCastleProvider());
    }

    public static byte[] hash(String password)
    {
        try {
            return hash(password.getBytes("UTF-8"));
        } catch (UnsupportedEncodingException e)
        {
            throw new IllegalStateException("couldn't hash password");
        }
    }

    public static byte[] hash(byte[] input)
    {
        try {
            MessageDigest md = MessageDigest.getInstance(HASH);
            md.update(input);
            return md.digest();
        } catch (NoSuchAlgorithmException e)
        {
            throw new IllegalStateException("couldn't hash password");
        }
    }

    public static byte[] recursiveHash(byte[] input, int recursion)
    {
        byte[] hash = input;
        for (int i=0; i < recursion; i++)
            hash = User.hash(hash);
        return hash;
    }
}
