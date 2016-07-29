package peergos.user.fs;


import peergos.user.fs.erasure.Erasure;
import peergos.util.*;

import java.io.DataOutput;
import java.io.IOException;
import java.time.chrono.Era;

public class ErasureFragmenter implements Fragmenter {

    private final int nOriginalFragments;
    private final int nAllowedFailures;

    public ErasureFragmenter(int nOriginalFragments, int nAllowedFailures) {
        this.nOriginalFragments = nOriginalFragments;
        this.nAllowedFailures = nAllowedFailures;
    }

    public byte[][] split(byte[] input, boolean isJavaScript) {
        return Erasure.split(input, nOriginalFragments, nAllowedFailures, isJavaScript);
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
