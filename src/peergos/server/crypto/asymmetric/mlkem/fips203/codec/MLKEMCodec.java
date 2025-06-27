package peergos.server.crypto.asymmetric.mlkem.fips203.codec;

import peergos.server.crypto.asymmetric.mlkem.CryptoUtils;
import peergos.server.crypto.asymmetric.mlkem.fips203.ParameterSet;

import java.util.BitSet;

import static peergos.server.crypto.asymmetric.mlkem.CryptoUtils.mod;
import static peergos.server.crypto.asymmetric.mlkem.CryptoUtils.pow;

public class MLKEMCodec implements Codec {

    private final ParameterSet parameterSet;

    public MLKEMCodec(ParameterSet parameterSet) {
        this.parameterSet = parameterSet;
    }

    public static MLKEMCodec create(ParameterSet parameterSet) {
        return new MLKEMCodec(parameterSet);
    }


    /**
     * Encodes the coefficients of a 256-term polynomial (a module lane) into a packed set of bytes where each
     * value takes up {@code d} bits that may not align to a byte boundary.
     * Note that while the algorithm accepts any value {@code d} from 1 to 12, it is realistically only used
     * for the values 1, 12 and the Du and Dv values from the given parameter set.
     *
     * @param d An {@code int} representing the number of digits to encode.
     * @param f An {@code int} array representing the coefficients of a polynomial (modulo q) in a lane.
     * @return A {@code byte} array composed of packed polynomial coefficients.
     */
    @Override
    public byte[] byteEncode(int d, int[] f) {

        // Declare bitset
        int bitCapacity = 256 * d;
        BitSet b = new BitSet(bitCapacity);

        // Iterate over the input array
        for (int i = 0; i < 256; i++) {

            // Extract a single integer (modulo m) -> Assumes big endian bit order and 32-bit ints
            int a = f[i] & CryptoUtils.INT_BIT_MASKS[d];

            // Iterate over the bits in the integer
            for (int j = 0; j < d; j++) {

                // Calculate the bit index for the operation
                int bitIndex = i * d + j;

                // Set the bit at the calculated bit index to a mod 2 which is the least significant bit of a
                b.set(bitIndex, (a & CryptoUtils.INT_BIT_MASKS[1]) != 0);

                // Update a
                a = (a - (b.get(bitIndex) ? 1 : 0))/2;

            }

        }

        // Convert the bitset to a byte array
        byte[] result = new byte[bitCapacity/8];
        byte[] bitsAsBytes = b.toByteArray();
        System.arraycopy(bitsAsBytes, 0, result, 0, bitsAsBytes.length);
        return result;

    }

    @Override
    public int[] compress(int d, int[] x) {

        // return ((x * d.Exp2() + (_param.Q / 2)) / _param.Q);

        int q = parameterSet.getQ();
        int[] result = new int[x.length];
        for (int i = 0; i < x.length; i++) {
            result[i] = ((x[i] * pow(2, d)) + (q >> 1)) / q;
        }

        return result;
    }

    @Override
    public int[] byteDecode(int d, byte[] f) {

        BitSet bits = BitSet.valueOf(f);
        int[] result = new int[256];
        int dPow = pow(2, d);
        int q = parameterSet.getQ();
        int m = (d == 12) ? q : dPow;

        for (int i = 0; i < 256; i++) {
            for (int j = 0; j < d; j++) {
                int jPow = pow(2, j);
                result[i] = mod(result[i] + (bits.get(i * d + j) ? jPow : 0), m);
            }
        }

        return result;
    }

    @Override
    public int[] decompress(int d, int[] y) {

        int[] result = new int[y.length];

        int q = parameterSet.getQ();
        int d2 = pow(2, d);
        int d2Half = pow(2, d) >>> 1;

        for (int i = 0; i < y.length; i++) {
            result[i] = (y[i] * q + d2Half) / d2;
        }

        return result;
    }
}
