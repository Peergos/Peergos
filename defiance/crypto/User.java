package defiance.crypto;

import defiance.util.Arrays;

import java.security.*;
import javax.crypto.*;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;
import java.util.Set;

public class User extends RemoteUser
{
    private final Key privateKey;

    public User(KeyPair pair)
    {
        super(pair.getPublic());
        privateKey = pair.getPrivate();
    }

    public User(Key privateKey, Key publicKey)
    {
        super(publicKey);
        this.privateKey = privateKey;
    }

    public byte[] signMessage(byte[] input)
    {
        try {
            Cipher c = Cipher.getInstance(AUTH);
            c.init(Cipher.ENCRYPT_MODE, privateKey);
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

    public byte[] decryptMessage(byte[] input)
    {
        try {
            Cipher c = Cipher.getInstance(AUTH);
            c.init(Cipher.DECRYPT_MODE, privateKey);
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

    public static User create(byte[] priv, byte[] pub)
    {
        try {
            Key publicKey = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(pub));
            Key privateKey = KeyFactory.getInstance("RSA").generatePrivate(new X509EncodedKeySpec(priv));;
            return new User(privateKey, publicKey);
        } catch (NoSuchAlgorithmException e)
        {
            throw new IllegalStateException("Couldn't create public key");
        } catch (InvalidKeySpecException e)
        {
            throw new IllegalStateException("Couldn't create public key");
        }
    }

    public static User random()
    {
        try
        {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance(AUTH);
            kpg.initialize(RSA_KEY_SIZE);
            return new User(kpg.genKeyPair());
        } catch (NoSuchAlgorithmException e)
        {
            throw new IllegalStateException("No algorithm: "+AUTH);
        }
    }

    public static void checkAllowedKeySizes()
    {
        try {
            Set<String> algorithms = Security.getAlgorithms("Cipher");
            for(String algorithm: algorithms) {
                int max = Cipher.getMaxAllowedKeyLength(algorithm);
                System.out.printf("%-22s: %dbit%n", algorithm, max);
            }
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
        }
    }

    public static boolean testEncryptionCapabilities()
    {
        checkAllowedKeySizes();

        byte[] input = "Hello encryptor!".getBytes();
        try
        {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance(AUTH);
            kpg.initialize(RSA_KEY_SIZE);
            KeyPair kp = kpg.genKeyPair();
            Key publicKey = kp.getPublic();
            Key privateKey = kp.getPrivate();
            Cipher cipher = Cipher.getInstance(AUTH);
            cipher.init(Cipher.ENCRYPT_MODE, publicKey);

            byte[] cipherText = cipher.doFinal(input);

            cipher.init(Cipher.DECRYPT_MODE, privateKey);
            byte[] plainText = cipher.doFinal(cipherText);
            return true;
        } catch (NoSuchAlgorithmException e)
        {
            e.printStackTrace();
            return false;
        } catch (NoSuchPaddingException e)
        {
            e.printStackTrace();
            return false;
        } catch (InvalidKeyException e)
        {
            e.printStackTrace();
            return false;
        }  catch (IllegalBlockSizeException e)
        {
            e.printStackTrace();
            return false;
        } catch (BadPaddingException e)
        {
            e.printStackTrace();
            return false;
        }
    }
}
