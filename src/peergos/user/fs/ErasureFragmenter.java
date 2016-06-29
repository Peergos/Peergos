package peergos.user.fs;


import peergos.user.fs.erasure.Erasure;

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

    public byte[][] split(byte[] input) {
        return Erasure.split(input, nOriginalFragments, nAllowedFailures);
    }

    public byte[] recombine(byte[][] encoded, int truncateLength) {
        // truncateTo should be  input.length
        return Erasure.recombine(encoded, truncateLength, nOriginalFragments, nAllowedFailures);
    }

    public void serialize(DataOutput dout) throws IOException {
        dout.writeInt(Type.ERASURE_CODING.val);
        dout.writeInt(nOriginalFragments);
        dout.writeInt(nAllowedFailures);
    }

}
