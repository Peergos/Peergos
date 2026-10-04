package peergos.shared.user.app;

import peergos.shared.cbor.*;
import peergos.shared.user.fs.*;

import java.util.*;

/** A user's approval for an app to use a folder they chose. The capability is the authority; the path is only a
 *  display hint.
 */
public class FolderGrant implements Cborable {
    public final String id;
    public final CapabilityWithPath target;
    public final boolean write;
    public final long granted;

    public FolderGrant(String id, CapabilityWithPath target, boolean write, long granted) {
        this.id = id;
        this.target = target;
        this.write = write;
        this.granted = granted;
    }

    public FolderGrant withTarget(CapabilityWithPath target) {
        return new FolderGrant(id, target, write, granted);
    }

    @Override
    public CborObject toCbor() {
        Map<String, Cborable> cbor = new TreeMap<>();
        cbor.put("i", new CborObject.CborString(id));
        cbor.put("t", target.toCbor());
        cbor.put("w", new CborObject.CborBoolean(write));
        cbor.put("g", new CborObject.CborLong(granted));
        return CborObject.CborMap.build(cbor);
    }

    public static FolderGrant fromCbor(Cborable cbor) {
        if (! (cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Incorrect cbor for FolderGrant: " + cbor);
        CborObject.CborMap map = (CborObject.CborMap) cbor;
        return new FolderGrant(map.getString("i"),
                map.getObject("t", CapabilityWithPath::fromCbor),
                map.getBoolean("w"),
                map.getLong("g"));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        FolderGrant that = (FolderGrant) o;
        return write == that.write && granted == that.granted && id.equals(that.id) && target.equals(that.target);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, target, write, granted);
    }
}
