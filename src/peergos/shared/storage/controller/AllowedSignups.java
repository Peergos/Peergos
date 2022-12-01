package peergos.shared.storage.controller;

import jsinterop.annotations.*;
import peergos.shared.cbor.*;

import java.util.*;

@JsType
public
class AllowedSignups implements Cborable {
    public final boolean free, paid;

    public AllowedSignups(boolean free, boolean paid) {
        this.free = free;
        this.paid = paid;
    }

    @Override
    public CborObject toCbor() {
        SortedMap<String, Cborable> state = new TreeMap<>();
        state.put("f", new CborObject.CborBoolean(free));
        state.put("p", new CborObject.CborBoolean(paid));
        return CborObject.CborMap.build(state);
    }

    public static AllowedSignups fromCbor(CborObject cbor) {
        if (! (cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Incorrect cbor for CryptreeNode: " + cbor);

        CborObject.CborMap m = (CborObject.CborMap) cbor;
        return new AllowedSignups(m.getBoolean("f"), m.getBoolean("p"));
    }
}
