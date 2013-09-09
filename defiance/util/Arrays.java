package defiance.util;

public class Arrays
{

    public static long getLong(byte[] source, int start)
    {
        long res = 0;
        for (int i=0; i < 8; i++)
            res = (res << 8) | (source[start+i] & 0xFF);
        return res;
    }
}
