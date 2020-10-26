package peergos.shared.user.fs;


import peergos.shared.cbor.*;
import peergos.shared.user.fs.erasure.Erasure;
import peergos.shared.util.*;

import java.util.*;
import java.util.stream.*;

public class ErasureFragmenter implements Fragmenter {

    private final int nOriginalFragments;
    private final int nAllowedFailures;

    public ErasureFragmenter(int nOriginalFragments, int nAllowedFailures) {
        this.nOriginalFragments = nOriginalFragments;
        this.nAllowedFailures = nAllowedFailures;
    }

    @Override
    public double storageIncreaseFactor() {
        return ((double)(2*nAllowedFailures + nOriginalFragments)) / nOriginalFragments;
    }

    public byte[][] split(byte[] input) {
        return Erasure.split(input, nOriginalFragments, nAllowedFailures);
    }

    public byte[] recombine(byte[][] encoded, int startOffset, int truncateLength) {
        // truncateTo should be  input.length
        byte[] withoutPrefix = Erasure.recombine(encoded, truncateLength, nOriginalFragments, nAllowedFailures);
        byte[] withPrefix = new byte[startOffset + withoutPrefix.length];
        System.arraycopy(withoutPrefix, 0, withPrefix, startOffset, withoutPrefix.length);
        return withPrefix;
    }

    @Override
    public CborObject toCbor() {
        Map<String, Cborable> res = new HashMap<>();
        res.put("t", new CborObject.CborLong(Type.ERASURE_CODING.val));
        res.put("o", new CborObject.CborLong(nOriginalFragments));
        res.put("a", new CborObject.CborLong(nAllowedFailures));
        return CborObject.CborMap.build(res);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        ErasureFragmenter that = (ErasureFragmenter) o;

        if (nOriginalFragments != that.nOriginalFragments) return false;
        return nAllowedFailures == that.nAllowedFailures;
    }

    @Override
    public int hashCode() {
        int result = nOriginalFragments;
        result = 31 * result + nAllowedFailures;
        return result;
    }

    public static final Set<Integer> ALLOWED_ORIGINAL = Stream.of(5, 10, 20, 40, 80).collect(Collectors.toSet());
    public static final Set<Integer> ALLOWED_FAILURES = Stream.of(5, 10, 20, 40, 80).collect(Collectors.toSet());
    public static final int ERASURE_ORIGINAL = 40; // mean 128 KiB fragments, could also use 80, 20, 10, 5
    public static final int ERASURE_ALLOWED_FAILURES = 10; // generates twice this extra fragments
}
