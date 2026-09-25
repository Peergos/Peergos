package peergos.shared.storage;

import peergos.shared.cbor.*;
import peergos.shared.crypto.hash.*;

import java.util.*;

/** A request, signed by an owner's identity key, to cap the space used by a writing space and all the writing spaces
 *  it owns. An empty size removes the cap.
 */
public class WriterQuotaRequest implements Cborable {
    public final PublicKeyHash owner, writer;
    public final Optional<Long> bytes;
    public final long utcMillis;

    public WriterQuotaRequest(PublicKeyHash owner, PublicKeyHash writer, Optional<Long> bytes, long utcMillis) {
        if (bytes.isPresent() && bytes.get() < 0)
            throw new IllegalArgumentException("Negative quota!");
        this.owner = owner;
        this.writer = writer;
        this.bytes = bytes;
        this.utcMillis = utcMillis;
    }

    @Override
    public CborObject toCbor() {
        Map<String, Cborable> props = new TreeMap<>();
        props.put("o", owner);
        props.put("w", writer);
        bytes.ifPresent(b -> props.put("s", new CborObject.CborLong(b)));
        props.put("t", new CborObject.CborLong(utcMillis));
        return CborObject.CborMap.build(props);
    }

    public static WriterQuotaRequest fromCbor(Cborable cbor) {
        if (! (cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Invalid cbor for WriterQuotaRequest! " + cbor);
        CborObject.CborMap map = (CborObject.CborMap) cbor;
        PublicKeyHash owner = map.get("o", PublicKeyHash::fromCbor);
        PublicKeyHash writer = map.get("w", PublicKeyHash::fromCbor);
        Optional<Long> bytes = map.getOptionalLong("s");
        long time = map.getLong("t");
        return new WriterQuotaRequest(owner, writer, bytes, time);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        WriterQuotaRequest that = (WriterQuotaRequest) o;
        return utcMillis == that.utcMillis && owner.equals(that.owner) && writer.equals(that.writer) && bytes.equals(that.bytes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(owner, writer, bytes, utcMillis);
    }
}
