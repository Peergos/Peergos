package peergos.shared.cbor;

public interface Cborable {

    CborObject toCbor();

    default byte[] serialize() {
        return toCbor().toByteArray();
    }
}
