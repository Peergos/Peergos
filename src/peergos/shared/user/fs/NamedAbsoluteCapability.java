package peergos.shared.user.fs;

import peergos.shared.cbor.*;
import peergos.shared.inode.*;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;

public class NamedAbsoluteCapability implements Cborable {
    public final PathElement name;
    public final AbsoluteCapability cap;
    public final Optional<Boolean> isDir;
    public final Optional<String> mimetype;
    public final Optional<LocalDateTime> created;

    public NamedAbsoluteCapability(PathElement name,
                                   AbsoluteCapability cap,
                                   Optional<Boolean> isDir,
                                   Optional<String> mimetype,
                                   Optional<LocalDateTime> created) {
        this.name = name;
        this.cap = cap;
        this.isDir = isDir;
        this.mimetype = mimetype;
        this.created = created;
    }

    public NamedAbsoluteCapability(String name,
                                   AbsoluteCapability cap,
                                   Optional<Boolean> isDir,
                                   Optional<String> mimetype,
                                   Optional<LocalDateTime> created) {
        this(new PathElement(name), cap, isDir, mimetype, created);
    }

    private void addCbor(String key, CborObject val, CborObject.CborMap m) {
        if (m.containsKey(key))
            throw new IllegalStateException("Incompatible cbor");
        m.put(key, val);
    }

    @Override
    public CborObject toCbor() {
        CborObject.CborMap cbor = cap.toCbor(); // This is to ensure binary compatibility for old code with new data
        if (cbor.containsKey("n"))
            throw new IllegalStateException("Incompatible cbor");
        cbor.put("n", new CborObject.CborString(name.name));
        isDir.ifPresent(d -> addCbor("d", new CborObject.CborBoolean(d), cbor));
        mimetype.ifPresent(m -> addCbor("t", new CborObject.CborString(m), cbor));
        created.ifPresent(c -> addCbor("c", new CborObject.CborLong(c.toEpochSecond(ZoneOffset.UTC)), cbor));
        return cbor;
    }

    public static NamedAbsoluteCapability fromCbor(Cborable cbor) {
        if (! (cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Invalid cbor");
        CborObject.CborMap map = ((CborObject.CborMap) cbor);

        String name = map.getString("n");
        AbsoluteCapability cap = AbsoluteCapability.fromCbor(cbor);
        Optional<Boolean> isDir = map.getOptional("d", c -> ((CborObject.CborBoolean)c).value);
        Optional<String> mimetype = map.getOptional("t", c -> ((CborObject.CborString)c).value);
        Optional<LocalDateTime> created = map.getOptional("c", c -> LocalDateTime.ofEpochSecond(((CborObject.CborLong)c).value, 0, ZoneOffset.UTC));
        return new NamedAbsoluteCapability(new PathElement(name), cap, isDir, mimetype, created);
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
