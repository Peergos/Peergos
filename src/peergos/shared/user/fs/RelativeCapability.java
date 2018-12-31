package peergos.shared.user.fs;

import jsinterop.annotations.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.crypto.symmetric.SymmetricKey;
import peergos.shared.io.ipfs.multibase.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** This provides a relative crytographic capability for reading a file or folder. It is assumed the holder also has
 * the owner key, and possibly the writer key (if absent here) externally.
 *
 */
public class RelativeCapability implements Cborable {
    // writer is only present when it is not implicit (an entry point, or a child link to a different writing key)
    public final Optional<PublicKeyHash> writer;
    private final byte[] mapKey;
    public final SymmetricKey baseKey;
    public final Optional<SecretSigningKey> signer;

    @JsConstructor
    public RelativeCapability(Optional<PublicKeyHash> writer, byte[] mapKey, SymmetricKey baseKey, Optional<SecretSigningKey> signer) {
        this.writer = writer;
        if (mapKey.length != Location.MAP_KEY_LENGTH)
            throw new IllegalStateException("Invalid map key length: " + mapKey.length);
        this.mapKey = mapKey;
        this.baseKey = baseKey;
        this.signer = signer;
    }

    public RelativeCapability(byte[] mapKey, SymmetricKey baseKey) {
        this(Optional.empty(), mapKey, baseKey, Optional.empty());
    }

    public RelativeCapability(PublicKeyHash writer, byte[] mapKey, SymmetricKey baseKey, Optional<SecretSigningKey> signer) {
        this(Optional.of(writer), mapKey, baseKey, signer);
    }

    public RelativeCapability(PublicKeyHash signer, byte[] mapKey, SymmetricKey baseKey) {
        this(Optional.of(signer), mapKey, baseKey, Optional.empty());
    }

    public RelativeCapability(SigningPrivateKeyAndPublicHash signer, byte[] mapKey, SymmetricKey baseKey) {
        this(Optional.of(signer.publicKeyHash), mapKey, baseKey, Optional.of(signer.secret));
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

    public AbsoluteCapability toAbsolute(AbsoluteCapability source) {
        return new AbsoluteCapability(source.owner, writer.orElse(source.writer), mapKey, baseKey, signer);
    }

    public RelativeCapability withBaseKey(SymmetricKey newBaseKey) {
        return new RelativeCapability(writer, mapKey, newBaseKey, signer);
    }

    public RelativeCapability withWritingKey(PublicKeyHash writingKey) {
        return new RelativeCapability(Optional.of(writingKey), mapKey, baseKey, Optional.empty());
    }

    @Override
    public CborObject toCbor() {
        Map<String, CborObject> cbor = new TreeMap<>();
        writer.ifPresent(w -> cbor.put("w", w.toCbor()));
        cbor.put("m", new CborObject.CborByteArray(mapKey));
        cbor.put("k", baseKey.toCbor());
        signer.ifPresent(secret -> cbor.put("s", secret.toCbor()));
        return CborObject.CborMap.build(cbor);
    }

    public static RelativeCapability fromByteArray(byte[] raw) {
        return fromCbor(CborObject.fromByteArray(raw));
    }

    public static RelativeCapability fromCbor(Cborable cbor) {
        if (! (cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Incorrect cbor for RelativeCapability: " + cbor);
        SortedMap<CborObject, ? extends Cborable> map = ((CborObject.CborMap) cbor).values;

        Optional<PublicKeyHash> writer = Optional.ofNullable(map.get(new CborObject.CborString("w")))
                .map(PublicKeyHash::fromCbor);
        byte[] mapKey = ((CborObject.CborByteArray)map.get(new CborObject.CborString("m"))).value;
        SymmetricKey baseKey = SymmetricKey.fromCbor(map.get(new CborObject.CborString("k")));
        Optional<SecretSigningKey> signer = Optional.ofNullable(map.get(new CborObject.CborString("s")))
                .map(SecretSigningKey::fromCbor);
        return new RelativeCapability(writer, mapKey, baseKey, signer);
    }

    public RelativeCapability readOnly() {
        if (!isWritable())
            return this;
        return new RelativeCapability(writer, mapKey, baseKey, Optional.empty());
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
                Objects.equals(baseKey, that.baseKey) &&
                Objects.equals(signer, that.signer);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(writer, baseKey, signer);
        result = 31 * result + Arrays.hashCode(mapKey);
        return result;
    }

    @Override
    public String toString() {
        return ArrayOps.bytesToHex(getMapKey());
    }
}
