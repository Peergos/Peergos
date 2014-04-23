package peergos.fs;

import peergos.crypto.User;

public class Fragment
{
    public static final int SIZE = 128*1024;

    private byte[] data;
    private byte[] hash;

    public Fragment(byte[] data)
    {
        this.data = data;
        this.hash = User.hash(data);
    }

    public byte[] getHash()
    {
        return hash;
    }
}
