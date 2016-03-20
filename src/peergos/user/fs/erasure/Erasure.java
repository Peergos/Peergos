package peergos.user.fs.erasure;

import java.io.ByteArrayOutputStream;
import java.util.*;

public class Erasure {

    public static byte[][] split(byte[] input, int originalBlobs, int allowedFailures)
    {
        return split(input, new GaloisField256(), originalBlobs, allowedFailures);
    }

    public static byte[][] split(byte[] input, GaloisField f, int originalBlobs, int allowedFailures)
    {
        long t1 = System.currentTimeMillis();
        int[] ints = convert(input, f);

        int n = originalBlobs + allowedFailures*2;
        ByteArrayOutputStream[] bouts = new ByteArrayOutputStream[n];
        for (int i=0; i < bouts.length; i++)
            bouts[i] = new ByteArrayOutputStream();
        int encodeSize = (f.size()/n)*n;
        int inputSize = encodeSize*originalBlobs/n;
        int nec = encodeSize-inputSize;
        int symbolSize = inputSize/originalBlobs;
        if (symbolSize * originalBlobs != inputSize)
            throw new IllegalStateException(String.format("Bad alignment of bytes in chunking. %d != %d * %d", inputSize, symbolSize, originalBlobs));

        for (int i=0; i < ints.length; i+=inputSize)
        {
            int[] copy = Arrays.copyOfRange(ints, i, i+inputSize);
            byte[] encoded = convert(GaloisPolynomial.encode(copy, nec, f), f);
            for (int j=0; j < n; j++)
            {
                bouts[j].write(encoded, j*symbolSize, symbolSize);
            }
        }

        byte[][] res = new byte[n][];
        for (int i=0; i < n; i++)
            res[i] = bouts[i].toByteArray();
        long t2 = System.currentTimeMillis();
        System.out.println("Erasure encoding took "+(t2-t1)+ " mS");
        return res;
    }

    public static byte[] recombine(byte[][] encoded, int truncateTo, int originalBlobs, int allowedFailures)
    {
        return recombine(new GaloisField256(), encoded, truncateTo, originalBlobs, allowedFailures);
    }

    public static byte[] recombine(List<byte[]> encoded, int truncateTo, int originalBlobs, int allowedFailures)
    {
        return recombine(new GaloisField256(), encoded.toArray(new byte[0][]), truncateTo, originalBlobs, allowedFailures);
    }

    public static byte[] recombine(GaloisField f, byte[][] encoded, int truncateTo, int originalBlobs, int allowedFailures)
    {
        long t1 = System.currentTimeMillis();
        int n = originalBlobs + allowedFailures*2;
        int encodeSize = (f.size()/n)*n;
        int inputSize = encodeSize*originalBlobs/n;
        int nec = encodeSize-inputSize;
        int symbolSize = inputSize/originalBlobs;
        int tbSize = encoded[0].length;

        ByteArrayOutputStream res = new ByteArrayOutputStream();
        for (int i=0; i < tbSize; i+=symbolSize)
        {
            ByteArrayOutputStream bout = new ByteArrayOutputStream();
            // take a symbol from each stream
            for (int j=0; j < n; j++)
                bout.write(encoded[j], i, symbolSize);
            int[] decodedInts = GaloisPolynomial.decode(convert(bout.toByteArray(), f), nec, f);
            byte[] raw = convert(decodedInts, f);
            res.write(raw, 0, inputSize);
        }
        long t2 = System.currentTimeMillis();
        System.out.println("Erasure decoding took "+(t2-t1) + " mS");
        return Arrays.copyOfRange(res.toByteArray(), 0, truncateTo);
    }

    public static int[] convert(byte[] in, GaloisField f)
    {
        if (f.size() >= 256) {
            int[] res = new int[in.length];
            for (int i = 0; i < in.length; i++)
                res[i] = f.mask() & in[i];
            return res;
        }
        if (f.size() == 16)
        {
            int[] res = new int[in.length*2];
            for (int i = 0; i < in.length; i++) {
                res[2*i] = f.mask() & in[i];
                res[2*i+1] = f.mask() & (in[i] >> 4);
            }
            return res;
        }
        throw new IllegalStateException("Unimplemented GaloisField size conversion");
    }

    public static byte[] convert(int[] in, GaloisField f)
    {
        if (f.size() >= 256) {
            byte[] res = new byte[in.length];
            for (int i = 0; i < in.length; i++)
                res[i] = (byte) in[i];
            return res;
        }
        if (f.size() == 16)
        {
            byte[] res = new byte[in.length/2];
            for (int i = 0; i < res.length; i++)
                res[i] = (byte) (in[2*i] | (in[2*i+1] << 4));
            return res;
        }
        throw new IllegalStateException("Unimplemented GaloisField size conversion");
    }
}
