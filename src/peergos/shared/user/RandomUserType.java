package peergos.shared.user;

import peergos.shared.cbor.*;

import java.util.*;

public class RandomUserType implements UserGenerationAlgorithm {

    public RandomUserType() {
    }

    @Override
    public CborObject toCbor() {
        Map<String, CborObject> props = new TreeMap<>();
        props.put("type", new CborObject.CborLong(getType().value));
        return CborObject.CborMap.build(props);
    }

    static RandomUserType fromCbor(CborObject cbor) {
        return new RandomUserType();
    }

    @Override
    public Type getType() {
        return Type.Random;
    }
}
