package peergos.shared.user.fs;

import peergos.shared.cbor.*;
import peergos.shared.inode.*;

import java.util.*;
import java.util.stream.*;

public class NamedAbsoluteCapability implements Cborable {
    public final PathElement name;
    public final AbsoluteCapability cap;

    public NamedAbsoluteCapability(PathElement name, AbsoluteCapability cap) {
        this.name = name;
        this.cap = cap;
    }

    public NamedAbsoluteCapability(String name, AbsoluteCapability cap) {
        this.name = new PathElement(name);
        this.cap = cap;
    }

    @Override
    public CborObject toCbor() {
        CborObject.CborMap cbor = cap.toCbor(); // This is to ensure binary compatibility for old code with new data
        Map<String, CborObject> values = cbor.values.entrySet().stream()
                .collect(Collectors.toMap(e -> ((CborObject.CborString) e.getKey()).value, e -> (CborObject) e.getValue()));
        Cborable existing = values.put("n", new CborObject.CborString(name.name));
        if (existing != null)
            throw new IllegalStateException("Incompatible cbor");
        return CborObject.CborMap.build(values);
    }

    public static NamedAbsoluteCapability fromCbor(Cborable cbor) {
        if (! (cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Invalid cbor");
        CborObject.CborMap map = ((CborObject.CborMap) cbor);

        String name = map.getString("n");
        AbsoluteCapability cap = AbsoluteCapability.fromCbor(cbor);
        return new NamedAbsoluteCapability(new PathElement(name), cap);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        NamedAbsoluteCapability that = (NamedAbsoluteCapability) o;
        return Objects.equals(name, that.name) &&
                Objects.equals(cap, that.cap);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, cap);
    }
}
