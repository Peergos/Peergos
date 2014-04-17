package peergos.tests;

import org.junit.Test;
import peergos.fs.erasure.GaloisPolynomial;

import java.util.Arrays;
import java.util.Random;

public class ErasureCodes
{

    @Test
    public void apiTest()
    {
        Random r = new Random();
        int size = 100;
        int nec = (int)(0.4*size);
        byte[] bytes = new byte[size];
        r.nextBytes(bytes);
        int[] input = convert(bytes);
        int[] encoded = GaloisPolynomial.encode(input, nec);
        int[] original = Arrays.copyOf(encoded, encoded.length);
//        System.out.printf("Original:  ");
//        print(encoded);
        // add errors
        for (int i=0; i < nec/2-1; i++)
            encoded[r.nextInt(encoded.length)] = 0;
        int[] decoded = GaloisPolynomial.decode(encoded, nec);
//        System.out.printf("Decoded:   ");
//        print(decoded);
        assert(Arrays.equals(decoded, original));
    }

    public static void print(int[] d)
    {
        for (int i: d)
            System.out.printf("%02x ", i);
        System.out.println();
    }

    public static int[] convert(byte[] in)
    {
        int[] res = new int[in.length];
        for (int i=0; i < in.length; i++)
            res[i] = 0xFF & in[i];
        return res;
    }
}
