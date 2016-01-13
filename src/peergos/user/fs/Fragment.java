package peergos.user.fs;

import peergos.crypto.Hash;

public class Fragment
{
    public static final int SIZE = 128*1024;

    private final byte[] data;
    private final byte[] hash;

    public Fragment(byte[] data)
    {
        this.data = data;
        this.hash = Hash.sha256(data);
    }

    public byte[] getHash()
    {
        return hash;
    }

    public byte[] getData() {
        return data;
    }
}
