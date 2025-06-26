package peergos.shared.crypto.asymmetric.mlkem;

import peergos.shared.cbor.CborObject;
import peergos.shared.cbor.Cborable;

import java.util.Arrays;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;

public class MlkemPublicKey implements Cborable {

    private final Mlkem implementation;
    private final byte[] keyBytes;

    public MlkemPublicKey(byte[] keyBytes, Mlkem implementation) {
        this.keyBytes = keyBytes;
        this.implementation = implementation;
    }

    public CompletableFuture<Mlkem.Encapsulation> encapsulate() {
        return implementation.encapsulate(keyBytes);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MlkemPublicKey that = (MlkemPublicKey) o;
        return Objects.deepEquals(keyBytes, that.keyBytes);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(keyBytes);
    }

    @Override
    public CborObject toCbor() {
        SortedMap<String, Cborable> state = new TreeMap<>();
        state.put("p", new CborObject.CborByteArray(keyBytes));
        return CborObject.CborMap.build(state);
    }

    static MlkemPublicKey fromCbor(Cborable cbor, Mlkem implementation) {
        if (! (cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Invalid cbor for MlkemPublicKey! " + cbor);
        CborObject.CborMap m = (CborObject.CborMap) cbor;
        return new MlkemPublicKey(m.getByteArray("p"), implementation);
    }
}
