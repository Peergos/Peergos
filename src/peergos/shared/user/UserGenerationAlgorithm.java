package peergos.shared.user;

import jsinterop.annotations.*;
import peergos.shared.cbor.*;
import peergos.shared.util.*;

import java.util.*;

public interface UserGenerationAlgorithm extends Cborable {
    Map<Integer, Type> byValue = new HashMap<>();
    @JsType
    enum Type {
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

    @JsMethod
    Type getType();

    static UserGenerationAlgorithm getDefault() {
        return new ScryptEd25519Curve25519(ScryptEd25519Curve25519.MIN_MEMORY_COST, 8, 1, 96);
    }

    static UserGenerationAlgorithm fromCbor(CborObject cbor) {
        if (! (cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Incorrect cbor type for UserGenerationAlgorithm: " + cbor);
        Type type = Type.byValue((int)((CborObject.CborLong) ((CborObject.CborMap) cbor).values.get(new CborObject.CborString("type"))).value);
        if (type == Type.ScryptEd25519Curve25519)
            return ScryptEd25519Curve25519.fromCbor(cbor);
        if (type == Type.Random)
            return new RandomUserType();
        throw new IllegalStateException("Unimplemented UserGeneration type algorithm: " + type);
    }
}
