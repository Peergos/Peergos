package peergos.server.crypto.asymmetric.mlkem.fips203.transform;

import peergos.server.crypto.asymmetric.mlkem.fips203.ParameterSet;
import peergos.server.crypto.asymmetric.mlkem.fips203.reduce.Reducer;
import peergos.server.crypto.asymmetric.mlkem.fips203.reduce.barrett.BarrettReducer;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class MLKEMTransformer implements Transformer {

    private final ParameterSet parameterSet;
    private final Reducer reducer;

    private static final int INPUT_OUTPUT_LENGTH = 256;

    final int[] transformLenVals = {
            128, 64, 64, 32, 32, 32, 32, 16, 16, 16, 16, 16, 16, 16, 16, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8,
            8, 8, 8, 4, 4, 4, 4,4, 4, 4, 4, 4, 4, 4, 4,4, 4, 4, 4, 4, 4, 4, 4,4, 4, 4, 4,4, 4, 4, 4,4, 4, 4, 4,
            2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2,
            2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2
    };
    final int[] transformStartVals = {
            0, 0, 128, 0, 64, 128, 192, 0, 32, 64, 96, 128, 160, 192, 224, 0, 16, 32, 48, 64, 80, 96, 112, 128,
            144, 160, 176, 192, 208, 224, 240, 0, 8, 16, 24, 32, 40, 48, 56, 64, 72, 80, 88, 96, 104, 112, 120,
            128, 136, 144, 152, 160, 168, 176, 184, 192, 200, 208, 216, 224, 232, 240, 248, 0, 4, 8, 12, 16, 20,
            24, 28, 32, 36, 40, 44, 48, 52, 56, 60, 64, 68, 72, 76, 80, 84, 88, 92, 96, 100, 104, 108, 112, 116,
            120, 124, 128, 132, 136, 140, 144, 148, 152, 156, 160, 164, 168, 172, 176, 180, 184, 188, 192, 196,
            200, 204, 208, 212, 216, 220, 224, 228, 232, 236, 240, 244, 248, 252
    };
    final int[] transformZetaVals = {
            1729, 2580, 3289, 2642, 630, 1897, 848, 1062, 1919, 193, 797, 2786, 3260, 569, 1746, 296, 2447, 1339,
            1476, 3046, 56, 2240, 1333, 1426, 2094, 535, 2882, 2393, 2879, 1974, 821, 289, 331, 3253, 1756, 1197,
            2304, 2277, 2055, 650, 1977, 2513, 632, 2865, 33, 1320, 1915, 2319, 1435, 807, 452, 1438, 2868, 1534,
            2402, 2647, 2617, 1481, 648, 2474, 3110, 1227, 910, 17, 2761, 583, 2649, 1637, 723, 2288, 1100, 1409,
            2662, 3281, 233, 756, 2156, 3015, 3050, 1703, 1651, 2789, 1789, 1847, 952, 1461, 2687, 939, 2308, 2437,
            2388, 733, 2337, 268, 641, 1584, 2298, 2037, 3220, 375, 2549, 2090, 1645, 1063, 319, 2773, 757, 2099,
            561, 2466, 2594, 2804, 1092, 403, 1026, 1143, 2150, 2775, 886, 1722, 1212, 1874, 1029, 2110, 2935, 885,
            2154
    };

    final int[] inverseLenVals = {
            2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2,
            2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 4, 4, 4, 4, 4,
            4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 8, 8, 8, 8, 8, 8, 8, 8,
            8, 8, 8, 8, 8, 8, 8, 8, 16, 16, 16, 16, 16, 16, 16, 16, 32, 32, 32, 32, 64, 64, 128
    };
    final int[] inverseStartVals = {
            0, 4, 8, 12, 16, 20, 24, 28, 32, 36, 40, 44, 48, 52, 56, 60, 64, 68, 72, 76, 80, 84, 88, 92, 96, 100,
            104, 108, 112, 116, 120, 124, 128, 132, 136, 140, 144, 148, 152, 156, 160, 164, 168, 172, 176, 180, 184,
            188, 192, 196, 200, 204, 208, 212, 216, 220, 224, 228, 232, 236, 240, 244, 248, 252, 0, 8, 16, 24, 32,
            40, 48, 56, 64, 72, 80, 88, 96, 104, 112, 120, 128, 136, 144, 152, 160, 168, 176, 184, 192, 200, 208,
            216, 224, 232, 240, 248, 0, 16, 32, 48, 64, 80, 96, 112, 128, 144, 160, 176, 192, 208, 224, 240, 0, 32,
            64, 96, 128, 160, 192, 224, 0, 64, 128, 192, 0, 128, 0
    };
    final int[] inverseZetaVals = {
            2154, 885, 2935, 2110, 1029, 1874, 1212, 1722, 886, 2775, 2150, 1143, 1026, 403, 1092, 2804, 2594, 2466,
            561, 2099, 757, 2773, 319, 1063, 1645, 2090, 2549, 375, 3220, 2037, 2298, 1584, 641, 268, 2337, 733,
            2388, 2437, 2308, 939, 2687, 1461, 952, 1847, 1789, 2789, 1651, 1703, 3050, 3015, 2156, 756, 233, 3281,
            2662, 1409, 1100, 2288, 723, 1637, 2649, 583, 2761, 17, 910, 1227, 3110, 2474, 648, 1481, 2617, 2647,
            2402, 1534, 2868, 1438, 452, 807, 1435, 2319, 1915, 1320, 33, 2865, 632, 2513, 1977, 650, 2055, 2277,
            2304, 1197, 1756, 3253, 331, 289, 821, 1974, 2879, 2393, 2882, 535, 2094, 1426, 1333, 2240, 56, 3046,
            1476, 1339, 2447, 296, 1746, 569, 3260, 2786, 797, 193, 1919, 1062, 848, 1897, 630, 2642, 3289, 2580,
            1729
    };

    /**
     * Precomputed values of gamma = 𝜁^(2BitRev7(𝑖)+1) mod 𝑞 as provided in Appendix A of the FIPS203 Specification
     * on Page 45.  Computation of these values can overflow built-in data types before being
     * bounded by the modulus so it is significantly easier and faster to work with precomputed values.
     * These values are used in the implementation of Algorithm 11 when multiplying polynomial
     * coefficient matrices in NTT space.
     */
    final int[] nttGammaVals = {
            17, -17, 2761, -2761, 583, -583, 2649, -2649,
            1637, -1637, 723, -723, 2288, -2288, 1100, -1100,
            1409, -1409, 2662, -2662, 3281, -3281, 233, -233,
            756, -756, 2156, -2156, 3015, -3015, 3050, -3050,
            1703, -1703, 1651, -1651, 2789, -2789, 1789, -1789,
            1847, -1847, 952, -952, 1461, -1461, 2687, -2687,
            939, -939, 2308, -2308, 2437, -2437, 2388, -2388,
            733, -733, 2337, -2337, 268, -268, 641, -641,
            1584, -1584, 2298, -2298, 2037, -2037, 3220, -3220,
            375, -375, 2549, -2549, 2090, -2090, 1645, -1645,
            1063, -1063, 319, -319, 2773, -2773, 757, -757,
            2099, -2099, 561, -561, 2466, -2466, 2594, -2594,
            2804, -2804, 1092, -1092, 403, -403, 1026, -1026,
            1143, -1143, 2150, -2150, 2775, -2775, 886, -886,
            1722, -1722, 1212, -1212, 1874, -1874, 1029, -1029,
            2110, -2110, 2935, -2935, 885, -885, 2154, -2154
    };

    public MLKEMTransformer(ParameterSet parameterSet, Reducer reducer) {
        this.parameterSet = parameterSet;
        this.reducer = reducer;
    }

    public static MLKEMTransformer create(ParameterSet parameterSet) {
        return new MLKEMTransformer(
                parameterSet,
                BarrettReducer.create(parameterSet)
        );
    }

    private void validateInput(int[] input) {

        int q = parameterSet.getQ();

        // Validate input is correct length
        if (input == null || input.length != INPUT_OUTPUT_LENGTH) {
            throw new IllegalArgumentException(String.format("Input must be an array of %d long values", INPUT_OUTPUT_LENGTH));
        }

        // Validate input has properly bounded values in modulo q
        List<Integer> incorrectIndexes = IntStream.range(0, input.length)
                .filter(i -> input[i] < 0 || input[i] > q)
                .boxed()
                .collect(Collectors.toList());;
        if (!incorrectIndexes.isEmpty()) {
            throw new IllegalArgumentException(String.format("Input values at the following indexes were not in modulo %d: %s", q, incorrectIndexes));
        }
    }

    @Override
    public int[] transform(int[] input) {

        // Validate the input
        validateInput(input);

        // Make a copy of the input to operate on
        // This variable is called f-hat in the FIPS203 spec, Algorithm 9, Line 1
        int[] result = input.clone();

        // NOTE: The FIPS203 spec has two outer loops that calculate {@code len} and {@code start} values that are used
        // to modify the inner loop conditions.  It also defines a manually incremented {@code i} loop counter that
        // is used as input to calculate the zeta values.  To improve performance and readability, we have
        // pre-calculated these three values for each iteration of the outer loop and ordered them so we can use
        // a single outer loop indexed on {@code i} from {@code 0} to {@code 126}.
        for (int i = 0; i < transformLenVals.length; i++) {

            // Retrieve pre-calculated loop values
            int len = transformLenVals[i];
            int start = transformStartVals[i];
            int zeta = transformZetaVals[i];

            // Core transform loop
            for (int j = start; j < start + len; j++) {
                int t = reducer.reduce(zeta * result[j + len]);
                result[j + len] = reducer.reduce(result[j] - t);
                result[j] = reducer.reduce(result[j] + t);
            }
        }

        // Return the resulting transform
        return result;
    }

    @Override
    public int[] inverse(int[] input) {

        // Validate the input
        validateInput(input);

        // Make a copy of the input to operate on
        // This variable is called f-hat in the FIPS203 spec, Algorithm 9, Line 1
        int[] result = input.clone();

        // NOTE: The FIPS203 spec has two outer loops that calculate {@code len} and {@code start} values that are used
        // to modify the inner loop conditions.  It also defines a manually decremented {@code i} loop counter that
        // is used as input to calculate the zeta values.  To improve performance and readability, we have
        // pre-calculated these three values for each iteration of the outer loop and ordered them so we can use
        // a single outer loop indexed on {@code i} from {@code 0} to {@code 126}.
        for (int i = 0; i < inverseLenVals.length; i++) {

            // Retrieve pre-calculated loop values
            int len = inverseLenVals[i];
            int start = inverseStartVals[i];
            int zeta = inverseZetaVals[i];

            // Core inverse transform loop
            for (int j = start; j < start + len; j++) {
                int t = result[j];
                result[j] = reducer.reduce(t + result[j + len]);
                result[j + len] = reducer.reduce(zeta * (result[j + len] - t));
            }
        }

        // Multiply all entries
        for (int i = 0; i < result.length; i++) {

            // NOTE: The magic number 3303 is defined in the FIPS203 spec as 128^-1.
            result[i] = reducer.reduce(result[i] * 3303);

        }

        // Return the resulting transform
        return result;

    }

    @Override
    public int[][] matrixMultiply(int[][][] a, int[][] b) {
        int aRows = a.length;
        int aCols = a[0].length;

        int[][] product = new int[aRows][256];

        for (int i = 0; i < aRows; i++) {
            for (int j = 0; j < aCols; j++) {
                int[] nttProduct = multiplyNTTs(a[i][j], b[j]);
                for (int k = 0; k < 256; k++) {
                    product[i][k] = reducer.reduce(product[i][k] + nttProduct[k]);
                }
            }
        }

        return product;
    }

    @Override
    public int[][] matrixAdd(int[][] a, int[][] b) {

        int rows = a.length;
        int cols = a[0].length;

        int[][] sum = new int[rows][];

        for (int i = 0; i < rows; i++) {
            sum[i] = new int[cols];
            for (int j = 0; j < cols; j++) {
                sum[i][j] = reducer.reduce(a[i][j] + b[i][j]);
            }
        }

        return sum;

    }

    @Override
    public int[][][] matrixTranspose(int[][][] a) {

        int rows = a.length;
        int cols = a[0].length;

        int[][][] transpose = new int[rows][cols][];

        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < cols; j++) {
                transpose[j][i] = a[i][j].clone();
            }
        }

        return transpose;
    }

    @Override
    public int[] multiplyNTTs(int[] fHat, int[] gHat) {

        // Compiler validation of input
        assert fHat != null;
        assert fHat.length == 256;

        // Compiler validation of input
        assert gHat != null;
        assert gHat.length == 256;

        int[] hHat = new int[256];

        for (int i = 0; i < 128; i++) {
            int[] c = baseCaseMultiply(
                    fHat[2*i],
                    fHat[2*i+1],
                    gHat[2*i],
                    gHat[2*i+1],
                    nttGammaVals[i]
            );
            hHat[2*i] = c[0];
            hHat[2*i+1] = c[1];
        }

        // Return the result
        return hHat;

    }

    @Override
    public int[] baseCaseMultiply(int a0, int a1, int b0, int b1, int gamma) {

        // Calculate c0
        int a0b0 = reducer.reduce(a0 * b0);
        int a1b1 = reducer.reduce(a1 * b1);
        int a1b1gamma = reducer.reduce(a1b1 * gamma);
        int c0 = reducer.reduce(a0b0 + a1b1gamma);

        // Calculate c1
        int a0b1 = reducer.reduce(a0 * b1);
        int a1b0 = reducer.reduce(a1 * b0);
        int c1 = reducer.reduce(a0b1 + a1b0);

        // Return compound result
        return new int[]{c0, c1};

    }

    @Override
    public int[] vectorTransposeMultiply(int[][] a, int[][] b) {
        int[] product = new int[parameterSet.getN()];
        for (var i = 0; i < a.length; i++) {
            var nttProduct = multiplyNTTs(a[i], b[i]);
            for (var j = 0; j < parameterSet.getN(); j++) {
                product[j] = (product[j] + nttProduct[j]) % parameterSet.getQ();
            }
        }
        return product;
    }

    @Override
    public int[] arrayAdd(int[] a, int[] b) {
        int[] sum = new int[a.length];
        for (int i = 0; i < a.length; i++) {
            sum[i] = reducer.reduce(a[i] + b[i]);
        }
        return sum;
    }

    @Override
    public int[] arraySubtract(int[] a, int[] b) {
        int[] difference = new int[a.length];
        for (int i = 0; i < a.length; i++) {
            difference[i] = reducer.reduce(a[i] - b[i]);
        }
        return difference;
    }
}
