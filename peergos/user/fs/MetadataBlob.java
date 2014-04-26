package peergos.user.fs;

import java.io.*;

public class MetadataBlob
{
    private final byte[][] fragmentHashes;
    private final byte[] initVector;

    public MetadataBlob(Fragment[] fragments, byte[] initVector)
    {
        fragmentHashes = new byte[fragments.length][];
        for (int i=0; i < fragments.length; i++)
            fragmentHashes[i] = fragments[i].getHash();
        this.initVector = initVector;
    }

    public MetadataBlob(byte[] decrypted)
    {
        try {
            initVector = new byte[EncryptedChunk.IV_SIZE];
            DataInputStream din = new DataInputStream(new ByteArrayInputStream(decrypted));
            din.readFully(initVector);
            fragmentHashes = new byte[din.readInt()][];
            int hashSize = din.readInt();
            for (int i=0; i < fragmentHashes.length; i++) {
                fragmentHashes[i] = new byte[hashSize];
                din.readFully(fragmentHashes[i]);
            }
        } catch (IOException e)
        {
            e.printStackTrace(); // shouldn't ever happen
            throw new IllegalStateException(e.getMessage());
        }
    }

    public byte[] serialize()
    {
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        try {
            bout.write(initVector, 0, initVector.length);
            DataOutputStream dout = new DataOutputStream(bout);
            dout.writeInt(fragmentHashes.length);
            dout.writeInt(fragmentHashes[0].length);
            for (int i = 0; i < fragmentHashes.length; i++)
                bout.write(fragmentHashes[i], 0, fragmentHashes[i].length);
        } catch (IOException e) {
            e.printStackTrace(); // shouldn't ever happen
        }
        return bout.toByteArray();
    }
}
