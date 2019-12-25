package peergos.shared.user.fs.erasure;
import java.util.logging.*;

import org.junit.*;

import java.math.*;
import java.util.*;
import java.util.function.*;
import java.util.stream.*;

public class ErrorTests {
	private static final Logger LOG = Logger.getGlobal();
    public static class ErasureParameters {
        @org.junit.Test
        public void findAcceptableErasureParameters() {
            int totalUserSizeInMB = 10000;
            int nChunks = totalUserSizeInMB / 5;
            Predicate<BigDecimal> acceptableFailure = pr -> pr.doubleValue() < 1.0/nChunks; // Expect not to lose a single chunk
            Stream.of(60).forEach(n -> IntStream.range(1, 6).map(i -> i*n/6)
                    .forEach(k -> Stream.of(0.5, 0.7, 0.8, 0.9).forEach(p -> {
                        BigDecimal prFail = probabilityFailure(n, k, p);
                        if (acceptableFailure.test(prFail))
                            LOG.info(n + ", " + k + ", " + p + " -> " + prFail + "\n");
                    }
                    )
                    )
            );

            double p = 0.5;
            int f = 40, e = 10;
            int n = f + 2*e, k = f + e;

            double min_p = 0.0, max_p = 1.0;
            while (true) {
                if (acceptableFailure.test(probabilityFailure(n, k, p))) {
                    max_p = p;
                    p = (p + min_p) / 2;
                } else {
                    min_p = p;
                    p = (1 + p) / 2;
                }
                if (Math.abs(max_p - min_p) < 0.01)
                    break;
            }
            LOG.info(n + ", " + k + ", " + p + " -> " + probabilityFailure(n, k, p) + "\n");
        }
    }

    /*
    *  The probability of data loss using a k of n erasure code with each fragment having pr(correct) = p
    * */
    public static BigDecimal probabilityFailure(int n, int k, double p) {
        List<BigDecimal> collect = IntStream.range(0, k)
                .mapToObj(i -> new BigDecimal(p).pow(i).multiply(new BigDecimal(1 - p).pow(n - i)).multiply(choose(n, i))).collect(Collectors.toList());
        return collect.stream()
        .reduce(new BigDecimal(0), (a, b) -> a.add(b));
    }

    static BigInteger[][] choose = new BigInteger[200][200];
    static {
        for (int i=0; i < choose.length; i++) {
            choose[i][0] = BigInteger.valueOf(1);
            choose[i][i] = BigInteger.valueOf(1);
        }
        for (int i=1; i < choose.length; i++)
            for (int j=1; j < i; j++)
                choose[i][j] = choose[i-1][j-1].add(choose[i-1][j]);
    }

    private static BigDecimal choose(int n, int k) {
        return new BigDecimal(choose[n][k]);
    }

    @Test
    public void recoverFromErrors() {
        // 40 -> 128 KiB fragments which is nice
        recoverFromerrors(40, 30);
    }

    @Test
    public void standardRecovery() {
        recoverFromerrors(40, 10);
    }

    public void recoverFromerrors(int fragments, int maxErrors) {
        // this is a fragments + maxErrors of fragments + 2*maxErrors erasure scheme
        byte[] original = new byte[5 * 1024 * 1024];
        new Random().nextBytes(original);

        IntStream.range(0, maxErrors+1).forEach(e -> recover(original, fragments, maxErrors, e));
        IntStream.range(maxErrors + 1, 2*maxErrors).forEach(e ->
        {
            try {
                recover(original, fragments, maxErrors, e);
                throw new RuntimeException("Should fail with this many errors!");
            } catch (IllegalStateException err) {}
        }
        );
    }

    public void recover(byte[] original, int fragments, int maxErrors, int actualErrors) {
        byte[][] encoded = Erasure.split(original, fragments, maxErrors);
        Set<Integer> done = new HashSet<>();
        while (actualErrors - done.size() > 0) {
            int index = new Random().nextInt(encoded.length);
            if (!done.contains(index)) {
                done.add(index);
                encoded[index] = new byte[encoded[index].length];
            }
        }
        byte[] recovered = Erasure.recombine(encoded, 5 * 1024 * 1024, fragments, maxErrors);
        if (!Arrays.equals(original, recovered))
            throw new IllegalStateException("Different result from original with "+actualErrors+" errors!");

    }
}
