package peergos.shared.user.fs;

import peergos.shared.cbor.*;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public interface Fragmenter extends Cborable {

    /** The amount of extra space required by this fragmenter compared to the original file
     *
     * @return
     */
    double storageIncreaseFactor();

    byte[][] split(byte[] input);

    byte[] recombine(byte[][] encoded, int inputLength);

    static Fragmenter fromCbor(CborObject cbor) {
        if (! (cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Incorrect cbor for Fragmenter: " + cbor);

        SortedMap<CborObject, CborObject> values = ((CborObject.CborMap) cbor).values;

        long t = ((CborObject.CborLong) values.get(new CborObject.CborString("t"))).value;
        Type type = Type.ofVal((int) t);
        if (type == Type.SIMPLE)
            return new SplitFragmenter();
        int originalFragments = (int)((CborObject.CborLong) values.get(new CborObject.CborString("o"))).value;
        int allowedFailures = (int)((CborObject.CborLong) values.get(new CborObject.CborString("a"))).value;
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
