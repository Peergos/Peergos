package peergos.util;

import java.io.*;

public class Serialize
{

    public static void serialize(byte[] b, DataOutput dout) throws IOException
    {
        dout.writeInt(b.length);
        if (b.length > 0)
            dout.write(b);
    }

    public static void serialize(String s, DataOutput dout) throws IOException
    {
        dout.writeInt(s.length());
        dout.write(s.getBytes());
    }

    public static String deserializeString(DataInput din, int len) throws IOException
    {
        int l = din.readInt();
        if (l > len)
            throw new IOException("String size "+ l + " too long.");
        byte[] b = new byte[l];
        din.readFully(b);
        return new String(b);
    }

    public static byte[] deserializeByteArray(DataInput din, int maxLength) throws IOException
    {
        int l = din.readInt();
        if (l == 0)
            return new byte[0];

        byte[] b = getByteArray(l, maxLength);
        din.readFully(b);
        return b;
    }

    public static byte[] getByteArray(int len, int maxLength) throws IOException
    {
        if (len > maxLength)
            throw new IOException("byte array of size "+ len +" too big.");
        return new byte[len];
    }
}
