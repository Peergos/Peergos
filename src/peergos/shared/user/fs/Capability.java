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

public class Capability implements Cborable {
    // writer is only present when it is not implicit (an entry point, or a child link to a different writing key)
    public final Optional<PublicKeyHash> writer;
    private final byte[] mapKey;
    public final SymmetricKey baseKey;
    public final Optional<SecretSigningKey> signer;

    @JsConstructor
    public Capability(Optional<PublicKeyHash> writer, byte[] mapKey, SymmetricKey baseKey, Optional<SecretSigningKey> signer) {
        this.writer = writer;
        if (mapKey.length != Location.MAP_KEY_LENGTH)
            throw new IllegalStateException("Invalid map key length: " + mapKey.length);
        this.mapKey = mapKey;
        this.baseKey = baseKey;
        this.signer = signer;
    }

    public Capability(byte[] mapKey, SymmetricKey baseKey) {
        this(Optional.empty(), mapKey, baseKey, Optional.empty());
    }

    public Capability(PublicKeyHash writer, byte[] mapKey, SymmetricKey baseKey, Optional<SecretSigningKey> signer) {
        this(Optional.of(writer), mapKey, baseKey, signer);
    }

    public Capability(PublicKeyHash signer, byte[] mapKey, SymmetricKey baseKey) {
        this(Optional.of(signer), mapKey, baseKey, Optional.empty());
    }

    public Capability(SigningPrivateKeyAndPublicHash signer, byte[] mapKey, SymmetricKey baseKey) {
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

    public Capability withBaseKey(SymmetricKey newBaseKey) {
        return new Capability(writer, mapKey, newBaseKey, signer);
    }

    public Capability withWritingKey(PublicKeyHash writingKey) {
        return new Capability(Optional.of(writingKey), mapKey, baseKey, Optional.empty());
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

    public static Capability fromByteArray(byte[] raw) {
        return fromCbor(CborObject.fromByteArray(raw));
    }

    public static Capability fromCbor(Cborable cbor) {
        if (! (cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Incorrect cbor for Capability: " + cbor);
        SortedMap<CborObject, ? extends Cborable> map = ((CborObject.CborMap) cbor).values;

        Optional<PublicKeyHash> writer = Optional.ofNullable(map.get(new CborObject.CborString("w")))
                .map(PublicKeyHash::fromCbor);
        byte[] mapKey = ((CborObject.CborByteArray)map.get(new CborObject.CborString("m"))).value;
        SymmetricKey baseKey = SymmetricKey.fromCbor(map.get(new CborObject.CborString("k")));
        Optional<SecretSigningKey> signer = Optional.ofNullable(map.get(new CborObject.CborString("s")))
                .map(SecretSigningKey::fromCbor);
        return new Capability(writer, mapKey, baseKey, signer);
    }

    public Capability readOnly() {
        if (!isWritable())
            return this;
        return new Capability(writer, mapKey, baseKey, Optional.empty());
    }

    public boolean isWritable() {
        return signer.isPresent();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Capability that = (Capability) o;
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

    public boolean isNull() {
        PublicKeyHash nullUser = PublicKeyHash.NULL;
        return writer.map(w -> nullUser.equals(w)).orElse(true) &&
                Arrays.equals(getMapKey(), new byte[32]) &&
                baseKey.equals(SymmetricKey.createNull());
    }

    public String toLink(PublicKeyHash owner, PublicKeyHash writer) {
        String encodedOwnerKey = Base58.encode(owner.serialize());
        String encodedWriterKey = Base58.encode(writer.serialize());
        String encodedMapKey = Base58.encode(getMapKey());
        String encodedBaseKey = Base58.encode(baseKey.serialize());
        return Stream.of(encodedOwnerKey, encodedWriterKey, encodedMapKey, encodedBaseKey)
                .collect(Collectors.joining("/", "#", ""));
    }

    public static Capability fromLink(String keysString) {
        if (keysString.startsWith("#"))
            keysString = keysString.substring(1);

        String[] split = keysString.split("/");
        if (split.length != 4)
            throw new IllegalStateException("Invalid public link "+ keysString);

        PublicKeyHash writer = PublicKeyHash.fromCbor(CborObject.fromByteArray(Base58.decode(split[1])));
        byte[] mapKey = Base58.decode(split[2]);
        SymmetricKey baseKey = SymmetricKey.fromByteArray(Base58.decode(split[3]));
        return new Capability(writer, mapKey, baseKey);
    }

    public static PublicKeyHash parseOwner(String keysString) {
        if (keysString.startsWith("#"))
            keysString = keysString.substring(1);
        String[] split = keysString.split("/");
        if (split.length != 4)
            throw new IllegalStateException("Invalid public link "+ keysString);

        return PublicKeyHash.fromCbor(CborObject.fromByteArray(Base58.decode(split[0])));
    }

    public static Capability createNull() {
        return new Capability(PublicKeyHash.NULL, new byte[32], SymmetricKey.createNull());
    }
}
