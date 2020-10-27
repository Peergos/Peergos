package peergos.shared.user.fs;

import jsinterop.annotations.JsType;
import peergos.shared.cbor.*;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** A Fragmenter decides how the fragments of a chunk are created.
 *
 *  The default implementation, SplitFragmenter simply splits the chunk into 128 KiB fragments
 *
 *  The ErasureFragmenter uses a Reed-Solomon erasure code to also generate more fragments according to the parameters.
 *
 */
@JsType
public interface Fragmenter extends Cborable {

    /** The amount of extra space required by this fragmenter compared to the original file
     *
     * @return
     */
    double storageIncreaseFactor();

    byte[][] split(byte[] input);

    byte[] recombine(byte[][] encoded, int startOffset, int inputLength);

    @SuppressWarnings("unusable-by-js")
    static Fragmenter fromCbor(Cborable cbor) {
        if (! (cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Incorrect cbor for Fragmenter: " + cbor);

        CborObject.CborMap map = (CborObject.CborMap) cbor;
        long t = map.getLong("t");
        Type type = Type.ofVal((int) t);
        if (type == Type.SIMPLE)
            return new SplitFragmenter();
        int originalFragments = (int)(map.getLong("o"));
        int allowedFailures = (int)(map.getLong("a"));
        return new ErasureFragmenter(originalFragments, allowedFailures);
    }

    enum Type  {
        SIMPLE(0),
        ERASURE_CODING(1);

        public final int val;

        Type(int val) {
            this.val = val;
        }

        private static Map<Integer, Type> MAP = Stream.of(values())
                .collect(Collectors.toMap(
                                e -> e.val,
                                e -> e));

        public static Type ofVal(int val) {
            Type type = MAP.get(val);
            if (type == null)
                throw new IllegalStateException("No Fragmenter type for value "+ val);
            return type;
        }
    }

    static Fragmenter getInstance() {
        return new SplitFragmenter();
    }
}
