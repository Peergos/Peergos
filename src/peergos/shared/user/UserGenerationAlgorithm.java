package peergos.shared.user;

import peergos.shared.cbor.*;
import peergos.shared.util.*;

import java.util.*;

public class UserGenerationAlgorithm {
    static final Map<Integer, Type> byValue = new HashMap<>();
    public enum Type {
        Random(0x0),
        ScryptEd25519Curve25519(0x1);
        // TODO find a post-quantum algorithm

        public final int value;

        Type(int value) {
            this.value = value;
            byValue.put(value, this);
        }

        public static Type byValue(int val) {
            if (!byValue.containsKey(val))
                throw new IllegalStateException("Unknown User Generation Algorithm type: " + StringUtils.format("%02x", val));
            return byValue.get(val);
        }
    }

    public final Type type;

    public UserGenerationAlgorithm(Type type) {
        this.type = type;
    }

    public static UserGenerationAlgorithm getDefault() {
        return new UserGenerationAlgorithm(Type.ScryptEd25519Curve25519);
    }

    public static UserGenerationAlgorithm fromCbor(CborObject cbor) {
        if (! (cbor instanceof CborObject.CborLong))
            throw new IllegalStateException("Incorrec cbor type for UserGenerationAlgorithm: " + cbor);
        return new UserGenerationAlgorithm(Type.byValue((int)((CborObject.CborLong) cbor).value));
    }
}
