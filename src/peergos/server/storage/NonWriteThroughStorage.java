package peergos.server.storage;

import peergos.shared.cbor.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.cid.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.storage.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;

public class NonWriteThroughStorage implements ContentAddressedStorage {
    private final ContentAddressedStorage source;
    private final ContentAddressedStorage modifications;

    public NonWriteThroughStorage(ContentAddressedStorage source, Hasher hasher) {
        this.source = source;
        this.modifications = new RAMStorage(hasher);
    }

    @Override
    public ContentAddressedStorage directToOrigin() {
        return this;
    }

    @Override
    public CompletableFuture<Cid> id() {
        return source.id();
    }

    @Override
    public CompletableFuture<TransactionId> startTransaction(PublicKeyHash owner) {
        return modifications.startTransaction(owner);
    }

    @Override
    public CompletableFuture<Boolean> closeTransaction(PublicKeyHash owner, TransactionId tid) {
        return modifications.closeTransaction(owner, tid);
    }

    @Override
    public CompletableFuture<List<byte[]>> getChampLookup(PublicKeyHash owner, Multihash root, byte[] champKey) {
        return modifications.getChampLookup(owner, root, champKey);
    }

    @Override
    public CompletableFuture<Boolean> gc() {
        return modifications.gc();
    }

    @Override
    public CompletableFuture<List<Multihash>> put(PublicKeyHash owner,
                                                  PublicKeyHash writer,
                                                  List<byte[]> signedHashes,
                                                  List<byte[]> blocks,
                                                  TransactionId tid) {
        return modifications.put(owner, writer, signedHashes, blocks, tid);
    }

    @Override
    public CompletableFuture<List<Multihash>> putRaw(PublicKeyHash owner,
                                                     PublicKeyHash writer,
                                                     List<byte[]> signatures,
                                                     List<byte[]> blocks,
                                                     TransactionId tid,
                                                     ProgressConsumer<Long> progressConsumer) {
        return modifications.putRaw(owner, writer, signatures, blocks, tid, progressConsumer);
    }

    @Override
    public CompletableFuture<Optional<byte[]>> getRaw(Multihash object, String auth) {
        try {
            Optional<byte[]> modified = modifications.getRaw(object, auth).get();
            if ( modified.isPresent())
                return CompletableFuture.completedFuture(modified);
            return source.getRaw(object, auth);
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    @Override
    public CompletableFuture<Optional<CborObject>> get(Multihash hash, String auth) {
        try {
            Optional<CborObject> modified = modifications.get(hash, auth).get();
            if ( modified.isPresent())
                return CompletableFuture.completedFuture(modified);
            return source.get(hash, auth);
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    @Override
    public CompletableFuture<List<Multihash>> recursivePin(PublicKeyHash owner, Multihash h) {
        return modifications.recursivePin(owner, h);
    }

    @Override
    public CompletableFuture<List<Multihash>> recursiveUnpin(PublicKeyHash owner, Multihash h) {
        return modifications.recursiveUnpin(owner, h);
    }

    @Override
    public CompletableFuture<List<Multihash>> pinUpdate(PublicKeyHash owner, Multihash existing, Multihash updated) {
        return modifications.pinUpdate(owner, existing, updated);
    }

    @Override
    public CompletableFuture<List<Multihash>> getLinks(Multihash root, String auth) {
        try {
            Optional<CborObject> modified = modifications.get(root, auth).get();
            if (modified.isPresent())
                return CompletableFuture.completedFuture(modified.get().links());
            return source.getLinks(root, auth);
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    @Override
    public CompletableFuture<Optional<Integer>> getSize(Multihash block) {
        try {
            Optional<CborObject> modified = modifications.get(block, "").get();
            if (modified.isPresent())
                return CompletableFuture.completedFuture(modified.map(cbor -> cbor.toByteArray().length));
            return source.getSize(block);
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }
}
