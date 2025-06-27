package peergos.server.crypto.asymmetric.mlkem.fips203.sample;

import peergos.server.crypto.asymmetric.mlkem.fips202.keccak.core.KeccakSponge;
import peergos.server.crypto.asymmetric.mlkem.fips202.keccak.io.BitInputStream;
import peergos.server.crypto.asymmetric.mlkem.fips202.keccak.io.BitOutputStream;
import peergos.server.crypto.asymmetric.mlkem.fips203.ParameterSet;
import peergos.server.crypto.asymmetric.mlkem.fips203.hash.XOFParameterSet;
import peergos.server.crypto.asymmetric.mlkem.fips203.key.gen.KeyPairGenerationException;

import java.util.BitSet;

import static peergos.server.crypto.asymmetric.mlkem.CryptoUtils.mod;

public class MLKEMSampler implements Sampler {

    private final ParameterSet parameterSet;

    public MLKEMSampler(ParameterSet parameterSet) {
        this.parameterSet = parameterSet;
    }

    public static MLKEMSampler create(ParameterSet parameterSet) {
        return new MLKEMSampler(parameterSet);
    }

    @Override
    public int[] sampleNTT(byte[] seed, byte a, byte b) {

        // Setup internal context vars
        int j = 0;
        int[] aHat = new int[256];
        byte[] sample = new byte[3];

        // Init context for XOF
        KeccakSponge xof = new KeccakSponge(XOFParameterSet.SHAKE128);
        BitOutputStream absorbStream = xof.getAbsorbStream();
        BitInputStream squeezeStream = xof.getSqueezeStream();

        // Absorb the seed rho, and the indices i and j that have been appended as bytes
        absorbStream.write(seed);
        absorbStream.write(new byte[] {a, b});

        while (j < 256) {

            if (squeezeStream.read(sample) != 3) {
                throw new KeyPairGenerationException("Unable to squeeze 3 bytes of data");
            }

            // Java doesn't have unsigned bytes, but this algorithm treats sampled bytes as if they are integers
            // which leads to strange behavior with a signed byte type.  So we extract the sample values and convert
            // them to unsigned integers.
            int c0 = Byte.toUnsignedInt(sample[0]);
            int c1 = Byte.toUnsignedInt(sample[1]);
            int c2 = Byte.toUnsignedInt(sample[2]);

            int d1 = c0 + 256 * (c1 % 16);
            int d2 = (c1 / 16) + (16 * c2);

            if (d1 < parameterSet.getQ()) {
                aHat[j] = d1;
                j++;
            }

            if (d2 < parameterSet.getQ() && j < 256) {
                aHat[j] = d2;
                j++;
            }
        }

        return aHat;

    }

    private int[] samplePolyCBD(int eta, byte[] input) {

        // Validate input length
        if (input == null || input.length != 64*eta) {
            throw new KeyPairGenerationException(String.format("PolyCBD sample input must be %d bytes", 64*eta));
        }

        // Declare result array
        int[] result = new int[256];

        BitSet b = BitSet.valueOf(input);
        for (int i = 0; i < 256; i++) {

            // Calculate X
            int x = 0;
            for (int j = 0; j < eta; j++) {
                x += b.get(2*i*eta + j) ? 1 : 0;
            }

            // Calculate Y
            int y = 0;
            for (int j = 0; j < eta; j++) {
                y += b.get(2*i*eta + eta + j) ? 1 : 0;
            }

            result[i] = mod(x - y, parameterSet.getQ());
        }

        return result;

    }

    @Override
    public int[] samplePolyCBDEta1(byte[] input) {

        return samplePolyCBD(parameterSet.getEta1(), input);

    }

    @Override
    public int[] samplePolyCBDEta2(byte[] input) {

        return samplePolyCBD(parameterSet.getEta2(), input);

    }
}
