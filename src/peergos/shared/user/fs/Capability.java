package peergos.shared.user.fs;

import peergos.shared.cbor.CborObject;
import peergos.shared.cbor.Cborable;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

public class Capability implements Cborable {
    public String friend;
    public String path;
    public FilePointer fp;

    public Capability(String friend, String path, FilePointer fp) {
        this.friend = friend;
        this.path = path;
        this.fp = fp;
    }

    @Override
    public CborObject toCbor() {
        Map<String, CborObject> cbor = new TreeMap<>();
        cbor.put("f", new CborObject.CborString(friend));
        cbor.put("p", new CborObject.CborString(path));
        cbor.put("fp", fp.toCbor());
        return CborObject.CborMap.build(cbor);
    }

    public static Capability fromByteArray(byte[] raw) {
        return fromCbor(CborObject.fromByteArray(raw));
    }

    public static Capability fromCbor(Cborable cbor) {
        if (! (cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Incorrect cbor for Capability: " + cbor);
        SortedMap<CborObject, ? extends Cborable> map = ((CborObject.CborMap) cbor).values;
        CborObject.CborString friend = (CborObject.CborString)map.get(new CborObject.CborString("f"));
        CborObject.CborString path = (CborObject.CborString)map.get(new CborObject.CborString("p"));
        FilePointer fp = FilePointer.fromCbor(map.get(new CborObject.CborString("fp")));
        return new Capability(friend.value, path.value, fp);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        Capability that = (Capability) o;

        if (friend != null ? !friend.equals(that.friend) : that.friend != null) return false;
        if (path != null ? !path.equals(that.path) : that.path != null) return false;
        return fp != null ? fp.equals(that.fp) : that.fp == null;

    }

    @Override
    public int hashCode() {
        int result = friend != null ? friend.hashCode() : 0;
        result = 31 * result + (path != null ? path.hashCode() : 0);
        result = 31 * result + (fp != null ? fp.hashCode() : 0);
        return result;
    }

    @Override
    public String toString() {
        return "friend:" + friend + " path:" + path;
    }
}
