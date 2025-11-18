package peergos.shared.user.fs;

import jsinterop.annotations.JsMethod;
import peergos.shared.cbor.*;
import peergos.shared.inode.*;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

public class NamedRelativeCapability implements Cborable {
    public final PathElement name;
    public final RelativeCapability cap;
    public final Optional<Boolean> isDir;
    public final Optional<String> mimetype;
    public final Optional<LocalDateTime> created;

    public NamedRelativeCapability(PathElement name,
                                   RelativeCapability cap,
                                   Optional<Boolean> isDir,
                                   Optional<String> mimetype,
                                   Optional<LocalDateTime> created) {
        this.name = name;
        this.cap = cap;
        this.isDir = isDir;
        this.mimetype = mimetype;
        this.created = created;
    }

    public NamedRelativeCapability(String name,
                                   RelativeCapability cap,
                                   Optional<Boolean> isDir,
                                   Optional<String> mimetype,
                                   Optional<LocalDateTime> created) {
        this(new PathElement(name), cap, isDir, mimetype, created);
    }

    public NamedAbsoluteCapability toAbsolute(AbsoluteCapability source) {
        return new NamedAbsoluteCapability(name, cap.toAbsolute(source), isDir, mimetype, created);
    }

    @JsMethod
    public String name() {
        return name.name;
    }

    @JsMethod
    public boolean isDir() {
        return isDir.orElse(false);
    }

    @JsMethod
    public LocalDateTime created() {
        return created.orElse(LocalDateTime.MIN);
    }

    @JsMethod
    public String mimeType() {
        return mimetype.orElse("application/octet-stream");
    }

    private void addCbor(String key, CborObject val, CborObject.CborMap m) {
        if (m.containsKey(key))
            throw new IllegalStateException("Incompatible cbor");
        m.put(key, val);
    }

    @Override
    public CborObject toCbor() {
        CborObject.CborMap cbor = cap.toCbor(); // This is to ensure binary compatibility for old code with new data
        // w, m, a, k, l are taken
        addCbor("n", new CborObject.CborString(name.name), cbor);
        isDir.ifPresent(d -> addCbor("d", new CborObject.CborBoolean(d), cbor));
        mimetype.ifPresent(m -> addCbor("t", new CborObject.CborString(m), cbor));
        created.ifPresent(c -> addCbor("c", new CborObject.CborLong(c.toEpochSecond(ZoneOffset.UTC)), cbor));
        return cbor;
    }

    public static NamedRelativeCapability fromCbor(Cborable cbor) {
        if (! (cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Invalid cbor");
        CborObject.CborMap map = ((CborObject.CborMap) cbor);

        String name = map.getString("n");
        RelativeCapability cap = RelativeCapability.fromCbor(cbor);
        Optional<Boolean> isDir = map.getOptional("d", c -> ((CborObject.CborBoolean)c).value);
        Optional<String> mimetype = map.getOptional("t", c -> ((CborObject.CborString)c).value);
        Optional<LocalDateTime> created = map.getOptional("c", c -> LocalDateTime.ofEpochSecond(((CborObject.CborLong)c).value, 0, ZoneOffset.UTC));
        return new NamedRelativeCapability(new PathElement(name), cap, isDir, mimetype, created);
    }
}
