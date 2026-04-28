package peergos.shared.mutable;

import peergos.shared.cbor.CborObject;
import peergos.shared.cbor.Cborable;
import peergos.shared.crypto.hash.PublicKeyHash;

import java.util.SortedMap;
import java.util.TreeMap;

public class SignedPointerUpdate implements Cborable {
    public final PublicKeyHash writer;
    public final byte[] signed;

    public SignedPointerUpdate(PublicKeyHash writer, byte[] signed) {
        this.writer = writer;
        this.signed = signed;
    }

    @Override
    public CborObject toCbor() {
        SortedMap<String, Cborable> state = new TreeMap<>();
        state.put("w", writer);
        state.put("s", new CborObject.CborByteArray(signed));
        return CborObject.CborMap.build(state);
    }

    public static SignedPointerUpdate fromCbor(Cborable cbor) {
        if (! (cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Invalid cbor for SignedPointerUpdate! " + cbor);
        CborObject.CborMap m = (CborObject.CborMap) cbor;
        return new SignedPointerUpdate(m.get("w", PublicKeyHash::fromCbor), m.get("s", c -> ((CborObject.CborByteArray)c).value));
    }
}
