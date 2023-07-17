package peergos.shared.storage.auth;

import peergos.shared.cbor.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.Cid;
import peergos.shared.io.ipfs.Multihash;

import java.util.*;
import java.util.concurrent.*;

public class BatId implements Cborable {

    public final Cid id;

    public BatId(Cid id) {
        this.id = id;
    }

    public boolean isInline() {
        return id.isIdentity();
    }

    public Optional<Bat> getInline() {
        if (id.isIdentity())
            return Optional.of(new Bat(id.getHash()));
        return Optional.empty();
    }

    public static BatId inline(Bat b) {
        return new BatId(new Cid(1, Cid.Codec.Raw, Multihash.Type.id, b.secret));
    }

    public static CompletableFuture<BatId> sha256(Bat b, Hasher h) {
        return h.sha256(b.secret)
                .thenApply(hash -> new BatId(new Cid(1, Cid.Codec.Raw, Multihash.Type.sha2_256, hash)));
    }

    @Override
    public CborObject toCbor() {
        return new CborObject.CborByteArray(id.toBytes());
    }

    public static BatId fromCbor(Cborable cbor) {
        if (! (cbor instanceof CborObject.CborByteArray))
            throw new IllegalStateException("Incorrect cbor for BatId: " + cbor);
        return new BatId(Cid.cast(((CborObject.CborByteArray) cbor).value));
    }

    @Override
    public String toString() {
        return id.toString();
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (! (o instanceof BatId)) return false;
        return id.equals(((BatId) o).id);
    }
}
