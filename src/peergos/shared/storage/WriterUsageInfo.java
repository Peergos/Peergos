package peergos.shared.storage;

import jsinterop.annotations.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.hash.*;

import java.util.*;

/** The space cap on a writing space, and how much of it is used.
 *
 *  quota and used describe the writer's own subtree, and are only present if the writer itself is capped.
 *  available is the least space left under any cap that applies to the writer, including those of its ancestors.
 */
public class WriterUsageInfo implements Cborable {
    public final PublicKeyHash writer;
    public final Optional<Long> quota;
    public final long used;
    public final Optional<Long> available;

    public WriterUsageInfo(PublicKeyHash writer, Optional<Long> quota, long used, Optional<Long> available) {
        this.writer = writer;
        this.quota = quota;
        this.used = used;
        this.available = available;
    }

    @JsMethod
    public boolean hasQuota() {
        return quota.isPresent();
    }

    @JsMethod
    public double getQuotaBytes() {
        return quota.orElse(0L);
    }

    @JsMethod
    public double getUsedBytes() {
        return used;
    }

    @JsMethod
    public boolean hasAvailable() {
        return available.isPresent();
    }

    @JsMethod
    public double getAvailableBytes() {
        return available.orElse(0L);
    }

    @Override
    public CborObject toCbor() {
        Map<String, Cborable> props = new TreeMap<>();
        props.put("w", writer);
        quota.ifPresent(q -> props.put("q", new CborObject.CborLong(q)));
        props.put("u", new CborObject.CborLong(used));
        available.ifPresent(a -> props.put("a", new CborObject.CborLong(a)));
        return CborObject.CborMap.build(props);
    }

    public static WriterUsageInfo fromCbor(Cborable cbor) {
        if (! (cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Invalid cbor for WriterUsageInfo! " + cbor);
        CborObject.CborMap map = (CborObject.CborMap) cbor;
        PublicKeyHash writer = map.get("w", PublicKeyHash::fromCbor);
        Optional<Long> quota = map.getOptionalLong("q");
        long used = map.getLong("u");
        Optional<Long> available = map.getOptionalLong("a");
        return new WriterUsageInfo(writer, quota, used, available);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        WriterUsageInfo that = (WriterUsageInfo) o;
        return used == that.used && writer.equals(that.writer) && quota.equals(that.quota) && available.equals(that.available);
    }

    @Override
    public int hashCode() {
        return Objects.hash(writer, quota, used, available);
    }
}
