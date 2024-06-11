package peergos.shared.user;

import jsinterop.annotations.*;
import peergos.shared.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.crypto.symmetric.*;
import peergos.shared.user.fs.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

@JsType
public class EntryPoint implements Cborable {

    public final AbsoluteCapability pointer;
    public final String ownerName;

    public EntryPoint(AbsoluteCapability pointer, String ownerName) {
        this.pointer = pointer;
        this.ownerName = ownerName;
    }

    public EntryPoint withOwner(PublicKeyHash newOwner) {
        return new EntryPoint(pointer.withOwner(newOwner), ownerName);
    }

    public byte[] serializeAndSymmetricallyEncrypt(SymmetricKey key) {
        byte[] nonce = key.createNonce();
        return ArrayOps.concat(nonce, key.encrypt(serialize(), nonce));
    }

    /**
     *
     * @param path The path of the file this entry point corresponds to
     * @param network
     * @return
     */
    public CompletableFuture<Boolean> isValid(String path, NetworkAccess network) {
        String[] parts = path.split("/");
        String claimedOwner = parts[1];
        // check claimed owner actually owns the signing key
        PublicKeyHash entryWriter = pointer.writer;
        return network.coreNode.getPublicKeyHash(claimedOwner).thenCompose(ownerKey -> {
            if (! ownerKey.isPresent())
                throw new IllegalStateException("No owner key present for user " + claimedOwner);
            return UserContext.getWriterData(network, ownerKey.get(), ownerKey.get())
                    .thenCompose(wd -> wd.props.get().ownsKey(ownerKey.get(), entryWriter, network.dhtClient, network.mutable, network.hasher));
        });
    }

    @Override
    @SuppressWarnings("unusable-by-js")
    public CborObject toCbor() {
        Map<String, Cborable> cbor = new TreeMap<>();
        cbor.put("c", pointer.toCbor());
        cbor.put("n", new CborObject.CborString(ownerName));
        return CborObject.CborMap.build(cbor);
    }

    @SuppressWarnings("unusable-by-js")
    public static EntryPoint fromCbor(Cborable cbor) {
        if (! (cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Incorrect cbor type for EntryPoint: " + cbor);

        CborObject.CborMap map = (CborObject.CborMap) cbor;
        AbsoluteCapability pointer = map.getObject("c", AbsoluteCapability::fromCbor);
        String ownerName = map.getString("n");
        return new EntryPoint(pointer, ownerName);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        EntryPoint that = (EntryPoint) o;
        return Objects.equals(pointer, that.pointer) &&
                Objects.equals(ownerName, that.ownerName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(pointer, ownerName);
    }

    static EntryPoint symmetricallyDecryptAndDeserialize(byte[] input, SymmetricKey key) {
        byte[] nonce = Arrays.copyOfRange(input, 0, 24);
        byte[] raw = key.decrypt(Arrays.copyOfRange(input, 24, input.length), nonce);
        return fromCbor(CborObject.fromByteArray(raw));
    }

    @Override
    public String toString() {
        return ownerName;
    }
}
