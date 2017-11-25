package peergos.shared.crypto.hash;

import peergos.shared.cbor.*;
import peergos.shared.io.ipfs.cid.*;
import peergos.shared.io.ipfs.multihash.*;

public class PublicKeyHash extends Multihash implements Cborable {
    public static final int MAX_KEY_HASH_SIZE = 1024;
    public static final PublicKeyHash NULL = new PublicKeyHash(new Multihash(Type.sha2_256, new byte[32]));

    public final Multihash hash;

    public PublicKeyHash(Multihash hash) {
        super(hash);
        if (! isSafe(hash))
            throw new IllegalStateException("Must use a safe hash for a public key!");
        this.hash = hash;
    }

    public static boolean isSafe(Multihash h) {
        return h.type == Type.sha2_256; // we can add other hashes later
    }

    @Override
    public byte[] toBytes() {
        return hash.toBytes();
    }

    @Override
    public String toString() {
        return hash.toString();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof PublicKeyHash && hash.equals(((PublicKeyHash) o).hash);
    }

    @Override
    public int hashCode() {
        return hash.hashCode();
    }

    @Override
    public CborObject toCbor() {
        return new CborObject.CborMerkleLink(hash);
    }

    public static PublicKeyHash decode(byte[] raw) {
        return new PublicKeyHash(Cid.cast(raw));
    }

    public static PublicKeyHash fromCbor(CborObject cbor) {
        if (! (cbor instanceof CborObject.CborMerkleLink))
            throw new IllegalStateException("Invalid cbor for PublicKeyHash! " + cbor);
        return new PublicKeyHash(((CborObject.CborMerkleLink) cbor).target);
    }

    public static PublicKeyHash fromString(String cid) {
        return new PublicKeyHash(Cid.decode(cid));
    }
}
