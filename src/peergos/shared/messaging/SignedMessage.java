package peergos.shared.messaging;

import peergos.shared.cbor.*;

import java.util.*;

public final class SignedMessage implements Cborable {
    public final byte[] signature;
    public final MessageEnvelope msg;

    public SignedMessage(byte[] signature, MessageEnvelope msg) {
        this.signature = signature;
        this.msg = msg;
    }

    @Override
    public CborObject toCbor() {
        return new CborObject.CborList(Arrays.asList(new CborObject.CborByteArray(signature), new CborObject.CborByteArray(msg.serialize())));
    }

    public static SignedMessage fromCbor(Cborable cbor) {
        if (! (cbor instanceof CborObject.CborList))
            throw new IllegalStateException("Incorrect cbor: " + cbor);
        CborObject.CborList list = (CborObject.CborList) cbor;
        byte[] signature = list.get(0, c -> ((CborObject.CborByteArray) c).value);
        MessageEnvelope msg = MessageEnvelope.fromCbor(CborObject.fromByteArray(list.get(1, c -> ((CborObject.CborByteArray) c).value)));
        return new SignedMessage(signature, msg);
    }

    @Override
    public String toString() {
        return msg.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SignedMessage that = (SignedMessage) o;
        return Arrays.equals(signature, that.signature) && Objects.equals(msg, that.msg);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(msg);
        result = 31 * result + Arrays.hashCode(signature);
        return result;
    }
}
