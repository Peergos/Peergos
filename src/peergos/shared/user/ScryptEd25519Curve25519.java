package peergos.shared.user;

import jsinterop.annotations.*;
import peergos.shared.cbor.*;

import java.util.*;

public class ScryptEd25519Curve25519 implements UserGenerationAlgorithm {

    public static final int MIN_MEMORY_COST = 17;

    @JsProperty
    public final int memoryCost, cpuCost, parallelism, outputBytes;

    public ScryptEd25519Curve25519(int memoryCost, int cpuCost, int parallelism, int outputBytes) {
        if (memoryCost < MIN_MEMORY_COST)
            throw new IllegalStateException("Scrpyt memory cost must be >= 17");
        this.memoryCost = memoryCost;
        this.cpuCost = cpuCost;
        this.parallelism = parallelism;
        this.outputBytes = outputBytes;
    }

    @Override
    public CborObject toCbor() {
        Map<String, CborObject> props = new TreeMap<>();
        props.put("type", new CborObject.CborLong(getType().value));
        props.put("m", new CborObject.CborLong(memoryCost));
        props.put("c", new CborObject.CborLong(cpuCost));
        props.put("p", new CborObject.CborLong(parallelism));
        props.put("o", new CborObject.CborLong(outputBytes));
        return CborObject.CborMap.build(props);
    }

    static peergos.shared.user.ScryptEd25519Curve25519 fromCbor(Cborable cbor) {
        int memoryCost = (int) ((CborObject.CborLong) ((CborObject.CborMap) cbor).values.get(new CborObject.CborString("m"))).value;
        int cpuCost = (int) ((CborObject.CborLong) ((CborObject.CborMap) cbor).values.get(new CborObject.CborString("c"))).value;
        int parallelsm = (int) ((CborObject.CborLong) ((CborObject.CborMap) cbor).values.get(new CborObject.CborString("p"))).value;
        int outputBytes = (int) ((CborObject.CborLong) ((CborObject.CborMap) cbor).values.get(new CborObject.CborString("o"))).value;
        return new peergos.shared.user.ScryptEd25519Curve25519(memoryCost, cpuCost, parallelsm, outputBytes);
    }

    @Override
    public Type getType() {
        return Type.ScryptEd25519Curve25519;
    }
}
