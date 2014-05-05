package peergos.user.fs;

import peergos.crypto.UserPublicKey;

import java.io.*;
import java.util.Arrays;

public class Metadata
{
    private final byte[] fragmentHashes;
    private final byte[] initVector;

    public Metadata(Fragment[] fragments, byte[] initVector)
    {
        fragmentHashes = new byte[fragments.length * UserPublicKey.HASH_SIZE];
        for (int i=0; i < fragments.length; i++)
            System.arraycopy(fragments[i].getHash(), 0, fragmentHashes, i*UserPublicKey.HASH_SIZE, UserPublicKey.HASH_SIZE);
        this.initVector = initVector;
    }

    public byte[] getHashes(){
        return Arrays.copyOf(fragmentHashes, fragmentHashes.length);
    }

    public byte[] serialize()
    {
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        try {
            bout.write(initVector, 0, initVector.length);
            DataOutputStream dout = new DataOutputStream(bout);
            dout.writeInt(fragmentHashes.length);
            bout.write(fragmentHashes, 0, fragmentHashes.length);
        } catch (IOException e) {
            e.printStackTrace(); // shouldn't ever happen
        }
        return bout.toByteArray();
    }
}
