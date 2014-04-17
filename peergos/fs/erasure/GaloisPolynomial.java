package peergos.fs.erasure;

import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

public class GaloisPolynomial
{
    private final int[] coefficients;

    private GaloisPolynomial(int[] coefficients)
    {
        if (coefficients.length > GaloisField.SIZE)
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
            y = GaloisField.mul(y, x) ^ coefficients[i];
        return y;
    }

    private GaloisPolynomial scale(int x)
    {
        int[] res = new int[coefficients.length];
        for (int i=0; i < res.length; i++)
            res[i] = GaloisField.mul(x, coefficients[i]);
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
                res[i+j] ^= GaloisField.mul(coefficients[i], other.coefficients[j]);
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
            g = g.mul(new GaloisPolynomial(new int[]{1, GaloisField.exp(i)}));
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
                    res[i+j] ^= GaloisField.mul(gen.coefficients[j], c);
        }
        System.arraycopy(input, 0, res, 0, input.length);
        return res;
    }

    private static int[] syndrome(int[] input, int nEC)
    {
        int[] res = new int[nEC];
        GaloisPolynomial poly = new GaloisPolynomial(input);
        for (int i=0; i < nEC; i++)
            res[i] = poly.eval(GaloisField.exp(i));
        return res;
    }

    private static void correctErrata(int[] input, int[] synd, List<Integer> pos)
    {
        GaloisPolynomial q = new GaloisPolynomial(new int[]{1});
        for (int i: pos)
        {
            int x = GaloisField.exp(input.length-1-i);
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
        for (int i=q.order() &1; i < q.order(); i+= 2)
            t[i/2] = q.coefficients[i];
        GaloisPolynomial qprime = new GaloisPolynomial(t);
        for (int i: pos)
        {
            int x = GaloisField.exp(i +256 - input.length);
            int y = p.eval(x);
            int z = qprime.eval(GaloisField.mul(x, x));
            input[i] ^= GaloisField.div(y, GaloisField.mul(x, z));
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
                delta ^= GaloisField.mul(errPoly.coefficients[errPoly.order()-1-j], synd[i-j]);
            if (delta != 0)
            {
                if (oldPoly.order() > errPoly.order())
                {
                    GaloisPolynomial newPoly = oldPoly.scale(delta);
                    oldPoly = errPoly.scale(GaloisField.div(1, delta));
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
            if (errPoly.eval(GaloisField.exp(255 - i)) == 0)
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
            int x = GaloisField.exp(nmess-1-i);
            for (int j=0; j < fsynd.length-1; j++)
                fsynd[j] = GaloisField.mul(fsynd[j], x) ^ fsynd[j+1];
        }
        int[] t = new int[fsynd.length-1];
        System.arraycopy(fsynd, 1, t, 0, t.length);
        return t;
    }

    public static int[] decode(int[] message, int ec)
    {
        int[] out = Arrays.copyOf(message, message.length);
        List<Integer> erasedPos = new LinkedList();
        for (int i=0; i < out.length; i++)
            if (out[i] < 0) // negative symbolises missing here
            {
                out[i] = 0;
                erasedPos.add(i);
            }
        if (erasedPos.size() > ec)
            throw new IllegalStateException("Too many erasures to correct! ("+erasedPos.size()+")");
        int[] synd = syndrome(out, ec);
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
}
