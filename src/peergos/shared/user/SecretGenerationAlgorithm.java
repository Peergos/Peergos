package peergos.shared.user;

import jsinterop.annotations.*;
import peergos.shared.cbor.*;
import peergos.shared.util.*;

import java.util.*;

public interface SecretGenerationAlgorithm extends Cborable {
    Map<Integer, Type> byValue = new HashMap<>();
    @JsType
    enum Type {
        Random(0x0),
        Scrypt(0x1);
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

    static SecretGenerationAlgorithm getDefault() {
        return new ScryptGenerator(ScryptGenerator.MIN_MEMORY_COST, 8, 1, 96);
    }

    static SecretGenerationAlgorithm fromCbor(Cborable cbor) {
        if (! (cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Incorrect cbor type for SecretGenerationAlgorithm: " + cbor);
        Type type = Type.byValue((int)((CborObject.CborLong) ((CborObject.CborMap) cbor).values.get(new CborObject.CborString("type"))).value);
        if (type == Type.Scrypt)
            return ScryptGenerator.fromCbor(cbor);
        if (type == Type.Random)
            return new RandomSecretType();
        throw new IllegalStateException("Unimplemented UserGeneration type algorithm: " + type);
    }
}
