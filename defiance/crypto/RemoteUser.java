package defiance.crypto;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import java.security.InvalidKeyException;
import java.security.Key;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;

public class RemoteUser
{
    public static final int RSA_KEY_SIZE = 4096;
    public static final String AUTH = "RSA";
    public static final String FILE = "AES";
    public static final String HASH = "SHA-256";

    private final Key publicKey;

    public RemoteUser(Key pub)
    {
        this.publicKey = pub;
    }

    public RemoteUser(byte[] encodedPublicKey)
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
}
