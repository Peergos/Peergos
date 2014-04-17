package peergos.fs;

import peergos.crypto.User;
import peergos.crypto.UserPublicKey;

import javax.crypto.*;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.*;

public class EncryptedChunk
{
    private final byte[] encrypted;
    private final byte[] hash;

    public EncryptedChunk(byte[] encrypted)
    {
        if (encrypted.length > Chunk.MAX_SIZE)
            throw new IllegalArgumentException("Encrypted chunk size ("+encrypted.length+") must be smaller than " + Chunk.MAX_SIZE);
        this.encrypted = encrypted;
        hash = User.hash(encrypted);
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

    public Fragment[] generateFragments(int required, int extra)
    {
        return null;
    }
}
