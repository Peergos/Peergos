package peergos.user.fs;

import peergos.crypto.SymmetricKey;
import peergos.crypto.User;
import peergos.crypto.UserPublicKey;

import javax.crypto.*;
import java.security.*;

public class Chunk
{
    public static final int MAX_SIZE = Fragment.SIZE*EncryptedChunk.ERASURE_ORIGINAL;

    private final byte[] data;
    private final SymmetricKey keyHolder;

    public Chunk(byte[] data, SymmetricKey key)
    {
        if (data.length > MAX_SIZE)
            throw new IllegalArgumentException("Chunk size can be at most " + MAX_SIZE);
        this.data = data;
        this.keyHolder = key;
    }

    public SymmetricKey getKey()
    {
        return keyHolder;
    }

    public byte[] encrypt(byte[] iv)
    {
        return keyHolder.encrypt(data, iv);
    }
}
