package peergos.fs.erasure;

public class GaloisField
{
    public static final int SIZE = 256;
    private static final int[] exp = new int[2*SIZE];
    private static final int[] log = new int[SIZE];
    static {
        exp[0] = 1;
        int x = 1;
        for (int i=1; i < 255; i++)
        {
            x <<= 1;
            if ((x & 0x100) != 0)
                x ^= 0x11d;
            exp[i] = x;
            log[x] = i;
        }
        for (int i=255; i < 512; i++)
            exp[i] = exp[i-255];
        log[exp[255]] = 255;
        // check
        for (int i=0; i < 256; i++) {
            assert (log[exp[i]] == i);
            assert (exp[log[i]] == i);
        }
    }

    public static int exp(int y)
    {
        return exp[y];
    }

    public static int mul(int x, int y)
    {
        if ((x==0) || (y==0))
            return 0;
        return exp[log[x]+log[y]];
    }

    public static int div(int x, int y)
    {
        if (y==0)
            throw new IllegalStateException("Divided by zero! Blackhole created.. ");
        if (x==0)
            return 0;
        return exp[log[x]+255-log[y]];
    }
}
