package peergos.shared.user.fs;

import jsinterop.annotations.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.crypto.random.*;
import peergos.shared.crypto.symmetric.SymmetricKey;
import peergos.shared.storage.auth.*;
import peergos.shared.user.fs.cryptree.*;
import peergos.shared.util.*;

import java.util.*;

/** This provides a relative cryptographic capability (read only or read and write) for a file or folder.
 *
 *  Here, relative means that the holder has the owner and writer public key from another source, typically the parent
 *  folder or entry point. This also includes the relative link between the symmetric write base keys if they are different.
 */
public class RelativeCapability implements Cborable {
    public static final int MAP_KEY_LENGTH = 32;

    // writer is only present when it is not implicit (an entry point, or a child link to a different writing key)
    public final Optional<PublicKeyHash> writer;
    private final byte[] mapKey;
    public final Optional<Bat> bat; // Only absent on legacy data
    public final SymmetricKey rBaseKey;
    public final Optional<SymmetricLink> wBaseKeyLink;
    // Only in child links of a restricted format directory, where rBaseKey is the child's restricted key. The child's
    // full key is linked from the directory's, or sealed to it by someone who only has the directory's restricted key.
    public final Optional<SymmetricLink> fullKeyLink;
    public final Optional<SealedKey> sealedFullKey;
    // whether the full key came from someone with only the directory's restricted key, so they may know it
    private boolean pending;

    public RelativeCapability(Optional<PublicKeyHash> writer,
                              byte[] mapKey,
                              Optional<Bat> bat,
                              SymmetricKey rBaseKey,
                              Optional<SymmetricLink> wBaseKeyLink) {
        this(writer, mapKey, bat, rBaseKey, wBaseKeyLink, Optional.empty(), Optional.empty());
    }

    @JsConstructor
    public RelativeCapability(Optional<PublicKeyHash> writer,
                              byte[] mapKey,
                              Optional<Bat> bat,
                              SymmetricKey rBaseKey,
                              Optional<SymmetricLink> wBaseKeyLink,
                              Optional<SymmetricLink> fullKeyLink,
                              Optional<SealedKey> sealedFullKey) {
        this.writer = writer;
        if (mapKey.length != RelativeCapability.MAP_KEY_LENGTH)
            throw new IllegalStateException("Invalid map key length: " + mapKey.length);
        this.mapKey = mapKey;
        this.bat = bat;
        this.rBaseKey = rBaseKey;
        this.wBaseKeyLink = wBaseKeyLink;
        this.fullKeyLink = fullKeyLink;
        this.sealedFullKey = sealedFullKey;
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
        AbsoluteCapability res = wBaseKey.isPresent() ?
                new WritableAbsoluteCapability(source.owner, writer, mapKey, bat, rBaseKey, wBaseKey.get()) :
                new AbsoluteCapability(source.owner, writer, mapKey, bat, rBaseKey, wBaseKey);
        return pending || source.isPending() ? res.asPending() : res;
    }

    public RelativeCapability withWritingKey(Optional<PublicKeyHash> writingKey) {
        return new RelativeCapability(writingKey, mapKey, bat, rBaseKey, wBaseKeyLink, fullKeyLink, sealedFullKey).pending(pending);
    }

    public RelativeCapability withBaseKey(SymmetricKey newBaseKey) {
        return new RelativeCapability(writer, mapKey, bat, newBaseKey, wBaseKeyLink, fullKeyLink, sealedFullKey).pending(pending);
    }

    public boolean isPending() {
        return pending;
    }

    public RelativeCapability asPending() {
        return new RelativeCapability(writer, mapKey, bat, rBaseKey, wBaseKeyLink, fullKeyLink, sealedFullKey).pending(true);
    }

    private RelativeCapability pending(boolean pending) {
        this.pending = pending;
        return this;
    }

    public boolean hasWrappedFullKey() {
        return fullKeyLink.isPresent() || sealedFullKey.isPresent();
    }

    /** Replace our full key with the restricted key derived from it, and link to the full key from the parent's. */
    public RelativeCapability wrapFullKey(SymmetricKey parentFullKey) {
        return new RelativeCapability(writer, mapKey, bat, rBaseKey.derive(CryptreeNode.RESTRICTED), wBaseKeyLink,
                Optional.of(SymmetricLink.fromPair(parentFullKey.derive(linkLabel()), rBaseKey)), Optional.empty()).pending(pending);
    }

    /** Replace our full key with the restricted key derived from it, and seal the full key to the parent. */
    public RelativeCapability sealFullKey(byte[] parentPublicKey, SafeRandom random) {
        return new RelativeCapability(writer, mapKey, bat, rBaseKey.derive(CryptreeNode.RESTRICTED), wBaseKeyLink,
                Optional.empty(), Optional.of(SealedKey.seal(rBaseKey, parentPublicKey, random))).pending(true);
    }

    public RelativeCapability unwrapFullKey(SymmetricKey parentFullKey, Optional<byte[]> parentPublicKey) {
        if (fullKeyLink.isPresent())
            return new RelativeCapability(writer, mapKey, bat, matching(fullKeyLink.get().target(parentFullKey.derive(linkLabel()))),
                    wBaseKeyLink, Optional.empty(), Optional.empty()).pending(pending);
        if (sealedFullKey.isPresent())
            return new RelativeCapability(writer, mapKey, bat, matching(sealedFullKey.get().open(parentFullKey, parentPublicKey.get())),
                    wBaseKeyLink, Optional.empty(), Optional.empty()).pending(true);
        return this;
    }

    /** A restricted key holder could pair a full key with someone else's restricted key, showing us a different child. */
    private SymmetricKey matching(SymmetricKey fullKey) {
        if (! fullKey.derive(CryptreeNode.RESTRICTED).equals(rBaseKey))
            throw new IllegalStateException("A child's full key doesn't match its restricted key");
        return fullKey;
    }

    private String linkLabel() {
        return pending ? CryptreeNode.PENDING_CHILD_KEYS : CryptreeNode.CHILD_KEYS;
    }

    public static RelativeCapability buildSubsequentChunk(byte[] mapkey, Optional<Bat> bat, SymmetricKey baseKey) {
        return new RelativeCapability(Optional.empty(), mapkey, bat, baseKey, Optional.empty());
    }

    @Override
    public CborObject.CborMap toCbor() {
        Map<String, Cborable> cbor = new TreeMap<>();
        writer.ifPresent(w -> cbor.put("w", w.toCbor()));
        cbor.put("m", new CborObject.CborByteArray(mapKey));
        bat.ifPresent(b -> cbor.put("a", b));
        cbor.put("k", rBaseKey.toCbor());
        wBaseKeyLink.ifPresent(w -> cbor.put("l", w.toCbor()));
        fullKeyLink.ifPresent(f -> cbor.put(pending ? "h" : "f", f.toCbor()));
        sealedFullKey.ifPresent(g -> cbor.put("g", g.toCbor()));
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
        Optional<Bat> bat = map.getOptional("a", Bat::fromCbor);
        SymmetricKey baseKey = SymmetricKey.fromCbor(map.get("k"));
        Optional<SymmetricLink> writerLink = Optional.ofNullable(map.get("l")).map(SymmetricLink::fromCbor);
        Optional<SymmetricLink> pendingKeyLink = Optional.ofNullable(map.get("h")).map(SymmetricLink::fromCbor);
        Optional<SymmetricLink> fullKeyLink = Optional.ofNullable(map.get("f")).map(SymmetricLink::fromCbor).or(() -> pendingKeyLink);
        Optional<SealedKey> sealedFullKey = Optional.ofNullable(map.get("g")).map(SealedKey::fromCbor);
        return new RelativeCapability(writer, mapKey, bat, baseKey, writerLink, fullKeyLink, sealedFullKey)
                .pending(pendingKeyLink.isPresent() && map.get("f") == null);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RelativeCapability that = (RelativeCapability) o;
        return Objects.equals(writer, that.writer) &&
                Arrays.equals(mapKey, that.mapKey) &&
                Objects.equals(bat, that.bat) &&
                Objects.equals(rBaseKey, that.rBaseKey) &&
                Objects.equals(wBaseKeyLink, that.wBaseKeyLink) &&
                Objects.equals(fullKeyLink, that.fullKeyLink) &&
                Objects.equals(sealedFullKey, that.sealedFullKey);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(writer, bat, rBaseKey, wBaseKeyLink);
        result = 31 * result + Arrays.hashCode(mapKey);
        return result;
    }

    @Override
    public String toString() {
        return ArrayOps.bytesToHex(getMapKey());
    }
}
