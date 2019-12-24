package peergos.shared.user.fs.erasure;

public class GaloisField256 extends GaloisField
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
        //log[exp[255]] = 255;
        // check

        for (int i=0; i < 255; i++) {
            if (log[exp[i]] != i)
                throw new IllegalStateException("log[exp["+i+"]] != "+i+", exp["+i+"] = "+exp[i]+", log["+exp[i]+"] = " + log[exp[i]]);
            if (i > 0 && exp[log[i]] != i)
                throw new IllegalStateException("exp[log["+i+"]] != "+ i);
        }
    }

    public int size()
    {
        return SIZE;
    }

    public int mask()
    {
        return SIZE-1;
    }

    public int exp(int y)
    {
        return exp[y];
    }

    public int mul(int x, int y)
    {
        if ((x==0) || (y==0))
            return 0;
        return exp[log[x]+log[y]];
    }

    public int div(int x, int y)
    {
        if (y==0)
            throw new IllegalStateException("Divided by zero! Blackhole created.. ");
        if (x==0)
            return 0;
        return exp[log[x]+255-log[y]];
    }

    public static void main(String[] a) {

    }
}
