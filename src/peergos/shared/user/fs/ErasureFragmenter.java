package peergos.shared.user.fs;


import peergos.shared.user.fs.erasure.Erasure;
import peergos.shared.util.*;

public class ErasureFragmenter implements Fragmenter {

    private final int nOriginalFragments;
    private final int nAllowedFailures;

    public ErasureFragmenter(int nOriginalFragments, int nAllowedFailures) {
        this.nOriginalFragments = nOriginalFragments;
        this.nAllowedFailures = nAllowedFailures;
    }

    public byte[][] split(byte[] input) {
        return Erasure.split(input, nOriginalFragments, nAllowedFailures);
    }

    public byte[] recombine(byte[][] encoded, int truncateLength) {
        // truncateTo should be  input.length
        return Erasure.recombine(encoded, truncateLength, nOriginalFragments, nAllowedFailures);
    }

    public void serialize(DataSink dout) {
        dout.writeInt(Type.ERASURE_CODING.val);
        dout.writeInt(nOriginalFragments);
        dout.writeInt(nAllowedFailures);
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
}
