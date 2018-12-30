package peergos.shared.user;

import jsinterop.annotations.*;
import peergos.shared.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.crypto.symmetric.*;
import peergos.shared.user.fs.*;
import peergos.shared.util.*;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

@JsType
public class EntryPoint implements Cborable {

    public final Capability pointer;
    public final String ownerName;
    public final PublicKeyHash owner;
    public final Set<String> readers, writers;

    public EntryPoint(Capability pointer, String ownerName, PublicKeyHash owner, Set<String> readers, Set<String> writers) {
        if (! pointer.writer.isPresent())
            throw new IllegalStateException("EntryPoint requires a non relative capability!");
        this.pointer = pointer;
        this.ownerName = ownerName;
        this.owner = owner;
        this.readers = readers;
        this.writers = writers;
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
        PublicKeyHash entryWriter = pointer.writer.get();
        return network.coreNode.getPublicKeyHash(claimedOwner).thenCompose(ownerKey -> {
            if (! ownerKey.isPresent())
                throw new IllegalStateException("No owner key present for user " + claimedOwner);
           return UserContext.getWriterData(network, ownerKey.get(), ownerKey.get()).thenApply(wd -> {
               // TODO do this recursively to handle arbitrary trees of key ownership
               return wd.props.ownedKeys.contains(entryWriter);
           });
        });
    }

    @Override
    @SuppressWarnings("unusable-by-js")
    public CborObject toCbor() {
        Map<String, CborObject> cbor = new TreeMap<>();
        cbor.put("c", pointer.toCbor());
        cbor.put("n", new CborObject.CborString(ownerName));
        cbor.put("o", owner.toCbor());
        cbor.put("r", new CborObject.CborList(readers.stream().sorted().map(CborObject.CborString::new).collect(Collectors.toList())));
        cbor.put("w", new CborObject.CborList(writers.stream().sorted().map(CborObject.CborString::new).collect(Collectors.toList())));
        return CborObject.CborMap.build(cbor);
    }

    public static EntryPoint fromCbor(Cborable cbor) {
        if (! (cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Incorrect cbor type for EntryPoint: " + cbor);

        SortedMap<CborObject, ? extends Cborable> map = ((CborObject.CborMap) cbor).values;
        Capability pointer = Capability.fromCbor(map.get(new CborObject.CborString("c")));
        String ownerName = ((CborObject.CborString) map.get(new CborObject.CborString("n"))).value;
        PublicKeyHash owner = PublicKeyHash.fromCbor(map.get(new CborObject.CborString("o")));
        Set<String> readers = ((CborObject.CborList) map.get(new CborObject.CborString("r"))).value
                .stream()
                .map(c -> ((CborObject.CborString) c).value)
                .collect(Collectors.toSet());
        Set<String> writers = ((CborObject.CborList) map.get(new CborObject.CborString("w"))).value
                .stream()
                .map(c -> ((CborObject.CborString) c).value)
                .collect(Collectors.toSet());
        return new EntryPoint(pointer, ownerName, owner, readers, writers);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        EntryPoint that = (EntryPoint) o;
        return Objects.equals(pointer, that.pointer) &&
                Objects.equals(ownerName, that.ownerName) &&
                Objects.equals(owner, that.owner) &&
                Objects.equals(readers, that.readers) &&
                Objects.equals(writers, that.writers);
    }

    @Override
    public int hashCode() {
        return Objects.hash(pointer, ownerName, owner, readers, writers);
    }

    static EntryPoint symmetricallyDecryptAndDeserialize(byte[] input, SymmetricKey key) {
        byte[] nonce = Arrays.copyOfRange(input, 0, 24);
        byte[] raw = key.decrypt(Arrays.copyOfRange(input, 24, input.length), nonce);
        return fromCbor(CborObject.fromByteArray(raw));
    }
}
