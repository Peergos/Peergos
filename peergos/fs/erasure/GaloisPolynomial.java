package peergos.fs.erasure;

import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;

public class GaloisPolynomial
{
    private final int[] coefficients;

    private GaloisPolynomial(int[] coefficients)
    {
        if (coefficients.length > GaloisField4.size())
            throw new IllegalStateException("Polynomial order must be less than or equal to the degree of the Galois field.");
        this.coefficients = coefficients;
    }

    private int order()
    {
        return coefficients.length;
    }

    private int eval(int x)
    {
        int y = coefficients[0];
        for (int i=1; i < coefficients.length; i++)
            y = GaloisField4.mul(y, x) ^ coefficients[i];
        return y;
    }

    private GaloisPolynomial scale(int x)
    {
        int[] res = new int[coefficients.length];
        for (int i=0; i < res.length; i++)
            res[i] = GaloisField4.mul(x, coefficients[i]);
        return new GaloisPolynomial(res);
    }

    private GaloisPolynomial add(GaloisPolynomial other)
    {
        int[] res = new int[Math.max(order(), other.order())];
        for (int i=0; i < order(); i++)
            res[i + res.length - order()] = coefficients[i];
        for (int i=0; i < other.order(); i++)
            res[i + res.length - other.order()] ^= other.coefficients[i];
        return new GaloisPolynomial(res);
    }

    private GaloisPolynomial mul(GaloisPolynomial other)
    {
        int[] res = new int[order() + other.order() - 1];
        for (int i=0; i < order(); i++)
            for (int j=0; j < other.order(); j++)
                res[i+j] ^= GaloisField4.mul(coefficients[i], other.coefficients[j]);
        return new GaloisPolynomial(res);
    }

    private GaloisPolynomial append(int x)
    {
        int[] res = new int[coefficients.length+1];
        System.arraycopy(coefficients, 0, res, 0, coefficients.length);
        res[res.length-1] = x;
        return new GaloisPolynomial(res);
    }

    private static GaloisPolynomial generator(int nECSymbols)
    {
        GaloisPolynomial g = new GaloisPolynomial(new int[] {1});
        for (int i=0; i < nECSymbols; i++)
            g = g.mul(new GaloisPolynomial(new int[]{1, GaloisField4.exp(i)}));
        return g;
    }

    public static int[] encode(int[] input, int nEC)
    {
        GaloisPolynomial gen = generator(nEC);
        int[] res = new int[input.length + nEC];
        System.arraycopy(input, 0, res, 0, input.length);
        for (int i=0; i < input.length; i++)
        {
            int c = res[i];
            if (c != 0)
                for (int j=0; j < gen.order(); j++)
                    res[i+j] ^= GaloisField4.mul(gen.coefficients[j], c);
        }
        System.arraycopy(input, 0, res, 0, input.length);
        return res;
    }

    private static int[] syndromes(int[] input, int nEC)
    {
        int[] res = new int[nEC];
        GaloisPolynomial poly = new GaloisPolynomial(input);
        for (int i=0; i < nEC; i++)
            res[i] = poly.eval(GaloisField4.exp(i));
        return res;
    }

    private static void correctErrata(int[] input, int[] synd, List<Integer> pos)
    {
        if (pos.size() == 0)
            return;
        GaloisPolynomial q = new GaloisPolynomial(new int[]{1});
        for (int i: pos)
        {
            int x = GaloisField4.exp(input.length - 1 - i);
            q = q.mul(new GaloisPolynomial(new int[]{x, 1}));
        }
        int[] t = new int[pos.size()];
        for (int i=0; i < t.length; i++)
            t[i] = synd[t.length-1-i];
        GaloisPolynomial p = new GaloisPolynomial(t).mul(q);
        t = new int[pos.size()];
        System.arraycopy(p.coefficients, p.order()-t.length, t, 0, t.length);
        p = new GaloisPolynomial(t);
        t = new int[(q.order()- (q.order() & 1))/2];
        for (int i=q.order() & 1; i < q.order(); i+= 2)
            t[i/2] = q.coefficients[i];
        GaloisPolynomial qprime = new GaloisPolynomial(t);
        for (int i: pos)
        {
            int x = GaloisField4.exp(i + GaloisField4.size() - input.length);
            int y = p.eval(x);
            int z = qprime.eval(GaloisField4.mul(x, x));
            input[i] ^= GaloisField4.div(y, GaloisField4.mul(x, z));
        }
    }

    private static List<Integer> findErrors(int[] synd, final int nmess)
    {
        GaloisPolynomial errPoly = new GaloisPolynomial(new int[]{1});
        GaloisPolynomial oldPoly = new GaloisPolynomial(new int[]{1});
        for (int i=0; i < synd.length; i++)
        {
            oldPoly = oldPoly.append(0);
            int delta = synd[i];
            for (int j=1; j < errPoly.order(); j++)
                delta ^= GaloisField4.mul(errPoly.coefficients[errPoly.order() - 1 - j], synd[i - j]);
            if (delta != 0)
            {
                if (oldPoly.order() > errPoly.order())
                {
                    GaloisPolynomial newPoly = oldPoly.scale(delta);
                    oldPoly = errPoly.scale(GaloisField4.div(1, delta));
                    errPoly = newPoly;
                }
                errPoly = errPoly.add(oldPoly.scale(delta));
            }
        }
        int errs = errPoly.order()-1;
        if (2*errs > synd.length)
            throw new IllegalStateException("Too many errors to correct! ("+errs+")");
        List<Integer> errorPos = new LinkedList();
        for (int i=0; i < nmess; i++)
            if (errPoly.eval(GaloisField4.exp(GaloisField4.size() - 1 - i)) == 0)
                if (nmess -1 -i < synd.length)
                    errorPos.add(nmess - 1 - i);
        if (errorPos.size() != errs)
            throw new IllegalStateException("couldn't find error positions!");
        return errorPos;
    }

    private static int[] forneySyndromes(int[] synd, List<Integer> pos, int nmess)
    {
        int[] fsynd = Arrays.copyOf(synd, synd.length);
        for (int i: pos)
        {
            int x = GaloisField4.exp(nmess - 1 - i);
            for (int j=0; j < fsynd.length-1; j++)
                fsynd[j] = GaloisField4.mul(fsynd[j], x) ^ fsynd[j+1];
        }
        int[] t = new int[fsynd.length-1];
        System.arraycopy(fsynd, 1, t, 0, t.length);
        return t;
    }

    public static int[] decode(int[] message, int nec)
    {
        int[] out = Arrays.copyOf(message, message.length);
        List<Integer> erasedPos = new LinkedList();
        for (int i=0; i < out.length; i++)
            if (out[i] < 0) // negative symbolises missing here
            {
                out[i] = 0;
                erasedPos.add(i);
            }
        if (erasedPos.size() > nec)
            throw new IllegalStateException("Too many erasures to correct! ("+erasedPos.size()+")");
        int[] synd = syndromes(out, nec);
        int max = 0;
        for (int i: synd)
            if (i > max)
                max = i;
        if (max == 0)
            return out;
        int[] fsynd = forneySyndromes(synd, erasedPos, out.length);
        List<Integer> errPos = findErrors(fsynd, out.length);
        correctErrata(out, fsynd, errPos);
        return out;
    }

    public static class Test {
        public Test() {
        }

        @org.junit.Test
        public void errorFreeSyndrome4() {
            int nec = 2;
            int[] input = new int[] {0, 2};
            int[] encoded = GaloisPolynomial.encode(input, nec);
            int[] original = Arrays.copyOf(encoded, encoded.length);
            System.out.printf("Original:  ");
            print(original);
            int[] synd = syndromes(encoded, nec);
            System.out.printf("Syndrome:  ");
            print(synd);
            for (int i: synd)
                junit.framework.Assert.assertTrue(i == 0);
        }

        @org.junit.Test
        public void e1Syndrome4() {
            int nec = 2;
            int[] input = new int[] {0, 2};
            int[] encoded = GaloisPolynomial.encode(input, nec);
            int[] original = Arrays.copyOf(encoded, encoded.length);
            System.out.printf("Original:  ");
            print(original);
            encoded[0] ^= 1;
            int[] synd = syndromes(encoded, nec);
            System.out.printf("Syndrome:  ");
            print(synd);
            List<Integer> errPos = findErrors(synd, encoded.length);
            System.out.printf("Error Positions: ");
            for (int i : errPos)
                System.out.printf(i + " ");
            System.out.println();
            correctErrata(encoded, synd, errPos);
            System.out.printf("Corrected: ");
            print(encoded);
        }

        public void errorFreeSyndrome() {
            Random r = new Random();
            int size = 2;
            int nec = 2;
            byte[] bytes = new byte[size];
            r.nextBytes(bytes);
            int[] input = convert(bytes);
            int[] encoded = GaloisPolynomial.encode(input, nec);
            int[] original = Arrays.copyOf(encoded, encoded.length);
            System.out.printf("Original:  ");
            print(original);
            int[] synd = syndromes(encoded, nec);
            System.out.printf("Syndrome:  ");
            print(synd);
            for (int i: synd)
                junit.framework.Assert.assertTrue(i == 0);
        }

        public void test() {
            Random r = new Random();
            int size = 2;
            int nec = 2;
            byte[] bytes = new byte[size];
            r.nextBytes(bytes);
            int[] input = convert(bytes);
            int[] encoded = GaloisPolynomial.encode(input, nec);
            int[] original = Arrays.copyOf(encoded, encoded.length);
            System.out.printf("Original:  ");
            print(original);
            encoded[0] ^= 1;

            int[] synd = syndromes(encoded, nec);
            System.out.printf("Syndrome:  ");
            print(synd);
            List<Integer> errPos = findErrors(synd, encoded.length);
            System.out.printf("Error Positions: ");
            for (int i : errPos)
                System.out.printf(i + " ");
            System.out.println();
            correctErrata(encoded, synd, errPos);
            System.out.printf("Corrected: ");
            print(encoded);
        }
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
            res[i] = GaloisField4.mask() & in[i];
        return res;
    }
}
