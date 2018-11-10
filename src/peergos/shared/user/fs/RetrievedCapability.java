package peergos.shared.user.fs;

import peergos.shared.cbor.CborObject;
import peergos.shared.cbor.Cborable;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

public class RetrievedCapability implements Cborable {
    public String path;
    public FilePointer fp;

    public RetrievedCapability(String path, FilePointer fp) {
        this.path = path;
        this.fp = fp;
    }

    @Override
    public CborObject toCbor() {
        Map<String, CborObject> cbor = new TreeMap<>();
        cbor.put("p", new CborObject.CborString(path));
        cbor.put("fp", fp.toCbor());
        return CborObject.CborMap.build(cbor);
    }

    public static RetrievedCapability fromByteArray(byte[] raw) {
        return fromCbor(CborObject.fromByteArray(raw));
    }

    public static RetrievedCapability fromCbor(Cborable cbor) {
        if (! (cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Incorrect cbor for RetrievedCapability: " + cbor);
        SortedMap<CborObject, ? extends Cborable> map = ((CborObject.CborMap) cbor).values;
        CborObject.CborString path = (CborObject.CborString)map.get(new CborObject.CborString("p"));
        FilePointer fp = FilePointer.fromCbor(map.get(new CborObject.CborString("fp")));
        return new RetrievedCapability(path.value, fp);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        RetrievedCapability that = (RetrievedCapability) o;

        if (path != null ? !path.equals(that.path) : that.path != null) return false;
        return fp != null ? fp.equals(that.fp) : that.fp == null;
    }

    @Override
    public int hashCode() {
        int result = (path != null ? path.hashCode() : 0);
        result = 31 * result + (fp != null ? fp.hashCode() : 0);
        return result;
    }

    @Override
    public String toString() {
        return " path:" + path;
    }
}
