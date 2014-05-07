package peergos.crypto;

import org.bouncycastle.asn1.nist.NISTObjectIdentifiers;

import javax.crypto.*;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.*;

public class SymmetricKey
{
    public static final String ALGORITHM = "AES";
    //    public static final String MODE = "AES/ECB/NoPadding"; // INSECURE, don't use ECB
//    public static final String MODE = "AES/CBC/NoPadding";
    public static final String MODE = "AES/CFB/NoPadding";
    public static final String TYPE = NISTObjectIdentifiers.id_aes256_CFB.getId();
    public static final int IV_SIZE = 16;

    private final SecretKey key;

    public SymmetricKey(SecretKey key)
    {
        this.key = key;
    }

    public SymmetricKey(byte[] encoded)
    {
        this.key = new SecretKeySpec(encoded, ALGORITHM);
    }

    public SecretKey getKey()
    {
        return key;
    }

    public byte[] encrypt(byte[] data, byte[] initVector)
    {
        return encrypt(key, data, initVector);
    }

    public byte[] decrypt(byte[] data, byte[] initVector)
    {
        return decrypt(key, data, initVector);
    }

    public static byte[] encrypt(SecretKey key, byte[] data, byte[] initVector)
    {
        try {
            Cipher cipher = Cipher.getInstance(TYPE, "BC");
            IvParameterSpec ivSpec = new IvParameterSpec(initVector);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key.getEncoded(), ALGORITHM), ivSpec);
            return cipher.doFinal(data);
        } catch (NoSuchAlgorithmException |NoSuchProviderException |NoSuchPaddingException |IllegalBlockSizeException |
                BadPaddingException |InvalidKeyException |InvalidAlgorithmParameterException e)
        {
            e.printStackTrace();
            throw new IllegalStateException("Couldn't encrypt chunk: "+e.getMessage());
        }
    }

    public static byte[] decrypt(SecretKey key, byte[] data, byte[] initVector)
    {
        try {
            Cipher cipher = Cipher.getInstance(MODE, "BC");
            IvParameterSpec ivSpec = new IvParameterSpec(initVector);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key.getEncoded(), ALGORITHM), ivSpec);
            return cipher.doFinal(data);
        } catch (NoSuchAlgorithmException |NoSuchProviderException |NoSuchPaddingException |IllegalBlockSizeException |
                BadPaddingException |InvalidKeyException |InvalidAlgorithmParameterException e)
        {
            e.printStackTrace();
            throw new IllegalStateException("Couldn't encrypt chunk: "+e.getMessage());
        }
    }

    private static SecureRandom random = new SecureRandom();

    public static byte[] randomIV()
    {
        byte[] res = new byte[IV_SIZE];
        random.nextBytes(res);
        return res;
    }

    public static SymmetricKey random()
    {
        try {
//            SecureRandom random = SecureRandom.getInstance(User.SECURE_RANDOM);
//            KeyGenerator kgen = KeyGenerator.getInstance(ALGORITHM, "BC");
//            int keySize = 256;
//            kgen.init(keySize, random);
//            SecretKey key = kgen.generateKey();
            SecretKey key = KeyGenerator.getInstance(TYPE, "BC").generateKey();
            return new SymmetricKey(key);
        } catch (NoSuchAlgorithmException|NoSuchProviderException e) {
            e.printStackTrace();
            throw new IllegalStateException(e.getMessage());
        }
    }
}
