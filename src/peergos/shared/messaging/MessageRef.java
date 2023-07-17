package peergos.shared.messaging;

import jsinterop.annotations.JsType;
import peergos.shared.cbor.*;
import peergos.shared.io.ipfs.Multihash;

import java.util.*;
@JsType
public class MessageRef implements Cborable {

    public final Multihash envelopeHash;

    public MessageRef(Multihash envelopeHash) {
        this.envelopeHash = envelopeHash;
    }

    @Override
    public CborObject toCbor() {
        Map<String, Cborable> result = new TreeMap<>();
        result.put("h", new CborObject.CborByteArray(envelopeHash.toBytes()));
        return CborObject.CborMap.build(result);
    }

    public static MessageRef fromCbor(Cborable cbor) {
        if (! (cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Incorrect cbor: " + cbor);
        CborObject.CborMap m = (CborObject.CborMap) cbor;
        Multihash h = Multihash.decode(m.getByteArray("h"));
        return new MessageRef(h);
    }

    @Override
    public String toString() {
        return envelopeHash.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MessageRef that = (MessageRef) o;
        return Objects.equals(envelopeHash, that.envelopeHash);
    }

    @Override
    public int hashCode() {
        return Objects.hash(envelopeHash);
    }
}
