package peergos.user.fs.erasure;

import org.junit.*;

import java.util.*;
import java.util.stream.*;

public class ErrorTests {
    byte[] original;
    int fragments = 40, maxErrors = 10;

    public ErrorTests() {}

    @Before
    public void init() {
        original = new byte[5 * 1024 * 1024];
        new Random().nextBytes(original);
    }

    @Test
    public void recoverFromerrors() {
        IntStream.range(0, maxErrors+1).forEach(this::recover);
        IntStream.range(maxErrors + 1, 2*maxErrors).forEach(e ->
        {
            try {
                recover(e);
                throw new RuntimeException("Should fail wih this many errors!");
            } catch (IllegalStateException err) {}
        }
        );
    }

    public void recover(int errors) {
        byte[][] encoded = Erasure.split(original, fragments, maxErrors);
        Set<Integer> done = new HashSet<>();
        while (errors - done.size() > 0) {
            int index = new Random().nextInt(encoded.length);
            if (!done.contains(index)) {
                done.add(index);
                encoded[index] = new byte[encoded[index].length];
            }
        }
        byte[] recovered = Erasure.recombine(encoded, 5 * 1024 * 1024, fragments, maxErrors);
        if (!Arrays.equals(original, recovered))
            throw new IllegalStateException("Different result from original with "+errors+" errors!");
        System.out.println();
    }
}
