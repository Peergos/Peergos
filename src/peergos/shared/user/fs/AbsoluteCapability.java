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
    public final SymmetricKey rBaseKey;
    public final Optional<SymmetricKey> wBaseKey;
    public final Optional<SecretSigningKey> signer;

    public AbsoluteCapability(PublicKeyHash owner,
                              PublicKeyHash writer,
                              byte[] mapKey,
                              SymmetricKey rBaseKey,
                              Optional<SymmetricKey> wBaseKey,
                              Optional<SecretSigningKey> signer) {
        if (mapKey.length != Location.MAP_KEY_LENGTH)
            throw new IllegalStateException("Invalid map key length: " + mapKey.length);
        this.owner = owner;
        this.writer = writer;
        this.mapKey = mapKey;
        this.rBaseKey = rBaseKey;
        this.wBaseKey = wBaseKey;
        this.signer = signer;
    }

    public AbsoluteCapability(PublicKeyHash owner, PublicKeyHash writer, byte[] mapKey, SymmetricKey rBaseKey) {
        this(owner, writer, mapKey, rBaseKey, Optional.empty(), Optional.empty());
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

    public WritableAbsoluteCapability toWritable(SymmetricKey writeBaseKey, SigningPrivateKeyAndPublicHash signer) {
        if (! signer.publicKeyHash.equals(writer))
            throw new IllegalStateException("Incorrect signing keyPair to make this capability writable!");
        return new WritableAbsoluteCapability(owner, writer, mapKey, rBaseKey, writeBaseKey, signer.secret);
    }

    public String toLink() {
        String encodedOwnerKey = Base58.encode(owner.serialize());
        String encodedWriterKey = Base58.encode(writer.serialize());
        String encodedMapKey = Base58.encode(mapKey);
        String encodedBaseKey = Base58.encode(rBaseKey.serialize());
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
        return new AbsoluteCapability(owner, writer, mapKey, baseKey, Optional.empty(), Optional.empty());
    }

    public AbsoluteCapability readOnly() {
        return new AbsoluteCapability(owner, writer, mapKey, rBaseKey, Optional.empty(), Optional.empty());
    }

    public AbsoluteCapability withBaseKey(SymmetricKey newBaseKey) {
        return new AbsoluteCapability(owner, writer, mapKey, newBaseKey, wBaseKey, signer);
    }

    public boolean isNull() {
        PublicKeyHash nullUser = PublicKeyHash.NULL;
        return nullUser.equals(owner) &&
                nullUser.equals(writer) &&
                Arrays.equals(getMapKey(), new byte[32]) &&
                rBaseKey.equals(SymmetricKey.createNull()) &&
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
        cbor.put("k", rBaseKey.toCbor());
        wBaseKey.ifPresent(wk -> cbor.put("b", wk.toCbor()));
        signer.ifPresent(secret -> cbor.put("s", secret.toCbor()));
        return CborObject.CborMap.build(cbor);
    }

    public static AbsoluteCapability fromCbor(Cborable cbor) {
        if (! (cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Incorrect cbor for AbsoluteCapability: " + cbor);
        CborObject.CborMap map = ((CborObject.CborMap) cbor);

        PublicKeyHash owner = PublicKeyHash.fromCbor(map.get("o"));
        PublicKeyHash writer = PublicKeyHash.fromCbor(map.get("w"));
        byte[] mapKey = ((CborObject.CborByteArray)map.get("m")).value;
        SymmetricKey baseKey = SymmetricKey.fromCbor(map.get("k"));
        Optional<SymmetricKey> writerBaseKey = Optional.ofNullable(map.get("b")).map(SymmetricKey::fromCbor);
        Optional<SecretSigningKey> signer = Optional.ofNullable(map.get("s"))
                .map(SecretSigningKey::fromCbor);
        if (writerBaseKey.isPresent() && signer.isPresent())
            return new WritableAbsoluteCapability(owner, writer, mapKey, baseKey, writerBaseKey.get(), signer.get());
        return new AbsoluteCapability(owner, writer, mapKey, baseKey, writerBaseKey, signer);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        AbsoluteCapability that = (AbsoluteCapability) o;
        return Objects.equals(owner, that.owner) &&
                Objects.equals(writer, that.writer) &&
                Arrays.equals(mapKey, that.mapKey) &&
                Objects.equals(rBaseKey, that.rBaseKey) &&
                Objects.equals(signer, that.signer);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(owner, writer, rBaseKey, signer);
        result = 31 * result + Arrays.hashCode(mapKey);
        return result;
    }
}
