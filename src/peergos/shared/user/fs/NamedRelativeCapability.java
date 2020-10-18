package peergos.shared.user.fs;

import peergos.shared.cbor.*;
import peergos.shared.inode.*;

import java.util.*;
import java.util.stream.*;

public class NamedRelativeCapability implements Cborable {
    public final PathElement name;
    public final RelativeCapability cap;

    public NamedRelativeCapability(PathElement name, RelativeCapability cap) {
        this.name = name;
        this.cap = cap;
    }

    public NamedRelativeCapability(String name, RelativeCapability cap) {
        this.name = new PathElement(name);
        this.cap = cap;
    }

    public NamedAbsoluteCapability toAbsolute(AbsoluteCapability source) {
        return new NamedAbsoluteCapability(name, cap.toAbsolute(source));
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

    public static NamedRelativeCapability fromCbor(Cborable cbor) {
        if (! (cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Invalid cbor");
        CborObject.CborMap map = ((CborObject.CborMap) cbor);

        String name = map.getString("n");
        RelativeCapability cap = RelativeCapability.fromCbor(cbor);
        return new NamedRelativeCapability(new PathElement(name), cap);
    }
}
