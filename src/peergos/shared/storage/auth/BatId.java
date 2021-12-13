package peergos.shared.storage.auth;

import peergos.shared.cbor.*;
import peergos.shared.io.ipfs.cid.*;
import peergos.shared.io.ipfs.multihash.*;

import java.util.*;

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
        return new BatId(new Cid(1, Cid.Codec.DagCbor, Multihash.Type.id, b.secret));
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
}
