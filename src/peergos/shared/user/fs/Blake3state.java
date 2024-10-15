package peergos.shared.user.fs;

import peergos.shared.cbor.CborObject;
import peergos.shared.cbor.Cborable;
import peergos.shared.util.ArrayOps;

import java.util.Arrays;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;

public class Blake3state implements Cborable {
    public final byte[] hash;

    public Blake3state(byte[] hash) {
        this.hash = hash;
    }

    @Override
    public CborObject toCbor() {
        SortedMap<String, Cborable> state = new TreeMap<>();
        state.put("h", new CborObject.CborByteArray(hash));

        return CborObject.CborMap.build(state);
    }

    public static Blake3state fromCbor(Cborable c) {
        byte[] hash = ((CborObject.CborMap) c).getByteArray("h");
        return new Blake3state(hash);
    }

    @Override
    public String toString() {
        return ArrayOps.bytesToHex(hash);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Blake3state that = (Blake3state) o;
        return Objects.deepEquals(hash, that.hash);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(hash);
    }
}
