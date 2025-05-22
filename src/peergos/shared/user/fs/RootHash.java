package peergos.shared.user.fs;

import peergos.shared.cbor.CborObject;
import peergos.shared.cbor.Cborable;
import peergos.shared.util.ArrayOps;

import java.util.Arrays;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;

public class RootHash implements Cborable {
    public final byte[] hash;

    public RootHash(byte[] hash) {
        if (hash.length != 32)
            throw new IllegalArgumentException("Incorrect hash length: " + hash.length);
        this.hash = hash;
    }

    @Override
    public CborObject toCbor() {
        SortedMap<String, Cborable> state = new TreeMap<>();
        state.put("h", new CborObject.CborByteArray(hash));
        return CborObject.CborMap.build(state);
    }

    public static RootHash fromCbor(Cborable cbor) {
        if (!(cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Invalid cbor for HashBranch! " + cbor);
        CborObject.CborMap m = (CborObject.CborMap) cbor;
        return new RootHash(m.getByteArray("h"));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RootHash rootHash = (RootHash) o;
        return Objects.deepEquals(hash, rootHash.hash);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(hash);
    }

    @Override
    public String toString() {
        return ArrayOps.bytesToHex(hash);
    }
}
