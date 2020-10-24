package peergos.server.storage;

import peergos.shared.cbor.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.storage.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;

public class NonWriteThroughStorage implements ContentAddressedStorage {
    private final ContentAddressedStorage source;
    private final ContentAddressedStorage modifications;

    public NonWriteThroughStorage(ContentAddressedStorage source) {
        this.source = source;
        this.modifications = new RAMStorage();
    }

    @Override
    public ContentAddressedStorage directToOrigin() {
        return this;
    }

    @Override
    public CompletableFuture<Multihash> id() {
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
    public CompletableFuture<Optional<byte[]>> getRaw(Multihash object) {
        try {
            Optional<byte[]> modified = modifications.getRaw(object).get();
            if ( modified.isPresent())
                return CompletableFuture.completedFuture(modified);
            return source.getRaw(object);
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    @Override
    public CompletableFuture<Optional<CborObject>> get(Multihash hash) {
        try {
            Optional<CborObject> modified = modifications.get(hash).get();
            if ( modified.isPresent())
                return CompletableFuture.completedFuture(modified);
            return source.get(hash);
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
    public CompletableFuture<List<Multihash>> getLinks(Multihash root) {
        try {
            Optional<CborObject> modified = modifications.get(root).get();
            if (modified.isPresent())
                return CompletableFuture.completedFuture(modified.get().links());
            return source.getLinks(root);
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    @Override
    public CompletableFuture<Optional<Integer>> getSize(Multihash block) {
        try {
            Optional<CborObject> modified = modifications.get(block).get();
            if (modified.isPresent())
                return CompletableFuture.completedFuture(modified.map(cbor -> cbor.toByteArray().length));
            return source.getSize(block);
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }
}
