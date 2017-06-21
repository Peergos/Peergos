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

public class FilePointer implements Cborable {
    public final Location location;
    public final SymmetricKey baseKey;
    public final Optional<SecretSigningKey> writer;

    @JsConstructor
    public FilePointer(Location location, Optional<SecretSigningKey> writer, SymmetricKey baseKey) {
        this.location = location;
        this.baseKey = baseKey;
        this.writer = writer;
    }

    public FilePointer(PublicKeyHash owner, PublicKeyHash writer, byte[] mapKey, SymmetricKey baseKey) {
        this(new Location(owner, writer, mapKey), Optional.empty(), baseKey);
    }

    public FilePointer(PublicKeyHash owner, SigningPrivateKeyAndPublicHash writer, byte[] mapKey, SymmetricKey baseKey) {
        this(new Location(owner, writer.publicKeyHash, mapKey), Optional.of(writer.secret), baseKey);
    }

    public Location getLocation() {
        return location;
    }

    public SigningPrivateKeyAndPublicHash signer() {
        if (! writer.isPresent())
            throw new IllegalStateException("Can't get signer for a read only pointer!");
        return new SigningPrivateKeyAndPublicHash(location.writer, writer.get());
    }

    public FilePointer withBaseKey(SymmetricKey newBaseKey) {
        return new FilePointer(location, writer, newBaseKey);
    }

    public FilePointer withWritingKey(PublicKeyHash writingKey) {
        return new FilePointer(location.withWriter(writingKey), Optional.empty(), baseKey);
    }

    @Override
    public CborObject toCbor() {
        Map<String, CborObject> cbor = new TreeMap<>();
        cbor.put("l", location.toCbor());
        cbor.put("k", baseKey.toCbor());
        writer.ifPresent(secret -> cbor.put("s", secret.toCbor()));
        return CborObject.CborMap.build(cbor);
    }

    public static FilePointer fromByteArray(byte[] raw) {
        return fromCbor(CborObject.fromByteArray(raw));
    }

    public static FilePointer fromCbor(CborObject cbor) {
        if (! (cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Incorrect cbor for FilePointer: " + cbor);
        SortedMap<CborObject, CborObject> map = ((CborObject.CborMap) cbor).values;
        Location loc = Location.fromCbor(map.get(new CborObject.CborString("l")));
        SymmetricKey baseKey = SymmetricKey.fromCbor(map.get(new CborObject.CborString("k")));
        CborObject.CborString secretLabel = new CborObject.CborString("s");
        Optional<SecretSigningKey> writer = map.containsKey(secretLabel) ? Optional.of(SecretSigningKey.fromCbor(map.get(secretLabel))) : Optional.empty();
        return new FilePointer(loc, writer, baseKey);
    }

    public FilePointer readOnly() {
        if (!isWritable())
            return this;
        return new FilePointer(this.location, Optional.empty(), this.baseKey);
    }

    public boolean isWritable() {
        return writer.isPresent();
    }

    public String toLink() {
        return "#" + Base58.encode(location.writer.serialize()) + "/" + Base58.encode(location.getMapKey()) + "/" + Base58.encode(baseKey.serialize());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        FilePointer that = (FilePointer) o;

        if (location != null ? !location.equals(that.location) : that.location != null) return false;
        return baseKey != null ? baseKey.equals(that.baseKey) : that.baseKey == null;

    }

    @Override
    public int hashCode() {
        int result = location != null ? location.hashCode() : 0;
        result = 31 * result + (baseKey != null ? baseKey.hashCode() : 0);
        return result;
    }

    @Override
    public String toString() {
        return ArrayOps.bytesToHex(location.getMapKey());
    }

    public boolean isNull() {
        PublicSigningKey nullUser = PublicSigningKey.createNull();
        return nullUser.equals(location.owner) &&
                nullUser.equals(location.writer) &&
                Arrays.equals(location.getMapKey(), new byte[32]) &&
                baseKey.equals(SymmetricKey.createNull());
    }

    public static FilePointer fromLink(String keysString) {
        if (keysString.startsWith("#"))
            keysString = keysString.substring(1);
        String[] split = keysString.split("/");
        PublicKeyHash owner = PublicKeyHash.NULL;
        PublicKeyHash writer = PublicKeyHash.fromCbor(CborObject.fromByteArray(Base58.decode(split[0])));
        byte[] mapKey = Base58.decode(split[1]);
        SymmetricKey baseKey = SymmetricKey.fromByteArray(Base58.decode(split[2]));
        return new FilePointer(owner, writer, mapKey, baseKey);
    }

    public static FilePointer createNull() {
        return new FilePointer(PublicKeyHash.NULL, PublicKeyHash.NULL, new byte[32], SymmetricKey.createNull());
    }
}
