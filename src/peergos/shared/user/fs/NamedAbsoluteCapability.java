package peergos.shared.user.fs;

import peergos.shared.cbor.*;
import peergos.shared.inode.*;

import java.util.*;

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
        if (cbor.containsKey("n"))
            throw new IllegalStateException("Incompatible cbor");
        cbor.put("n", new CborObject.CborString(name.name));
        return cbor;
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

    @Override
    public String toString() {
        return name + "/" + cap;
    }
}
