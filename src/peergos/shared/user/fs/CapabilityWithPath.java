package peergos.shared.user.fs;

import peergos.shared.cbor.CborObject;
import peergos.shared.cbor.Cborable;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

public class CapabilityWithPath implements Cborable {
    public String path;
    public AbsoluteCapability cap;

    public CapabilityWithPath(String path, AbsoluteCapability cap) {
        this.path = path;
        this.cap = cap;
    }

    @Override
    public CborObject toCbor() {
        Map<String, Cborable> cbor = new TreeMap<>();
        cbor.put("p", new CborObject.CborString(path));
        cbor.put("c", cap.toCbor());
        return CborObject.CborMap.build(cbor);
    }

    public static CapabilityWithPath fromCbor(Cborable cbor) {
        if (! (cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Incorrect cbor for CapabilityWithPath: " + cbor);
        CborObject.CborMap map = (CborObject.CborMap) cbor;
        String path = map.getString("p");
        AbsoluteCapability fp = map.getObject("c", AbsoluteCapability::fromCbor);
        return new CapabilityWithPath(path, fp);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        CapabilityWithPath that = (CapabilityWithPath) o;

        if (path != null ? !path.equals(that.path) : that.path != null) return false;
        return cap != null ? cap.equals(that.cap) : that.cap == null;
    }

    @Override
    public int hashCode() {
        int result = (path != null ? path.hashCode() : 0);
        result = 31 * result + (cap != null ? cap.hashCode() : 0);
        return result;
    }

    @Override
    public String toString() {
        return " path:" + path;
    }
}
