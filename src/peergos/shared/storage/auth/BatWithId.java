package peergos.shared.storage.auth;

import peergos.shared.cbor.*;
import peergos.shared.io.ipfs.Cid;
import peergos.shared.io.ipfs.bases.*;

import java.util.*;

public class BatWithId implements Cborable {

    public final Bat bat;
    public final Cid id;

    public BatWithId(Bat bat, Cid id) {
        if (id.isIdentity())
            throw new IllegalStateException("Cannot use identity cid here!");
        if (id.codec != Cid.Codec.Raw)
            throw new IllegalStateException("BatId codec must be Raw!");
        this.bat = bat;
        this.id = id;
    }

    public BatId id() {
        return new BatId(id);
    }

    public String encode() {
        return Multibase.encode(Multibase.Base.Base58BTC, serialize());
    }

    public static BatWithId decode(String in) {
        return fromCbor(CborObject.fromByteArray(Multibase.decode(in)));
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

    @Override
    public int hashCode() {
        return Objects.hash(bat, id);
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof BatWithId))
            return false;
        return bat.equals(((BatWithId) obj).bat) && id.equals(((BatWithId) obj).id);
    }
}
