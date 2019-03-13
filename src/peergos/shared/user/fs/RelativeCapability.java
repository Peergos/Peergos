package peergos.shared.user.fs;

import jsinterop.annotations.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.crypto.symmetric.SymmetricKey;
import peergos.shared.util.*;

import java.util.*;

/** This provides a relative cryptographic capability (read only or read and write) for a file or folder.
 *
 *  Here, relative means that the holder has the owner and writer public key from another source, typically the parent
 *  folder or entry point. This also includes the relative link between the symmetric write base keys if they are different.
 */
public class RelativeCapability implements Cborable {
    // writer is only present when it is not implicit (an entry point, or a child link to a different writing key)
    public final Optional<PublicKeyHash> writer;
    private final byte[] mapKey;
    public final SymmetricKey rBaseKey;
    public final Optional<SymmetricLink> wBaseKeyLink;

    @JsConstructor
    public RelativeCapability(Optional<PublicKeyHash> writer,
                              byte[] mapKey,
                              SymmetricKey rBaseKey,
                              Optional<SymmetricLink> wBaseKeyLink) {
        this.writer = writer;
        if (mapKey.length != Location.MAP_KEY_LENGTH)
            throw new IllegalStateException("Invalid map key length: " + mapKey.length);
        this.mapKey = mapKey;
        this.rBaseKey = rBaseKey;
        this.wBaseKeyLink = wBaseKeyLink;
    }

    public RelativeCapability(byte[] mapKey, SymmetricKey rBaseKey, SymmetricLink wBaseKeyLink) {
        this(Optional.empty(), mapKey, rBaseKey, Optional.ofNullable(wBaseKeyLink));
    }

    @JsMethod
    public byte[] getMapKey() {
        return Arrays.copyOf(mapKey, mapKey.length);
    }

    public Location getLocation(PublicKeyHash owner, PublicKeyHash writer) {
        return new Location(owner, this.writer.orElse(writer), mapKey);
    }

    public SymmetricKey getWriteBaseKey(SymmetricKey sourceBaseKey) {
        return wBaseKeyLink.get().target(sourceBaseKey);
    }

    public AbsoluteCapability toAbsolute(AbsoluteCapability source) {
        Optional<SymmetricKey> wBaseKey = source.wBaseKey.flatMap(w -> wBaseKeyLink.map(link -> link.target(w)));
        PublicKeyHash writer = this.writer.orElse(source.writer);
        if (wBaseKey.isPresent())
            return new WritableAbsoluteCapability(source.owner, writer, mapKey, rBaseKey, wBaseKey.get());
        return new AbsoluteCapability(source.owner, writer, mapKey, rBaseKey, wBaseKey);
    }

    public RelativeCapability withBaseKey(SymmetricKey newReadBaseKey) {
        return new RelativeCapability(writer, mapKey, newReadBaseKey, wBaseKeyLink);
    }

    public RelativeCapability withWritingKey(PublicKeyHash writingKey) {
        return new RelativeCapability(Optional.of(writingKey), mapKey, rBaseKey, wBaseKeyLink);
    }

    public static RelativeCapability buildSubsequentChunk(byte[] mapkey, SymmetricKey baseKey) {
        return new RelativeCapability(Optional.empty(), mapkey, baseKey, Optional.empty());
    }

    @Override
    public CborObject toCbor() {
        Map<String, CborObject> cbor = new TreeMap<>();
        writer.ifPresent(w -> cbor.put("w", w.toCbor()));
        cbor.put("m", new CborObject.CborByteArray(mapKey));
        cbor.put("k", rBaseKey.toCbor());
        wBaseKeyLink.ifPresent(w -> cbor.put("l", w.toCbor()));
        return CborObject.CborMap.build(cbor);
    }

    public static RelativeCapability fromByteArray(byte[] raw) {
        return fromCbor(CborObject.fromByteArray(raw));
    }

    public static RelativeCapability fromCbor(Cborable cbor) {
        if (! (cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Incorrect cbor for RelativeCapability: " + cbor);
        CborObject.CborMap map = ((CborObject.CborMap) cbor);

        Optional<PublicKeyHash> writer = Optional.ofNullable(map.get("w"))
                .map(PublicKeyHash::fromCbor);
        byte[] mapKey = ((CborObject.CborByteArray)map.get("m")).value;
        SymmetricKey baseKey = SymmetricKey.fromCbor(map.get("k"));
        Optional<SymmetricLink> writerLink = Optional.ofNullable(map.get("l")).map(SymmetricLink::fromCbor);
        return new RelativeCapability(writer, mapKey, baseKey, writerLink);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RelativeCapability that = (RelativeCapability) o;
        return Objects.equals(writer, that.writer) &&
                Arrays.equals(mapKey, that.mapKey) &&
                Objects.equals(rBaseKey, that.rBaseKey);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(writer, rBaseKey);
        result = 31 * result + Arrays.hashCode(mapKey);
        return result;
    }

    @Override
    public String toString() {
        return ArrayOps.bytesToHex(getMapKey());
    }
}
