package peergos.util;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public class Serialize
{

    public static void serialize(byte[] b, DataOutputStream dout) throws IOException
    {
        dout.writeInt(b.length);
        dout.write(b);
    }

    public static void serialize(String s, DataOutputStream dout) throws IOException
    {
        dout.writeInt(s.length());
        dout.write(s.getBytes());
    }

    public static String deserializeString(DataInputStream din, int len) throws IOException
    {
        int l = din.readInt();
        if (l > len)
            throw new IOException("String size "+ l + " too long.");
        byte[] b = new byte[l];
        din.readFully(b);
        return new String(b);
    }

    public static byte[] deserializeByteArray(DataInputStream din, int maxLength) throws IOException
    {
        int l = din.readInt();
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
