package peergos.shared.crypto.hash;

import jsinterop.annotations.JsType;
import peergos.shared.cbor.*;
import peergos.shared.io.ipfs.cid.*;
import peergos.shared.io.ipfs.multihash.*;

@JsType
public class PublicKeyHash extends Multihash implements Cborable {
    public static final int MAX_KEY_HASH_SIZE = 1024;
    public static final PublicKeyHash NULL = new PublicKeyHash(new Multihash(Type.sha2_256, new byte[32]));

    public final Multihash multihash;

    public PublicKeyHash(Multihash multihash) {
        super(multihash.type, multihash.getHash());
        if (! isSafe(multihash))
            throw new IllegalStateException("Must use a safe hash for a public key!");
        this.multihash = multihash;
    }

    public static boolean isSafe(Multihash h) {
        return h.type == Type.sha2_256 || h.type == Type.id; // we can add other hashes later
    }

    @Override
    public byte[] toBytes() {
        return multihash.toBytes();
    }

    @Override
    public String toString() {
        return multihash.toString();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof PublicKeyHash && multihash.equals(((PublicKeyHash) o).multihash);
    }

    @Override
    public int hashCode() {
        return multihash.hashCode();
    }

    @Override
    @SuppressWarnings("unusable-by-js")
    public CborObject toCbor() {
        return new CborObject.CborMerkleLink(multihash);
    }

    public static PublicKeyHash decode(byte[] raw) {
        return new PublicKeyHash(Cid.cast(raw));
    }

    @SuppressWarnings("unusable-by-js")
    public static PublicKeyHash fromCbor(Cborable cbor) {
        if (! (cbor instanceof CborObject.CborMerkleLink))
            throw new IllegalStateException("Invalid cbor for PublicKeyHash! " + cbor);
        return new PublicKeyHash(((CborObject.CborMerkleLink) cbor).target);
    }

    public static PublicKeyHash fromString(String cid) {
        return new PublicKeyHash(Cid.decode(cid));
    }
}
