package peergos.server.crypto.asymmetric.mlkem.fips203.transform;

public interface Transformer {

    /**
     * Performs a number theoretic transform of an array of 256 integers in modulo q=3329
     * The concrete implementation should mimic the output characteristics of Algorithm 9 in the FIP203 Specification
     *
     * @param input An array of 256 integers in modulo q
     * @return An array of 256 integers in modulo q transformed using the NTT algorithm
     */
    int[] transform(int[] input);

    /**
     * Performs the inverse of a number theoretic transform of an array of 256 integers in modulo q=3329
     * The concrete implementation should mimic the output characteristics of Algorithm 10 in the FIP203 Specification
     *
     * @param input An array of 256 integers in modulo q representing a number theoretic transform
     * @return An array of 256 integers in modulo q with the transform reversed
     */
    int[] inverse(int[] input);

    int[][] matrixMultiply(int[][][] a, int[][] b);

    int[][] matrixAdd(int[][] a, int[][] b);

    int[][][] matrixTranspose(int[][][] a);

    int[] multiplyNTTs(int[] fHat, int[] gHat);

    int[] baseCaseMultiply(int a0, int a1, int b0, int b1, int gamma);

    int[] vectorTransposeMultiply(int[][] a, int[][] b);

    int[] arrayAdd(int[] a, int[] b);

    int[] arraySubtract(int[] a, int[] b);

}
