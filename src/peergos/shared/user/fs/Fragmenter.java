package peergos.shared.user.fs;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import peergos.shared.util.*;

import java.io.*;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import static org.junit.Assert.*;

public interface Fragmenter {

    byte[][] split(byte[] input);

    byte[] recombine(byte[][] encoded, int inputLength);

    void serialize(DataSink dout);

    static Fragmenter deserialize(DataInput din) throws IOException {
        int val = din.readInt();
        Type type  = Type.ofVal(val);
        switch (type) {
            case SIMPLE:
                return new peergos.shared.user.fs.SplitFragmenter();
            case ERASURE_CODING:
                int nOriginalFragments = din.readInt();
                int nAllowedFailures = din.readInt();
                return new peergos.shared.user.fs.ErasureFragmenter(nOriginalFragments, nAllowedFailures);
            default:
                throw new IllegalStateException();
        }
    }

    enum Type  {
        SIMPLE(0),
        ERASURE_CODING(1);

        public final int val;

        Type(int val) {
            this.val = val;
        }

        private static Map<Integer, Type> MAP = Stream.of(values())
                .collect(
                        Collectors.toMap(
                                e -> e.val,
                                e -> e));
        public static Type ofVal(int val) {
            Type type = MAP.get(val);
            if (type == null)
                throw new IllegalStateException("No type for value "+ val);
            return type;
        }
    }

    static Fragmenter getInstance() {
        //return new ErasureFragmenter(40, 10);
        return new SplitFragmenter();
    }
}
