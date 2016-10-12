package peergos.shared.util;

import peergos.shared.user.fs.*;

import java.io.*;
import java.util.Arrays;
import java.util.concurrent.*;

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

    public static byte[] readFully(InputStream in) throws IOException {
        ByteArrayOutputStream bout =  new ByteArrayOutputStream();
        byte[] b =  new  byte[0x1000];
        int nRead = -1;
        while ((nRead = in.read(b, 0, b.length)) != -1 )
            bout.write(b, 0, nRead);
        return bout.toByteArray();
    }

    public static CompletableFuture<byte[]> readFully(AsyncReader in, long size) {
        byte[] res = new byte[(int)size];
        return in.readIntoArray(res, 0, (int) size).thenApply(i -> res);
    }

    public static CompletableFuture<Boolean> readFullArray(AsyncReader in, byte[] result) {
        return in.readIntoArray(result, 0, result.length).thenApply(b -> true);
    }

    public static byte[] ensureSize(byte[] data, int  size) {
        boolean iBigger = data.length < size;
        return  iBigger ? Arrays.copyOf(data, size) : data;
    }
}
