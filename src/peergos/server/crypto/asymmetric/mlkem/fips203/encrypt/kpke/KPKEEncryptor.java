package peergos.server.crypto.asymmetric.mlkem.fips203.encrypt.kpke;

import peergos.server.crypto.asymmetric.mlkem.fips203.ParameterSet;
import peergos.server.crypto.asymmetric.mlkem.fips203.codec.Codec;
import peergos.server.crypto.asymmetric.mlkem.fips203.codec.MLKEMCodec;
import peergos.server.crypto.asymmetric.mlkem.fips203.encrypt.Encryptor;
import peergos.server.crypto.asymmetric.mlkem.fips203.hash.Hash;
import peergos.server.crypto.asymmetric.mlkem.fips203.hash.MLKEMHash;
import peergos.server.crypto.asymmetric.mlkem.fips203.sample.MLKEMSampler;
import peergos.server.crypto.asymmetric.mlkem.fips203.sample.Sampler;
import peergos.server.crypto.asymmetric.mlkem.fips203.transform.MLKEMTransformer;
import peergos.server.crypto.asymmetric.mlkem.fips203.transform.Transformer;

import java.nio.ByteBuffer;

public class KPKEEncryptor implements Encryptor {

    private final ParameterSet parameterSet;
    private final Codec codec;
    private final Hash hash;
    private final Sampler sampler;
    private final Transformer ntt;

    public KPKEEncryptor(ParameterSet parameterSet, Codec codec, Hash hash, Sampler sampler, Transformer ntt) {
        this.parameterSet = parameterSet;
        this.codec = codec;
        this.hash = hash;
        this.sampler = sampler;
        this.ntt = ntt;
    }

    public static KPKEEncryptor create(ParameterSet parameterSet) {
        return new KPKEEncryptor(
                parameterSet,
                MLKEMCodec.create(parameterSet),
                MLKEMHash.create(parameterSet),
                MLKEMSampler.create(parameterSet),
                MLKEMTransformer.create(parameterSet)
        );
    }

    /**
     * Implements Algorithm 14  (K-PKE.Encrypt) of the FIPS203 Standard.
     * @param ekPKE An array of {@code 384*k+32} bytes representing the encryption key.
     * @param message An array of 32 bytes representing the message to encrypt.
     * @param random An array of 32 bytes representing the entropy into the system.
     * @return A {@code 32(du*k + dv)} byte array representing the cipherText.
     */
    @Override
    public byte[] encrypt(byte[] ekPKE, byte[] message, byte[] random) {

        int n = 0;

        // Create a byte buffer to wrap the passed in ekPKE
        ByteBuffer ekPKEBuffer = ByteBuffer.wrap(ekPKE);

        // Allocate tHat
        int[][] tHat = new int[parameterSet.getK()][];

        // Iterate over the 384-byte chunks of tHat and perform a byte decode on each chunk
        // When this operation is complete there will be 32-bytes remaining in the buffer
        // which are the seed rho.
        for (int i = 0; i < parameterSet.getK(); i++) {

            // Split off a 384-byte chunk of ekPKE
            byte[] ekPKEChunk = new byte[384];
            ekPKEBuffer.get(ekPKEChunk);

            // Fill tHat
            tHat[i] = codec.byteDecode(12, ekPKEChunk);

        }

        // Split off rho
        byte[] rho = new byte[32];
        ekPKEBuffer.get(rho);

        // Regenerate aHatMatrix
        int[][][] aHatMatrix = new int[parameterSet.getK()][parameterSet.getK()][];
        for (int i = 0; i < parameterSet.getK(); i++) {
            for (int j = 0; j < parameterSet.getK(); j++) {
                aHatMatrix[i][j] = sampler.sampleNTT(rho, (byte) j, (byte) i);
            }
        }

        // Generate y
        int[][] y = new int[parameterSet.getK()][];
        for (int i = 0; i < parameterSet.getK(); i++) {
            y[i] = sampler.samplePolyCBDEta1(hash.prfEta1(random, (byte) n));
            n++;
        }

        // Generate e1
        int[][] e1 = new int[parameterSet.getK()][];
        for (int i = 0; i < parameterSet.getK(); i++) {
            e1[i] = sampler.samplePolyCBDEta2(hash.prfEta2(random, (byte) n));
            n++;
        }

        // Sample e2
        int[] e2 = sampler.samplePolyCBDEta2(hash.prfEta2(random, (byte) n));

        // Generate yHat
        int[][] yHat = new int[parameterSet.getK()][];
        for (int i = 0; i < parameterSet.getK(); i++) {
            yHat[i] = ntt.transform(y[i]);
        }

        // Generate u
        int[][] u = new int[parameterSet.getK()][];
        int[][] matrixOp = ntt.matrixMultiply(ntt.matrixTranspose(aHatMatrix), yHat);
        for (int i = 0; i < parameterSet.getK(); i++) {
            u[i] = ntt.inverse(matrixOp[i]);
        }
        u = ntt.matrixAdd(u, e1);

        // Generate mu
        int[] decodedMessage = codec.byteDecode(1, message);
        int[] mu = codec.decompress(1, decodedMessage);

        // Generate v
        int[] v = ntt.arrayAdd(ntt.arrayAdd(ntt.inverse(ntt.vectorTransposeMultiply(tHat, yHat)), e2), mu);

        // Generate result
        int resultLength = 32 * (parameterSet.getDu() * parameterSet.getK() + parameterSet.getDv());
        ByteBuffer resultBuffer = ByteBuffer.allocate(resultLength);

        for (int[] ints : u) {
            resultBuffer.put(codec.byteEncode(parameterSet.getDu(), codec.compress(parameterSet.getDu(), ints)));
        }
        resultBuffer.put(codec.byteEncode(parameterSet.getDv(), codec.compress(parameterSet.getDv(), v)));

        return resultBuffer.array().clone();

    }

}
