package peergos.shared.routing;

import peergos.shared.cbor.*;
import peergos.shared.crypto.asymmetric.*;

import java.util.*;
import java.util.concurrent.*;

/** A routing record: a body signed under its own context. Anything outside the body, an admission or a proof of
 *  work, is not part of the record's identity, so it can change without changing the record's hash.
 */
public abstract class SignedRecord implements Cborable {
    public final byte[] signature;

    protected SignedRecord(byte[] signature) {
        this.signature = signature;
    }

    public abstract CborObject.CborMap body();

    public abstract String context();

    public abstract PublicSigningKey signer();

    /** The keys in the body, which the size bound excludes. */
    protected abstract List<? extends Cborable> keys();

    protected abstract void checkFields();

    public final void checkBounds() {
        checkFields();
        Bounds.checkBody(body(), keys());
    }

    /** The bytes a record's hash is over: its body and signature. */
    public byte[] signedBytes() {
        return new CborObject.CborList(Arrays.asList(body(), new CborObject.CborByteArray(signature))).toByteArray();
    }

    public CompletableFuture<Boolean> verifySignature() {
        return Signing.verify(signer(), context(), body().toByteArray(), signature);
    }

    protected SortedMap<String, Cborable> envelope() {
        SortedMap<String, Cborable> m = new TreeMap<>();
        m.put("b", body());
        m.put("s", new CborObject.CborByteArray(signature));
        return m;
    }

    @Override
    public CborObject toCbor() {
        return CborObject.CborMap.build(envelope());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        return Arrays.equals(signedBytes(), ((SignedRecord) o).signedBytes());
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(signedBytes());
    }
}
