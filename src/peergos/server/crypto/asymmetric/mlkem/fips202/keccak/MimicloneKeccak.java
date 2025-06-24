package peergos.server.crypto.asymmetric.mlkem.fips202.keccak;

import java.util.BitSet;

/**
 * There are 7 permutation functions in the Keccak family.
 * <br/>
 * They are:
 * <ul>
 * <li>Keccak-f[25] (b=25, l=0, w=1)</li>
 * <li>Keccak-f[50] (b=50, l=1, w=2)</li>
 * <li>Keccak-f[100] (b=100, l=2, w=4)</li>
 * <li>Keccak-f[200] (b=200, l=3, w=8)</li>
 * <li>Keccak-f[400] (b=400, l=4, w=16)</li>
 * <li>Keccak-f[800] (b=800, l=5, w=32)</li>
 * <li>Keccak-f[1600] (b=1600, l=6, w=64)</li>
 * </ul>
 *
 */
public class MimicloneKeccak {

    public enum Permutation {
        KECCAK_F25,
        KECCAK_F50,
        KECCAK_F100,
        KECCAK_F200,
        KECCAK_F400,
        KECCAK_F800,
        KECCAK_F1600;

        private final int b, l, w, n;

        private Permutation() {
            l = ordinal();
            w = 2^l;
            b = 25 * w;
            n = 12 + 2*l;
        }
    }

    private static final long[] ROUND_CONSTANTS = {
            0x0000000000000001L, // RC[0]
            0x0000000000008082L, // RC[1]
            0x800000000000808AL, // RC[2]
            0x8000000080008000L, // RC[3]
            0x000000000000808BL, // RC[4]
            0x0000000080000001L, // RC[5]
            0x8000000080008081L, // RC[6]
            0x8000000000008009L, // RC[7]
            0x000000000000008AL, // RC[8]
            0x0000000000000088L, // RC[9]
            0x0000000080008009L, // RC[10]
            0x000000008000000AL, // RC[11]
            0x000000008000808BL, // RC[12]
            0x800000000000008BL, // RC[13]
            0x8000000000008089L, // RC[14]
            0x8000000000008003L, // RC[15]
            0x8000000000008002L, // RC[16]
            0x8000000000000080L, // RC[17]
            0x000000000000800AL, // RC[18]
            0x800000008000000AL, // RC[19]
            0x8000000080008081L, // RC[20]
            0x8000000000008080L, // RC[21]
            0x0000000080000001L, // RC[22]
            0x8000000080008008L, // RC[23]
    };

    private static final int[][] ROTATION_OFFSETS = {
            {0, 36, 3, 41, 18},
            {1, 44, 10, 45, 2},
            {62, 6, 43, 15, 61},
            {28, 55, 25, 21, 56},
            {27, 20, 39, 8, 14}
    };

    private final Permutation permutation;

    public MimicloneKeccak(Permutation permutation) {
        this.permutation = permutation;
    }

    int mod(int val, int base) {
        return (val % base + base) % base;
    }

    long convert(BitSet bits) {
        long value = 0L;
        for (int i = 0; i < bits.length(); ++i) {
            value += bits.get(i) ? (1L << i) : 0L;
        }
        return value;
    }

    public BitSet[][] permute(BitSet[][] a) {

        // At the moment we are expecting the state space to be passed in directly, which is not how FIPS202 works

        long[][] inputData = new long[5][5];
        for (int i = 0; i < 5; i++) {
            for (int j = 0; j < 5; j++) {
                inputData[i][j] = convert(a[i][j]);
            }
        }

        System.out.println("INPUT DATA");
        for (int i = 0; i < 5; i++) {
            StringBuilder builder = new StringBuilder();
            for (int j = 0; j < 5; j++) {
                builder.append(String.format("%016X ", inputData[j][i]));
            }
            System.out.println(builder.toString());
        }

        // Execute the rounds
        for (int i = 0; i < permutation.n; i++) {
            inputData = round(inputData, ROUND_CONSTANTS[i]);
        }

        // Convert result to array of BitSet
        BitSet[][] outputData = new BitSet[5][5];
        for (int i = 0; i < 5; i++) {
            for (int j = 0; j < 5; j++) {
                outputData[i][j] = BitSet.valueOf(new long[]{inputData[i][j]});
            }
        }

        return outputData;
    }

    private void printState(String label, long[][] data) {
        System.out.printf("After %s%n", label);
        for (int y = 0; y < 5; y++) {
            StringBuilder builder = new StringBuilder();
            for (int x = 0; x < 5; x++) {
                builder.append(String.format(" %016X", data[x][y]));
            }
            System.out.println(builder.toString());
        }
        System.out.println();
    }

    /**
     * Implements Algorithm 1 (Theta Step) of the NIST FIPS 202 Standard.
     * This version ignores the bit size as we are only implementing Keccak-f[1600] in support
     * of the SHAKE256 algorithm needed as an XOF for FIPS 203.  We use {@code long} as the
     * carrier for bit information since in Java this will always be a 64-bit type.
     *
     * @param state A 5x5 array of {@code long} values treated as 64 indexed bits.
     * @return A reference to the modified state space after XORing with the parities of two columns.
     */
    long[][] theta(long[][] state) {

        // Allocate result state
        long[][] statePrime = new long[5][5];

        // Step 1
        // First we iterate through all the sheets (2d xz arrays) and XOR together all 5 lanes
        // The intermediate result C is an array of length 5 that has collapsed each sheet into a single lane
        long[] c = new long[5];
        for (int x = 0; x < 5; x++) {
            c[x] = state[x][0] ^ state[x][1] ^ state[x][2] ^ state[x][3] ^ state[x][4];
        }

        // Step 2
        // Next we iterate across the 5 intermediate C lanes and XOR together the lanes to their left and right
        // (with the right lane bit shifted along the z-axis) to produce a new set of 5 intermediate D lanes
        // In this case the {@code rotateLeft} is the same thing as taking {@code z-1 mod w} as z index if
        // c were composed of indexed bits rather than a long.
        long[] d = new long[5];
        for (int x = 0; x < 5; x++) {
            d[x] = c[mod(x-1, 5)] ^ Long.rotateLeft(c[mod(x+1, 5)], 1);
        }

        // Step 3
        // XOR each together each lane in a sheet of the state space with each lane of D.  The resulting
        // state space is referred to as A prime but we do not allocate new memory for it for efficiency.
        for (int x = 0; x < 5; x++) {
            for (int y = 0; y < 5; y++) {
                statePrime[x][y] = state[x][y] ^ d[x];
            }
        }

        // Return the new state
        return statePrime;

    }

    /**
     * Implements Algorithm 2 (Rho Step) of the NIST FIPS 202 Standard.
     * This version ignores the bit size as we are only implementing Keccak-f[1600] in support
     * of the SHAKE256 algorithm needed as an XOF for FIPS 203.  We use {@code long} as the
     * carrier for bit information since in Java this will always be a 64-bit type.
     *
     * Rho takes every lane except for the notionally central lane at (0,0) and shifts the
     * bits by a pre-determined amount specified in the standard.
     *
     * Pi re-organized the lanes by rotating them around the central lane with some variation.
     *
     * These two steps are combined for efficiency.  The bit shift is pre-calculated for each
     * lane and assignments to the modified state space perform the rotation at the same time.
     *
     * @param state
     * @return
     */
    long[][] rhopi(long[][] state) {

        // Allocate prime state
        long[][] statePrime = new long[5][5];

        // Step 1
        // Copy the (0,0) lane of the original state into the prime state
        statePrime[0][0] = state[0][0]; // Copy by value because this is a long

        // Rotate the bits in each lane corresponding to pre-calculated values in the spec.
        for (int x = 0; x < 5; x++) {
            for (int y = 0; y < 5; y++) {
                statePrime[y][mod(2*x + 3*y, 5)]
                        = Long.rotateLeft(state[x][y], ROTATION_OFFSETS[x][y]);
            }
        }

        return statePrime;
    }

    long[][] chi(long[][] state) {
        long[][] statePrime = new long[5][5];

        for (int x = 0; x < 5; x++) {
            for (int y = 0; y < 5; y++) {
                statePrime[x][y] = state[x][y] ^ ((~state[mod(x+1, 5)][y]) & state[mod(x+2, 5)][y]);
            }
        }

        return statePrime;
    }

    public long[][] round(long[][] a, long rc) {

        // STEP 1 (Theta)
        a = theta(a);

        // STEP 2/3 (Rho / Pi)
        long[][] b = rhopi(a);

        // STEP 4 (Chi)
        long[][] c = chi(b);

        // STEP 5 (Iota)
        a[0][0] = c[0][0] ^ rc;

        return a;

    }

    String sponge(String message, long digestLength) {
        return "";
    }

}
