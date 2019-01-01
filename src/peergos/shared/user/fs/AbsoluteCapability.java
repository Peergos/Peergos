package peergos.shared.user.fs;

import jsinterop.annotations.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.crypto.symmetric.*;
import peergos.shared.io.ipfs.multibase.*;

import java.util.*;
import java.util.stream.*;

/** This a complete cryptographic capability to read a file or folder
 *
 */
public class AbsoluteCapability implements Cborable {

    public final PublicKeyHash owner, writer;
    private final byte[] mapKey;
    public final SymmetricKey baseKey;
    public final Optional<SecretSigningKey> signer;

    public AbsoluteCapability(PublicKeyHash owner, PublicKeyHash writer, byte[] mapKey, SymmetricKey baseKey, Optional<SecretSigningKey> signer) {
        if (mapKey.length != Location.MAP_KEY_LENGTH)
            throw new IllegalStateException("Invalid map key length: " + mapKey.length);
        this.owner = owner;
        this.writer = writer;
        this.mapKey = mapKey;
        this.baseKey = baseKey;
        this.signer = signer;
    }

    public AbsoluteCapability(PublicKeyHash owner, PublicKeyHash writer, byte[] mapKey, SymmetricKey baseKey) {
        this(owner, writer, mapKey, baseKey, Optional.empty());
    }

    @JsMethod
    public byte[] getMapKey() {
        return Arrays.copyOf(mapKey, mapKey.length);
    }

    public Location getLocation() {
        return new Location(owner, writer, mapKey);
    }

    public SigningPrivateKeyAndPublicHash getSigningPair() {
        if (! signer.isPresent())
            throw new IllegalStateException("Can't get a signing key pair from a read only capability!");
        return new SigningPrivateKeyAndPublicHash(writer, signer.get());
    }

    public boolean isWritable() {
        return signer.isPresent();
    }

    public RelativeCapability relativise(AbsoluteCapability descendant) {
        if (! Objects.equals(owner, descendant.owner))
            throw new IllegalStateException("Files with different owners can't be descendant of each other!");
        if (Objects.equals(writer, descendant.writer))
            return new RelativeCapability(Optional.empty(), descendant.getMapKey(), descendant.baseKey, descendant.signer);
        return new RelativeCapability(Optional.of(descendant.writer), descendant.getMapKey(), descendant.baseKey, descendant.signer);
    }

    public WritableAbsoluteCapability toWritable(SigningPrivateKeyAndPublicHash signer) {
        if (! signer.publicKeyHash.equals(writer))
            throw new IllegalStateException("Incorrect signing keyPair to make this capability writable!");
        return new WritableAbsoluteCapability(owner, writer, mapKey, baseKey, signer.secret);
    }

    public String toLink() {
        String encodedOwnerKey = Base58.encode(owner.serialize());
        String encodedWriterKey = Base58.encode(writer.serialize());
        String encodedMapKey = Base58.encode(mapKey);
        String encodedBaseKey = Base58.encode(baseKey.serialize());
        return Stream.of(encodedOwnerKey, encodedWriterKey, encodedMapKey, encodedBaseKey)
                .collect(Collectors.joining("/", "#", ""));
    }

    public static AbsoluteCapability fromLink(String keysString) {
        if (keysString.startsWith("#"))
            keysString = keysString.substring(1);

        String[] split = keysString.split("/");
        if (split.length != 4)
            throw new IllegalStateException("Invalid public link "+ keysString);

        PublicKeyHash owner = PublicKeyHash.fromCbor(CborObject.fromByteArray(Base58.decode(split[0])));
        PublicKeyHash writer = PublicKeyHash.fromCbor(CborObject.fromByteArray(Base58.decode(split[1])));
        byte[] mapKey = Base58.decode(split[2]);
        SymmetricKey baseKey = SymmetricKey.fromByteArray(Base58.decode(split[3]));
        return new AbsoluteCapability(owner, writer, mapKey, baseKey, Optional.empty());
    }

    public AbsoluteCapability readOnly() {
        if (!isWritable())
            return this;
        return new AbsoluteCapability(owner, writer, mapKey, baseKey, Optional.empty());
    }

    public AbsoluteCapability withBaseKey(SymmetricKey newBaseKey) {
        return new AbsoluteCapability(owner, writer, mapKey, newBaseKey, signer);
    }

    public boolean isNull() {
        PublicKeyHash nullUser = PublicKeyHash.NULL;
        return nullUser.equals(owner) &&
                nullUser.equals(writer) &&
                Arrays.equals(getMapKey(), new byte[32]) &&
                baseKey.equals(SymmetricKey.createNull()) &&
                ! signer.isPresent();
    }

    public static AbsoluteCapability createNull() {
        return new AbsoluteCapability(PublicKeyHash.NULL, PublicKeyHash.NULL, new byte[32], SymmetricKey.createNull());
    }

    @Override
    public CborObject toCbor() {
        Map<String, CborObject> cbor = new TreeMap<>();
        cbor.put("o", owner.toCbor());
        cbor.put("w", writer.toCbor());
        cbor.put("m", new CborObject.CborByteArray(mapKey));
        cbor.put("k", baseKey.toCbor());
        signer.ifPresent(secret -> cbor.put("s", secret.toCbor()));
        return CborObject.CborMap.build(cbor);
    }

    public static AbsoluteCapability fromCbor(Cborable cbor) {
        if (! (cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Incorrect cbor for AbsoluteCapability: " + cbor);
        SortedMap<CborObject, ? extends Cborable> map = ((CborObject.CborMap) cbor).values;

        PublicKeyHash owner = PublicKeyHash.fromCbor(map.get(new CborObject.CborString("o")));
        PublicKeyHash writer = PublicKeyHash.fromCbor(map.get(new CborObject.CborString("w")));
        byte[] mapKey = ((CborObject.CborByteArray)map.get(new CborObject.CborString("m"))).value;
        SymmetricKey baseKey = SymmetricKey.fromCbor(map.get(new CborObject.CborString("k")));
        Optional<SecretSigningKey> signer = Optional.ofNullable(map.get(new CborObject.CborString("s")))
                .map(SecretSigningKey::fromCbor);
        return new AbsoluteCapability(owner, writer, mapKey, baseKey, signer);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        AbsoluteCapability that = (AbsoluteCapability) o;
        return Objects.equals(owner, that.owner) &&
                Objects.equals(writer, that.writer) &&
                Arrays.equals(mapKey, that.mapKey) &&
                Objects.equals(baseKey, that.baseKey) &&
                Objects.equals(signer, that.signer);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(owner, writer, baseKey, signer);
        result = 31 * result + Arrays.hashCode(mapKey);
        return result;
    }
}
