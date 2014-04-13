package peergos.fs;

import peergos.crypto.User;
import peergos.crypto.UserPublicKey;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.SecretKeySpec;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

public class Chunk
{
    public static final int MAX_SIZE = 4*1024*1024;
    public static final String CIPHER = "AES";

    private final byte[] data;

    public Chunk(byte[] data)
    {
        this.data = data;
    }

    public SecretKey generateKey()
    {
        try {
            byte[] hash = UserPublicKey.hash(data);
            SecureRandom random = SecureRandom.getInstance(User.SECURE_RANDOM);
            random.setSeed(hash);
            KeyGenerator kgen = KeyGenerator.getInstance("AES");
            int keySize = 256;
            kgen.init(keySize, random);
            SecretKey key = kgen.generateKey();
//            SecretKeyFactory keyFactory = SecretKeyFactory.getInstance("PBEWithSHA256And256BitAES-CBC-BC");
//            SecretKeySpec secretKey = new SecretKeySpec(keyFactory.generateSecret(keySpec).getEncoded(), "AES");
//            byte[] key = secretKey.getEncoded();
            return  key;
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
            throw new IllegalStateException(e.getMessage());
        }
    }

    public byte[] encrypt()
    {

    }
}
