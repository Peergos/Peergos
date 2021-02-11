package peergos.shared.social;

import jsinterop.annotations.*;
import peergos.shared.cbor.*;
import peergos.shared.user.fs.*;

import java.util.*;

@JsType
public class SharedItem implements Cborable {
    public final AbsoluteCapability cap;
    public final String owner, sharer, path;

    public SharedItem(AbsoluteCapability cap, String owner, String sharer, String path) {
        this.cap = cap;
        this.owner = owner;
        this.sharer = sharer;
        this.path = path;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SharedItem that = (SharedItem) o;
        return Objects.equals(cap, that.cap) &&
                Objects.equals(owner, that.owner) &&
                Objects.equals(sharer, that.sharer) &&
                Objects.equals(path, that.path);
    }

    @Override
    public int hashCode() {
        return Objects.hash(cap, owner, sharer, path);
    }

    @Override
    public CborObject toCbor() {
        SortedMap<String, Cborable> state = new TreeMap<>();
        state.put("c", cap);
        state.put("o", new CborObject.CborString(owner));
        state.put("s", new CborObject.CborString(sharer));
        state.put("p", new CborObject.CborString(path));
        return CborObject.CborMap.build(state);
    }

    public static SharedItem fromCbor(Cborable cbor) {
        if (! (cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Invalid cbor! " + cbor);
        CborObject.CborMap m = (CborObject.CborMap) cbor;

        AbsoluteCapability cap = m.getObject("c", AbsoluteCapability::fromCbor);
        String owner = m.getString("o");
        String sharer = m.getString("s");
        String path = m.getString("p");
        return new SharedItem(cap, owner, sharer, path);
    }

    @Override
    public String toString() {
        return path + " via " + sharer;
    }
}
