package peergos.shared.user;

import jsinterop.annotations.*;
import peergos.shared.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.crypto.symmetric.*;
import peergos.shared.user.fs.*;
import peergos.shared.util.*;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

@JsType
public class EntryPoint implements Cborable{

    public final FilePointer pointer;
    public final String owner;
    public final Set<String> readers, writers;

    public EntryPoint(FilePointer pointer, String owner, Set<String> readers, Set<String> writers) {
        this.pointer = pointer;
        this.owner = owner;
        this.readers = readers;
        this.writers = writers;
    }

    @SuppressWarnings("unusable-by-js")
    public byte[] serializeAndEncrypt(BoxingKeyPair user, PublicBoxingKey target) throws IOException {
        return target.encryptMessageFor(this.serialize(), user.secretBoxingKey);
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
        PublicKeyHash entryWriter = pointer.getLocation().writer;
        return network.coreNode.getPublicKeyHash(claimedOwner).thenCompose(ownerKey -> {
            if (! ownerKey.isPresent())
                throw new IllegalStateException("No owner key present for user " + claimedOwner);
           return UserContext.getWriterData(network, ownerKey.get()).thenApply(wd -> {
               // TODO do this recursively to handle arbitrary trees of key ownership
               return wd.props.ownedKeys.contains(entryWriter);
           });
        });
    }

    @Override
    @SuppressWarnings("unusable-by-js")
    public CborObject toCbor() {
        return new CborObject.CborList(Arrays.asList(
                pointer.toCbor(),
                new CborObject.CborString(owner),
                new CborObject.CborList(readers.stream().sorted().map(CborObject.CborString::new).collect(Collectors.toList())),
                new CborObject.CborList(writers.stream().sorted().map(CborObject.CborString::new).collect(Collectors.toList()))
        ));
    }

    static EntryPoint fromCbor(CborObject cbor) {
        if (! (cbor instanceof CborObject.CborList))
            throw new IllegalStateException("Incorrect cbor type for EntryPoint: " + cbor);

        List<? extends Cborable> value = ((CborObject.CborList) cbor).value;
        FilePointer pointer = FilePointer.fromCbor(value.get(0));
        String owner = ((CborObject.CborString) value.get(1)).value;
        Set<String> readers = ((CborObject.CborList) value.get(2)).value
                .stream()
                .map(c -> ((CborObject.CborString) c).value)
                .collect(Collectors.toSet());
        Set<String> writers = ((CborObject.CborList) value.get(3)).value
                .stream()
                .map(c -> ((CborObject.CborString) c).value)
                .collect(Collectors.toSet());
        return new EntryPoint(pointer, owner, readers, writers);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        EntryPoint that = (EntryPoint) o;

        if (pointer != null ? !pointer.equals(that.pointer) : that.pointer != null) return false;
        if (owner != null ? !owner.equals(that.owner) : that.owner != null) return false;
        if (readers != null ? !readers.equals(that.readers) : that.readers != null) return false;
        return writers != null ? writers.equals(that.writers) : that.writers == null;

    }

    @Override
    public int hashCode() {
        int result = pointer != null ? pointer.hashCode() : 0;
        result = 31 * result + (owner != null ? owner.hashCode() : 0);
        result = 31 * result + (readers != null ? readers.hashCode() : 0);
        result = 31 * result + (writers != null ? writers.hashCode() : 0);
        return result;
    }

    static EntryPoint symmetricallyDecryptAndDeserialize(byte[] input, SymmetricKey key) throws IOException {
        byte[] nonce = Arrays.copyOfRange(input, 0, 24);
        byte[] raw = key.decrypt(Arrays.copyOfRange(input, 24, input.length), nonce);
        return fromCbor(CborObject.fromByteArray(raw));
    }
}
