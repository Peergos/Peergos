package peergos.shared.user.fs;

import jsinterop.annotations.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.crypto.symmetric.*;
import peergos.shared.io.ipfs.bases.*;
import peergos.shared.storage.auth.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.stream.*;

/** This a complete cryptographic capability to read a file or folder
 *
 */
public class AbsoluteCapability implements Cborable {

    public final PublicKeyHash owner, writer;
    private final byte[] mapKey;
    public final Optional<Bat> bat; // Only absent on legacy data
    public final SymmetricKey rBaseKey;
    public final Optional<SymmetricKey> wBaseKey;

    public AbsoluteCapability(PublicKeyHash owner,
                              PublicKeyHash writer,
                              byte[] mapKey,
                              Optional<Bat> bat,
                              SymmetricKey rBaseKey,
                              Optional<SymmetricKey> wBaseKey) {
        if (mapKey.length != RelativeCapability.MAP_KEY_LENGTH)
            throw new IllegalStateException("Invalid map key length: " + mapKey.length);
        this.owner = owner;
        this.writer = writer;
        this.mapKey = mapKey;
        this.bat = bat;
        this.rBaseKey = rBaseKey;
        this.wBaseKey = wBaseKey;
    }

    public AbsoluteCapability(PublicKeyHash owner, PublicKeyHash writer, byte[] mapKey, Optional<Bat> bat, SymmetricKey rBaseKey) {
        this(owner, writer, mapKey, bat, rBaseKey, Optional.empty());
    }

    @JsMethod
    public byte[] getMapKey() {
        return Arrays.copyOf(mapKey, mapKey.length);
    }

    public Location getLocation() {
        return new Location(owner, writer, mapKey);
    }

    @JsMethod
    public boolean isWritable() {
        return wBaseKey.isPresent();
    }

    public WritableAbsoluteCapability toWritable(SymmetricKey writeBaseKey) {
        return new WritableAbsoluteCapability(owner, writer, mapKey, bat, rBaseKey, writeBaseKey);
    }

    /*  Return a capability link of the form #$owner/$writer/$mapkey+$bat/$baseKey
     */
    public String toLink() {
        String encodedOwnerKey = Base58.encode(owner.serialize());
        String encodedWriterKey = Base58.encode(writer.serialize());
        String encodedMapKeyAndBat = Base58.encode(ArrayOps.concat(mapKey, bat.map(Bat::serialize).orElse(new byte[0])));
        String encodedBaseKey = Base58.encode(rBaseKey.serialize());
        return Stream.of(encodedOwnerKey, encodedWriterKey, encodedMapKeyAndBat, encodedBaseKey)
                .collect(Collectors.joining("/", "#", ""));
    }

    public static AbsoluteCapability fromLink(String keysString) {
        if (keysString.startsWith("#"))
            keysString = keysString.substring(1);

        String[] split = keysString.split("/");
        if (split.length == 4 || split.length == 5) {
            PublicKeyHash owner = PublicKeyHash.fromCbor(CborObject.fromByteArray(Base58.decode(split[0])));
            PublicKeyHash writer = PublicKeyHash.fromCbor(CborObject.fromByteArray(Base58.decode(split[1])));
            byte[] mapKeyAndBat = Base58.decode(split[2]);
            Optional<Bat> bat = mapKeyAndBat.length == 32 ?
                    Optional.empty() :
                    Optional.of(Bat.fromCbor(CborObject.fromByteArray(Arrays.copyOfRange(mapKeyAndBat, 32, mapKeyAndBat.length))));
            byte[] mapKey = Arrays.copyOfRange(mapKeyAndBat, 0, 32);
            SymmetricKey baseKey = SymmetricKey.fromByteArray(Base58.decode(split[3]));
            if (split.length == 4)
                return new AbsoluteCapability(owner, writer, mapKey, bat, baseKey, Optional.empty());
            SymmetricKey baseWKey = SymmetricKey.fromByteArray(Base58.decode(split[4]));
            return new WritableAbsoluteCapability(owner, writer, mapKey, bat, baseKey, baseWKey);
        } else
            throw new IllegalStateException("Invalid public link "+ keysString);
    }

    public static AbsoluteCapability build(Location loc, Optional<Bat> bat, SymmetricKey key) {
        return new AbsoluteCapability(loc.owner, loc.writer, loc.getMapKey(), bat, key);
    }

    public AbsoluteCapability readOnly() {
        return new AbsoluteCapability(owner, writer, mapKey, bat, rBaseKey, Optional.empty());
    }

    public AbsoluteCapability withMapKey(byte[] newMapKey, Optional<Bat> newBat) {
        return new AbsoluteCapability(owner, writer, newMapKey, newBat, rBaseKey, wBaseKey);
    }

    public AbsoluteCapability withBaseKey(SymmetricKey newBaseKey) {
        return new AbsoluteCapability(owner, writer, mapKey, bat, newBaseKey, wBaseKey);
    }

    public AbsoluteCapability withOwner(PublicKeyHash owner) {
        return new AbsoluteCapability(owner, writer, mapKey, bat, rBaseKey, wBaseKey);
    }

    public boolean isNull() {
        PublicKeyHash nullUser = PublicKeyHash.NULL;
        return nullUser.equals(owner) &&
                nullUser.equals(writer) &&
                Arrays.equals(getMapKey(), new byte[32]) &&
                bat.isEmpty() &&
                rBaseKey.equals(SymmetricKey.createNull());
    }

    public static AbsoluteCapability createNull() {
        return new AbsoluteCapability(PublicKeyHash.NULL, PublicKeyHash.NULL, new byte[32], Optional.empty(), SymmetricKey.createNull());
    }

    @Override
    public CborObject.CborMap toCbor() {
        Map<String, Cborable> cbor = new TreeMap<>();
        cbor.put("o", owner.toCbor());
        cbor.put("w", writer.toCbor());
        cbor.put("m", new CborObject.CborByteArray(mapKey));
        bat.ifPresent(b -> cbor.put("a", b));
        cbor.put("k", rBaseKey.toCbor());
        wBaseKey.ifPresent(wk -> cbor.put("b", wk.toCbor()));
        return CborObject.CborMap.build(cbor);
    }

    public static AbsoluteCapability fromCbor(Cborable cbor) {
        if (! (cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Incorrect cbor for AbsoluteCapability: " + cbor);
        CborObject.CborMap map = ((CborObject.CborMap) cbor);

        PublicKeyHash owner = PublicKeyHash.fromCbor(map.get("o"));
        PublicKeyHash writer = PublicKeyHash.fromCbor(map.get("w"));
        byte[] mapKey = ((CborObject.CborByteArray)map.get("m")).value;
        Optional<Bat> bat = map.getOptional("a", Bat::fromCbor);
        SymmetricKey baseKey = SymmetricKey.fromCbor(map.get("k"));
        Optional<SymmetricKey> writerBaseKey = Optional.ofNullable(map.get("b")).map(SymmetricKey::fromCbor);
        if (writerBaseKey.isPresent())
            return new WritableAbsoluteCapability(owner, writer, mapKey, bat, baseKey, writerBaseKey.get());
        return new AbsoluteCapability(owner, writer, mapKey, bat, baseKey, writerBaseKey);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        AbsoluteCapability that = (AbsoluteCapability) o;
        return Objects.equals(owner, that.owner) &&
                Objects.equals(writer, that.writer) &&
                Arrays.equals(mapKey, that.mapKey) &&
                Objects.equals(rBaseKey, that.rBaseKey);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(owner, writer, rBaseKey);
        result = 31 * result + Arrays.hashCode(mapKey);
        return result;
    }
}
