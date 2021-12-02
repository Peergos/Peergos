package peergos.server.storage.auth;

import peergos.shared.cbor.*;
import peergos.shared.io.ipfs.cid.*;

import java.util.*;

public class BatWithId implements Cborable {

    public final Bat bat;
    public final Cid id;

    public BatWithId(Bat bat, Cid id) {
        this.bat = bat;
        this.id = id;
    }

    @Override
    public CborObject toCbor() {
        SortedMap<String, Cborable> state = new TreeMap<>();
        state.put("b", bat);
        state.put("i", new CborObject.CborByteArray(id.toBytes()));
        return CborObject.CborMap.build(state);
    }

    public static BatWithId fromCbor(Cborable cbor) {
        if (! (cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Incorrect cbor for Bat: " + cbor);

        CborObject.CborMap m = (CborObject.CborMap) cbor;
        return new BatWithId(m.get("b", Bat::fromCbor), m.get("i", c -> Cid.cast(((CborObject.CborByteArray)c).value)));
    }
}
