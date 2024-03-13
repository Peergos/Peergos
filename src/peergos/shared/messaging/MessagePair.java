package peergos.shared.messaging;

import jsinterop.annotations.JsType;
import peergos.shared.cbor.CborObject;
import peergos.shared.cbor.Cborable;

import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

@JsType
public class MessagePair implements Cborable {

    public final MessageEnvelope envelope;
    public final MessageRef ref;

    public MessagePair(MessageEnvelope envelope,
                       MessageRef ref) {
        this.envelope = envelope;
        this.ref = ref;
    }

    @Override
    public CborObject toCbor() {
        Map<String, Cborable> result = new TreeMap<>();
        result.put("e", envelope.toCbor());
        result.put("r", ref.toCbor());
        return CborObject.CborMap.build(result);
    }

    public static MessagePair fromCbor(Cborable cbor) {
        if (! (cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Incorrect cbor: " + cbor);
        CborObject.CborMap m = (CborObject.CborMap) cbor;
        MessageEnvelope envelope = m.get("e", MessageEnvelope::fromCbor);
        MessageRef ref = m.get("r", MessageRef::fromCbor);
        return new MessagePair(envelope, ref);
    }

    @Override
    public String toString() {
        return envelope + " - " + ref;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MessagePair that = (MessagePair) o;
        return Objects.equals(envelope, that.envelope) &&
                Objects.equals(ref, that.ref);
    }

    @Override
    public int hashCode() {
        return Objects.hash(envelope, ref);
    }
}
