package peergos.shared.crypto.hash;

import jsinterop.annotations.JsType;
import peergos.shared.cbor.*;
import peergos.shared.io.ipfs.Cid;
import peergos.shared.io.ipfs.Multihash;

@JsType
public class PublicKeyHash extends Cid implements Cborable {
    public static final int MAX_KEY_HASH_SIZE = 1024;
    public static final PublicKeyHash NULL = new PublicKeyHash(new Cid(1, Codec.DagCbor, Type.sha2_256, new byte[32]));

    public final Cid target;

    public PublicKeyHash(Cid target) {
        super(target.version, target.codec, target.type, target.getHash());
        if (! isSafe(target))
            throw new IllegalStateException("Must use a safe hash for a public key!");
        this.target = target;
    }

    public static boolean isSafe(Multihash h) {
        return h.type == Type.sha2_256 || h.type == Type.id; // we can add other hashes later
    }

    @Override
    public byte[] toBytes() {
        return target.toBytes();
    }

    @Override
    public String toString() {
        return target.toString();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof PublicKeyHash && target.equals(((PublicKeyHash) o).target);
    }

    @Override
    public int hashCode() {
        return target.hashCode();
    }

    @Override
    public CborObject toCbor() {
        return new CborObject.CborMerkleLink(target);
    }

    public static PublicKeyHash decode(byte[] raw) {
        return new PublicKeyHash(Cid.cast(raw));
    }

    public static PublicKeyHash fromCbor(Cborable cbor) {
        if (! (cbor instanceof CborObject.CborMerkleLink))
            throw new IllegalStateException("Invalid cbor for PublicKeyHash! " + cbor);
        return new PublicKeyHash((Cid)((CborObject.CborMerkleLink) cbor).target);
    }

    public static PublicKeyHash fromString(String cid) {
        return new PublicKeyHash(Cid.decode(cid));
    }
}
