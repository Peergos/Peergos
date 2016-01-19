package peergos.user.fs;

import peergos.crypto.*;
import peergos.crypto.symmetric.SymmetricKey;
import peergos.user.fs.erasure.Erasure;
import peergos.util.*;

import java.util.*;

public class EncryptedChunk
{
    public static final int ERASURE_ORIGINAL = 40;
    public static final int ERASURE_ALLOWED_FAILURES = 10;
    private final byte[] auth;
    private final byte[] encrypted;

    public EncryptedChunk(byte[] encrypted)
    {
        if (encrypted.length > Chunk.MAX_SIZE + TweetNaCl.SECRETBOX_OVERHEAD_BYTES)
            throw new IllegalArgumentException("Encrypted chunk size ("+encrypted.length+") must be at most " + (Chunk.MAX_SIZE + TweetNaCl.SECRETBOX_OVERHEAD_BYTES));
        this.auth = Arrays.copyOfRange(encrypted, 0, TweetNaCl.SECRETBOX_OVERHEAD_BYTES);
        this.encrypted = Arrays.copyOfRange(encrypted, TweetNaCl.SECRETBOX_OVERHEAD_BYTES, encrypted.length);
    }

    public EncryptedChunk(byte[][] fragments, int originalSize)
    {
        this(Erasure.recombine(fragments, originalSize, ERASURE_ORIGINAL, ERASURE_ALLOWED_FAILURES));
    }

    public byte[] getAuth() {
        return auth;
    }

    public byte[] decrypt(SymmetricKey key, byte[] iv)
    {
        return key.decrypt(ArrayOps.concat(auth, encrypted), iv);
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
