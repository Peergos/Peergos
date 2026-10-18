package peergos.shared.routing;

import peergos.shared.cbor.*;

import java.util.*;
import java.util.function.*;

/** The record types, with the code that tags each in storage and on the wire. */
public enum RecordType {
    IDENTITY_INCEPTION(0, true, IdentityInception.class, IdentityInception::fromCbor),
    IDENTITY_ROTATION(1, true, IdentityRotation.class, IdentityRotation::fromCbor),
    IDENTITY_MOVE(2, true, IdentityMove.class, IdentityMove::fromCbor),
    SERVER_INCEPTION(3, false, ServerInception.class, ServerInception::fromCbor),
    SERVER_ROTATION(4, false, ServerRotation.class, ServerRotation::fromCbor),
    SERVER_ADDRESSES(5, false, ServerAddresses.class, ServerAddresses::fromCbor);

    public final int code;
    public final boolean identity;
    private final Class<? extends SignedRecord> type;
    private final Function<Cborable, SignedRecord> decoder;

    RecordType(int code, boolean identity, Class<? extends SignedRecord> type, Function<Cborable, SignedRecord> decoder) {
        this.code = code;
        this.identity = identity;
        this.type = type;
        this.decoder = decoder;
    }

    public static RecordType of(SignedRecord r) {
        for (RecordType t : values())
            if (t.type == r.getClass())
                return t;
        throw new IllegalStateException("Unknown record type " + r.getClass());
    }

    public static RecordType byCode(long code) {
        for (RecordType t : values())
            if (t.code == code)
                return t;
        throw new IllegalStateException("Unknown record type " + code);
    }

    /** A record with its type: {"t": code, "r": record}. */
    public static CborObject.CborMap tag(SignedRecord r) {
        Map<String, Cborable> m = new TreeMap<>();
        m.put("t", new CborObject.CborLong(of(r).code));
        m.put("r", r);
        return CborObject.CborMap.build(m);
    }

    public static SignedRecord untag(Cborable cbor) {
        CborObject.CborMap m = RoutingCbor.map(cbor, "tagged record");
        return byCode(m.getLong("t")).decoder.apply(m.get("r"));
    }
}
