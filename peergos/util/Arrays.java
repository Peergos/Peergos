package peergos.util;

public class Arrays
{

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
}
