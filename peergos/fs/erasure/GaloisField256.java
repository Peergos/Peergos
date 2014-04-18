package peergos.fs.erasure;

public class GaloisField256
{
    // Theory obtained from BBC White paper WHP 031 - Reed-solomon error correction, C.K.P. Clarke

    private static final int SIZE = 256;
    private static final int[] exp = new int[2*SIZE];
    private static final int[] log = new int[SIZE];
    static {
        exp[0] = 1;
        int x = 1;
        for (int i=1; i < 255; i++)
        {
            x <<= 1;
            // field generator polynomial is p(x) = x^8 + x^4 + x^3 + x^2 + 1
            if ((x & SIZE) != 0)
                x ^= (SIZE | 0x1D); // x^8 = x^4 + x^3 + x^2 + 1  ==> 0001_1101
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

//        System.out.printf("Exp: ");
//        ErasureCodes.print(exp);
//        System.out.printf("Log: ");
//        ErasureCodes.print(log);
    }

    public static int size()
    {
        return SIZE;
    }

    public static int mask()
    {
        return SIZE-1;
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
