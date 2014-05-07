package peergos.user.fs;

import peergos.crypto.SymmetricKey;
import peergos.crypto.User;
import peergos.user.fs.erasure.Erasure;

public class EncryptedChunk
{
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
        this(Erasure.recombine(fragments, originalSize, ERASURE_ORIGINAL, ERASURE_ALLOWED_FAILURES));
    }

    public byte[] decrypt(SymmetricKey key, byte[] iv)
    {
        return key.decrypt(encrypted, iv);
    }

    public Fragment[] generateFragments()
    {
        byte[][] bfrags = Erasure.split(encrypted, ERASURE_ORIGINAL, ERASURE_ALLOWED_FAILURES);
        Fragment[] frags = new Fragment[bfrags.length];
        for (int i=0; i < frags.length; i++)
            frags[i] = new Fragment(bfrags[i]);
        return frags;
    }
}
