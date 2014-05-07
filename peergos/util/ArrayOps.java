package peergos.util;

import java.util.Random;

public class ArrayOps
{
    public static byte[] concat(byte[] one, byte[] two)
    {
        byte[] res = new byte[one.length+two.length];
        System.arraycopy(one, 0, res, 0, one.length);
        System.arraycopy(two, 0, res, one.length, two.length);
        return res;
    }

    public static byte[] concat(byte[] one, byte[] two, byte[] three)
    {
        byte[] res = new byte[one.length+two.length+three.length];
        System.arraycopy(one, 0, res, 0, one.length);
        System.arraycopy(two, 0, res, one.length, two.length);
        System.arraycopy(three, 0, res, one.length+two.length, three.length);
        return res;
    }

    public static long getLong(byte[] source, int start)
    {
        long res = 0;
        for (int i=0; i < 8; i++)
            res = (res << 8) | (source[start+i] & 0xFF);
        return res;
    }

    public static byte[] hexToBytes(String hex)
    {
        byte[] res = new byte[hex.length()/2];
        for (int i=0; i < res.length; i++)
            res[i] = (byte) Integer.parseInt(hex.substring(2*i, 2*i+2), 16);
        return res;
    }

    public static String bytesToHex(byte[] data)
    {
        StringBuilder s = new StringBuilder();
        for (byte b : data)
            s.append(String.format("%02x", b & 0xFF));
        return s.toString();
    }

    public static byte[] random(int length)
    {
        byte[] res = new byte[length];
        Random r = new Random();
        r.setSeed(System.nanoTime());
        r.nextBytes(res);
        return res;
    }
}
