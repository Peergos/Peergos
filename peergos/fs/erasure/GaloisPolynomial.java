package peergos.fs.erasure;

import java.util.*;

public class GaloisPolynomial
{
    private final int[] coefficients;
    private final GaloisField f;

    private GaloisPolynomial(int[] coefficients, GaloisField f)
    {
        if (coefficients.length > f.size())
            throw new IllegalStateException("Polynomial order must be less than or equal to the degree of the Galois field.");
        this.coefficients = coefficients;
        this.f = f;
    }

    private int order()
    {
        return coefficients.length;
    }

    private int eval(int x)
    {
        int y = coefficients[0];
        for (int i=1; i < coefficients.length; i++)
            y = f.mul(y, x) ^ coefficients[i];
        return y;
    }

    private GaloisPolynomial scale(int x)
    {
        int[] res = new int[coefficients.length];
        for (int i=0; i < res.length; i++)
            res[i] = f.mul(x, coefficients[i]);
        return new GaloisPolynomial(res, f);
    }

    private GaloisPolynomial add(GaloisPolynomial other)
    {
        int[] res = new int[Math.max(order(), other.order())];
        for (int i=0; i < order(); i++)
            res[i + res.length - order()] = coefficients[i];
        for (int i=0; i < other.order(); i++)
            res[i + res.length - other.order()] ^= other.coefficients[i];
        return new GaloisPolynomial(res, f);
    }

    private GaloisPolynomial mul(GaloisPolynomial other)
    {
        int[] res = new int[order() + other.order() - 1];
        for (int i=0; i < order(); i++)
            for (int j=0; j < other.order(); j++)
                res[i+j] ^= f.mul(coefficients[i], other.coefficients[j]);
        return new GaloisPolynomial(res, f);
    }

    private GaloisPolynomial append(int x)
    {
        int[] res = new int[coefficients.length+1];
        System.arraycopy(coefficients, 0, res, 0, coefficients.length);
        res[res.length-1] = x;
        return new GaloisPolynomial(res, f);
    }

    private static GaloisPolynomial generator(int nECSymbols, GaloisField f)
    {
        GaloisPolynomial g = new GaloisPolynomial(new int[] {1}, f);
        for (int i=0; i < nECSymbols; i++)
            g = g.mul(new GaloisPolynomial(new int[]{1, f.exp(i)}, f));
        return g;
    }

    public static int[] encode(int[] input, int nEC, GaloisField f)
    {
        GaloisPolynomial gen = generator(nEC, f);
        int[] res = new int[input.length + nEC];
        System.arraycopy(input, 0, res, 0, input.length);
        for (int i=0; i < input.length; i++)
        {
            int c = res[i];
            if (c != 0)
                for (int j=0; j < gen.order(); j++)
                    res[i+j] ^= f.mul(gen.coefficients[j], c);
        }
        System.arraycopy(input, 0, res, 0, input.length);
        return res;
    }

    private static int[] syndromes(int[] input, int nEC, GaloisField f)
    {
        int[] res = new int[nEC];
        GaloisPolynomial poly = new GaloisPolynomial(input, f);
        for (int i=0; i < nEC; i++)
            res[i] = poly.eval(f.exp(i));
        return res;
    }

    private static void correctErrata(int[] input, int[] synd, List<Integer> pos, GaloisField f)
    {
        if (pos.size() == 0)
            return;
        GaloisPolynomial q = new GaloisPolynomial(new int[]{1}, f);
        for (int i: pos)
        {
            int x = f.exp(input.length - 1 - i);
            q = q.mul(new GaloisPolynomial(new int[]{x, 1}, f));
        }
        int[] t = new int[pos.size()];
        for (int i=0; i < t.length; i++)
            t[i] = synd[t.length-1-i];
        GaloisPolynomial p = new GaloisPolynomial(t, f).mul(q);
        t = new int[pos.size()];
        System.arraycopy(p.coefficients, p.order()-t.length, t, 0, t.length);
        p = new GaloisPolynomial(t, f);
        t = new int[(q.order()- (q.order() & 1))/2];
        for (int i=q.order() & 1; i < q.order(); i+= 2)
            t[i/2] = q.coefficients[i];
        GaloisPolynomial qprime = new GaloisPolynomial(t,f);
        for (int i: pos)
        {
            int x = f.exp(i + f.size() - input.length);
            int y = p.eval(x);
            int z = qprime.eval(f.mul(x, x));
            input[i] ^= f.div(y, f.mul(x, z));
        }
    }

    private static List<Integer> findErrors(int[] synd, final int nmess, GaloisField f)
    {
        GaloisPolynomial errPoly = new GaloisPolynomial(new int[]{1}, f);
        GaloisPolynomial oldPoly = new GaloisPolynomial(new int[]{1}, f);
        for (int i=0; i < synd.length; i++)
        {
            oldPoly = oldPoly.append(0);
            int delta = synd[i];
            for (int j=1; j < errPoly.order(); j++)
                delta ^= f.mul(errPoly.coefficients[errPoly.order() - 1 - j], synd[i - j]);
            if (delta != 0)
            {
                if (oldPoly.order() > errPoly.order())
                {
                    GaloisPolynomial newPoly = oldPoly.scale(delta);
                    oldPoly = errPoly.scale(f.div(1, delta));
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
            if (errPoly.eval(f.exp(f.size() - 1 - i)) == 0)
                    errorPos.add(nmess - 1 - i);
        if (errorPos.size() != errs)
            throw new IllegalStateException("couldn't find error positions! ("+errorPos.size()+"!="+errs+")");
        return errorPos;
    }

    private static int[] forneySyndromes(int[] synd, List<Integer> pos, int nmess, GaloisField f)
    {
        int[] fsynd = Arrays.copyOf(synd, synd.length);
        for (int i: pos)
        {
            int x = f.exp(nmess - 1 - i);
            for (int j=0; j < fsynd.length-1; j++)
                fsynd[j] = f.mul(fsynd[j], x) ^ fsynd[j+1];
        }
        int[] t = new int[fsynd.length-1];
        System.arraycopy(fsynd, 1, t, 0, t.length);
        return t;
    }

    public static int[] decode(int[] message, int nec, GaloisField f)
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
        int[] synd = syndromes(out, nec, f);
        int max = 0;
        for (int i: synd)
            if (i > max)
                max = i;
        if (max == 0)
            return out;
        int[] fsynd = forneySyndromes(synd, erasedPos, out.length, f);
        List<Integer> errPos = findErrors(fsynd, out.length, f);
        correctErrata(out, fsynd, errPos, f);
        return out;
    }

    public static class Test {
        boolean print = false;
        public Test() {
        }

        @org.junit.Test
        public void run()
        {
//            GaloisField f =  new GaloisField1024();
            long t1 = System.nanoTime();
            GaloisField f = new GaloisField65536();
            long t2 = System.nanoTime();
            System.out.printf("Constructing field took %d mS\n", (t2-t1)/1000000);
            errorFreeSyndrome(f);
            long t3 = System.nanoTime();
            System.out.printf("Error free syndrome took %d mS\n", (t3-t2)/1000000);
            singleError(f);
            long t4 = System.nanoTime();
            System.out.printf("Single error took %d mS\n", (t4-t3)/1000000);
            manyErrors(f);
            long t5 = System.nanoTime();
            System.out.printf("Single error took %d mS\n", (t5-t4)/1000000);
        }

        public void errorFreeSyndrome(GaloisField f) {
            Random r = new Random();
            int size = (int)(f.size() * 0.6);
            int nec = (int)(f.size() * 0.4);
            byte[] bytes = new byte[size];
            r.nextBytes(bytes);
            int[] input = convert(bytes, f);
            int[] encoded = GaloisPolynomial.encode(input, nec, f);
            int[] original = Arrays.copyOf(encoded, encoded.length);
            if (print) {
                System.out.printf("Original:  ");
                print(original);
            }
            int[] synd = syndromes(encoded, nec, f);
            if (print) {
                System.out.printf("Syndrome:  ");
                print(synd);
            }
            assert (Arrays.equals(synd, new int[synd.length]));
        }

        public void singleError(GaloisField f) {
            Random r = new Random();
            int size = (int)(f.size() * 0.6);
            int nec = (int)(f.size() * 0.4);
            byte[] bytes = new byte[size];
            r.nextBytes(bytes);
            int[] input = convert(bytes, f);
            int[] encoded = GaloisPolynomial.encode(input, nec, f);
            int[] original = Arrays.copyOf(encoded, encoded.length);
            if (print) {
                System.out.printf("Original:  ");
                print(original);
            }
            encoded[0] ^= 1;

            int[] synd = syndromes(encoded, nec, f);
            if (print) {
                System.out.printf("Syndrome:  ");
                print(synd);
            }
            List<Integer> errPos = findErrors(synd, encoded.length, f);
            if (print) {
                System.out.printf("Error Positions: ");
                for (int i : errPos)
                    System.out.printf(i + " ");
                System.out.println();
            }
            correctErrata(encoded, synd, errPos, f);
            if (print) {
                System.out.printf("Corrected: ");
                print(encoded);
            }
            assert (Arrays.equals(encoded, original));
        }

        public void manyErrors(GaloisField f) {
            Random r = new Random();
            int size = (int) (f.size() * 0.6);
            int nec = (int) (f.size() * 0.4);
            byte[] bytes = new byte[size];
            r.nextBytes(bytes);
            int[] input = convert(bytes, f);
            int[] encoded = GaloisPolynomial.encode(input, nec, f);
            int[] original = Arrays.copyOf(encoded, encoded.length);
            if (print) {
                System.out.printf("Original:  ");
                print(original);
                System.out.printf("Inserted errors at: ");
            }
            List<Integer> epositions = new ArrayList();
            for (int i=0; i < nec/2-1; i++) {
                int index = r.nextInt(encoded.length);
                epositions.add(index);
                encoded[index] ^= 1;
            }
            if (print) {
                Collections.sort(epositions);
                for (Integer i : epositions)
                    System.out.printf(i + " ");
                System.out.println();
            }

            int[] synd = syndromes(encoded, nec, f);
            if (print) {
                System.out.printf("Syndrome:  ");
                print(synd);
            }
            List<Integer> errPos = findErrors(synd, encoded.length, f);
            if (print) {
                Collections.sort(errPos);
                System.out.printf("Found Error Positions: ");
                for (int i : errPos)
                    System.out.printf(i + " ");
                System.out.println();
            }
            correctErrata(encoded, synd, errPos, f);
            if (print) {
                System.out.printf("Corrected: ");
                print(encoded);
            }
            assert (Arrays.equals(encoded, original));
        }
    }

    public static void print(int[] d)
    {
        for (int i: d)
            System.out.printf("%02x ", i);
        System.out.println();
    }

    public static int[] convert(byte[] in, GaloisField f)
    {
        int[] res = new int[in.length];
        for (int i=0; i < in.length; i++)
            res[i] = f.mask() & in[i];
        return res;
    }
}
