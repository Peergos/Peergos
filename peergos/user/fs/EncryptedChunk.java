package peergos.user.fs;

import peergos.crypto.User;
import peergos.user.fs.erasure.API;

import javax.crypto.*;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.*;

public class EncryptedChunk
{
    public static final int IV_SIZE = 16;
    public static final int ERASURE_ORIGINAL = 40;
    public static final int ERASURE_ALLOWED_FAILURES = 10;
    private final byte[] encrypted;
    private final byte[] hash;

    public EncryptedChunk(byte[] encrypted)
    {
        if (encrypted.length > Chunk.MAX_SIZE)
            throw new IllegalArgumentException("Encrypted chunk size ("+encrypted.length+") must be smaller than " + Chunk.MAX_SIZE);
        this.encrypted = encrypted;
        hash = User.hash(encrypted);
    }

    public EncryptedChunk(byte[][] fragments, int originalSize)
    {
        this(API.recombine(fragments, originalSize, ERASURE_ORIGINAL, ERASURE_ALLOWED_FAILURES));
    }

    public byte[] decrypt(SecretKey key, byte[] initVector)
    {
        try {
            Cipher cipher = Cipher.getInstance(Chunk.MODE, "BC");
            IvParameterSpec ivSpec = new IvParameterSpec(initVector);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key.getEncoded(), Chunk.ALGORITHM), ivSpec);
            return cipher.doFinal(encrypted);
        } catch (NoSuchAlgorithmException|NoSuchProviderException|NoSuchPaddingException|IllegalBlockSizeException|
                BadPaddingException|InvalidKeyException|InvalidAlgorithmParameterException e)
        {
            e.printStackTrace();
            throw new IllegalStateException("Couldn't decrypt chunk: "+e.getMessage());
        }
    }

    public Fragment[] generateFragments()
    {
        byte[][] bfrags = API.split(encrypted, ERASURE_ORIGINAL, ERASURE_ALLOWED_FAILURES);
        Fragment[] frags = new Fragment[bfrags.length];
        for (int i=0; i < frags.length; i++)
            frags[i] = new Fragment(bfrags[i]);
        return frags;
    }
}
