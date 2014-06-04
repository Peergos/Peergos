package peergos.crypto;

import java.security.*;
import javax.crypto.*;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;
import java.util.Enumeration;
import java.util.Set;

public class User extends UserPublicKey
{
    private final PrivateKey privateKey;

    public User(KeyPair pair)
    {
        super(pair.getPublic());
        privateKey = pair.getPrivate();
    }

    public User(PrivateKey privateKey, PublicKey publicKey)
    {
        super(publicKey);
        this.privateKey = privateKey;
    }

    public byte[] hashAndSignMessage(byte[] input)
    {
        byte[] hash = hash(input);
        return signMessage(hash);
    }

    public byte[] signMessage(byte[] input)
    {
        try {
            Cipher c = Cipher.getInstance(AUTH, "BC");
            c.init(Cipher.ENCRYPT_MODE, privateKey);
            return c.doFinal(input);
        } catch (NoSuchAlgorithmException|NoSuchProviderException e)
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
            Cipher c = Cipher.getInstance(AUTH, "BC");
            c.init(Cipher.DECRYPT_MODE, privateKey);
            return c.doFinal(input);
        } catch (NoSuchAlgorithmException|NoSuchProviderException e)
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
            PublicKey publicKey = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(pub));
            PrivateKey privateKey = KeyFactory.getInstance("RSA").generatePrivate(new X509EncodedKeySpec(priv));;
            return new User(privateKey, publicKey);
        } catch (NoSuchAlgorithmException e)
        {
            throw new IllegalStateException("Couldn't create public key");
        } catch (InvalidKeySpecException e)
        {
            throw new IllegalStateException("Couldn't create public key");
        }
    }

    public static KeyPair generateKeyPair()
    {
        try
        {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance(AUTH, "BC");
            kpg.initialize(RSA_KEY_SIZE);
            return kpg.genKeyPair();
        } catch (NoSuchAlgorithmException|NoSuchProviderException e)
        {
            throw new IllegalStateException("No algorithm: "+AUTH);
        }
    }

    public static User random()
    {
        return new User(generateKeyPair());
    }

    public static User generateUserCredentials(String username, String password)
    {
        // need usernames and public keys to be in 1-1 correspondence, and the private key to be derivable from the username+password
        // username is essentially salt against rainbow table attacks
        byte[] hash = hash(username+password);
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance(AUTH, "BC");
            SecureRandom random = SecureRandom.getInstance(SECURE_RANDOM);
            random.setSeed(hash);
            kpg.initialize(RSA_KEY_SIZE, random);
            long start = System.nanoTime();
            User u = new User(kpg.generateKeyPair());
            long end = System.nanoTime();
            System.out.printf("User credential generation took %d mS\n", (end-start)/1000000);
            return u;
        } catch (NoSuchAlgorithmException|NoSuchProviderException e)
        {
            throw new IllegalStateException("Couldn't generate key-pair from password - "+e.getMessage());
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
//            e.printStackTrace();
        }
    }

    public static void enumerateAllCryptoAlgorithmsAvailable()
    {
        try {
            Provider p[] = Security.getProviders();
            for (int i = 0; i < p.length; i++) {
                System.out.println(p[i]);
                for (Enumeration e = p[i].keys(); e.hasMoreElements();)
                    System.out.println("\t" + e.nextElement());
            }
        } catch (Exception e) {
            System.out.println(e);
        }
    }

    public static boolean testEncryptionCapabilities()
    {
        checkAllowedKeySizes();

        byte[] input = "Hello encryptor!".getBytes();
        try
        {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance(AUTH, "BC");
            kpg.initialize(RSA_KEY_SIZE);
            KeyPair kp = kpg.genKeyPair();
            Key publicKey = kp.getPublic();
            Key privateKey = kp.getPrivate();
            Cipher cipher = Cipher.getInstance(AUTH, "BC");
            cipher.init(Cipher.ENCRYPT_MODE, publicKey);

            byte[] cipherText = cipher.doFinal(input);

            cipher.init(Cipher.DECRYPT_MODE, privateKey);
            byte[] plainText = cipher.doFinal(cipherText);
            return true;
        } catch (NoSuchAlgorithmException|NoSuchProviderException e)
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
