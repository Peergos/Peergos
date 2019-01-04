package peergos.shared.user.fs;

import jsinterop.annotations.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.crypto.symmetric.SymmetricKey;
import peergos.shared.util.*;

import java.util.*;

/** This provides a relative cryptographic capability for reading a file or folder. It is assumed the holder also has
 * the owner key, and possibly the writer key (if absent here) externally.
 *
 */
public class RelativeCapability implements Cborable {
    // writer is only present when it is not implicit (an entry point, or a child link to a different writing key)
    public final Optional<PublicKeyHash> writer;
    private final byte[] mapKey;
    public final SymmetricKey rBaseKey;
    public final Optional<SymmetricLink> wBaseKeyLink;
    public final Optional<SecretSigningKey> signer;

    @JsConstructor
    public RelativeCapability(Optional<PublicKeyHash> writer,
                              byte[] mapKey,
                              SymmetricKey rBaseKey,
                              Optional<SymmetricLink> wBaseKeyLink,
                              Optional<SecretSigningKey> signer) {
        this.writer = writer;
        if (mapKey.length != Location.MAP_KEY_LENGTH)
            throw new IllegalStateException("Invalid map key length: " + mapKey.length);
        this.mapKey = mapKey;
        this.rBaseKey = rBaseKey;
        this.wBaseKeyLink = wBaseKeyLink;
        this.signer = signer;
    }

    public RelativeCapability(byte[] mapKey, SymmetricKey rBaseKey, SymmetricLink wBaseKeyLink) {
        this(Optional.empty(), mapKey, rBaseKey, Optional.ofNullable(wBaseKeyLink), Optional.empty());
    }

    @JsMethod
    public byte[] getMapKey() {
        return Arrays.copyOf(mapKey, mapKey.length);
    }

    public Location getLocation(PublicKeyHash owner, PublicKeyHash writer) {
        return new Location(owner, this.writer.orElse(writer), mapKey);
    }

    public SigningPrivateKeyAndPublicHash signer(PublicKeyHash writer) {
        if (! signer.isPresent())
            throw new IllegalStateException("Can't get signer for a read only pointer!");
        return new SigningPrivateKeyAndPublicHash(this.writer.orElse(writer), signer.get());
    }

    public SymmetricKey getWriteBaseKey(SymmetricKey sourceBaseKey) {
        return wBaseKeyLink.get().target(sourceBaseKey);
    }

    public AbsoluteCapability toAbsolute(AbsoluteCapability source) {
        Optional<SymmetricKey> wBaseKey = source.wBaseKey.flatMap(w -> wBaseKeyLink.map(link -> link.target(w)));
        PublicKeyHash writer = this.writer.orElse(source.writer);
        if (wBaseKey.isPresent() && source.signer.isPresent())
            return new WritableAbsoluteCapability(source.owner, writer, mapKey, rBaseKey, wBaseKey.get(),
                    signer.orElse(source.signer.get()));
        return new AbsoluteCapability(source.owner, writer, mapKey, rBaseKey,
                wBaseKey, signer);
    }

    public RelativeCapability withBaseKey(SymmetricKey newReadBaseKey) {
        return new RelativeCapability(writer, mapKey, newReadBaseKey, wBaseKeyLink, signer);
    }

    public RelativeCapability withWritingKey(PublicKeyHash writingKey) {
        return new RelativeCapability(Optional.of(writingKey), mapKey, rBaseKey, wBaseKeyLink, Optional.empty());
    }

    @Override
    public CborObject toCbor() {
        Map<String, CborObject> cbor = new TreeMap<>();
        writer.ifPresent(w -> cbor.put("w", w.toCbor()));
        cbor.put("m", new CborObject.CborByteArray(mapKey));
        cbor.put("k", rBaseKey.toCbor());
        wBaseKeyLink.ifPresent(w -> cbor.put("l", w.toCbor()));
        signer.ifPresent(secret -> cbor.put("s", secret.toCbor()));
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
        Optional<SecretSigningKey> signer = Optional.ofNullable(map.get("s"))
                .map(SecretSigningKey::fromCbor);
        Optional<SymmetricLink> writerLink = Optional.ofNullable(map.get("l")).map(SymmetricLink::fromCbor);
        return new RelativeCapability(writer, mapKey, baseKey, writerLink, signer);
    }

    public RelativeCapability readOnly() {
        if (!isWritable())
            return this;
        return new RelativeCapability(writer, mapKey, rBaseKey, wBaseKeyLink, Optional.empty());
    }

    public boolean isWritable() {
        return signer.isPresent();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RelativeCapability that = (RelativeCapability) o;
        return Objects.equals(writer, that.writer) &&
                Arrays.equals(mapKey, that.mapKey) &&
                Objects.equals(rBaseKey, that.rBaseKey) &&
                Objects.equals(signer, that.signer);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(writer, rBaseKey, signer);
        result = 31 * result + Arrays.hashCode(mapKey);
        return result;
    }

    @Override
    public String toString() {
        return ArrayOps.bytesToHex(getMapKey());
    }
}
