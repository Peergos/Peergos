package peergos.shared.storage;

import peergos.shared.cbor.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

public class FixedContentAddressedStorage implements ContentAddressedStorage {

    private final Map<Multihash, byte[]> blocks;

    public FixedContentAddressedStorage(Map<Multihash, byte[]> blocks) {
        this.blocks = blocks;
    }

    public static CompletableFuture<FixedContentAddressedStorage> build(List<byte[]> blocks, Hasher hasher) {
        return Futures.combineAll(blocks.stream()
                .map(b -> hasher.hash(b, false)
                        .thenApply(h -> new Pair<>(h, b)))
                .collect(Collectors.toList()))
                .thenApply(pairs -> new FixedContentAddressedStorage(pairs.stream().collect(Collectors.toMap(p -> p.left, p -> p.right))));
    }

    @Override
    public ContentAddressedStorage directToOrigin() {
        throw new IllegalStateException("Unsupported operation!");
    }

    @Override
    public CompletableFuture<Multihash> id() {
        throw new IllegalStateException("Unsupported operation!");
    }

    @Override
    public CompletableFuture<TransactionId> startTransaction(PublicKeyHash owner) {
        throw new IllegalStateException("Unsupported operation!");
    }

    @Override
    public CompletableFuture<Boolean> closeTransaction(PublicKeyHash owner, TransactionId tid) {
        throw new IllegalStateException("Unsupported operation!");
    }

    @Override
    public CompletableFuture<List<Multihash>> put(PublicKeyHash owner, PublicKeyHash writer, List<byte[]> signedHashes, List<byte[]> blocks, TransactionId tid) {
        throw new IllegalStateException("Unsupported operation!");
    }

    @Override
    public CompletableFuture<Optional<CborObject>> get(Multihash hash) {
        return Futures.of(Optional.of(CborObject.fromByteArray(blocks.get(hash))));
    }

    @Override
    public CompletableFuture<List<Multihash>> putRaw(PublicKeyHash owner, PublicKeyHash writer, List<byte[]> signedHashes, List<byte[]> blocks, TransactionId tid, ProgressConsumer<Long> progressCounter) {
        throw new IllegalStateException("Unsupported operation!");
    }

    @Override
    public CompletableFuture<Optional<byte[]>> getRaw(Multihash hash) {
        throw new IllegalStateException("Unsupported operation!");
    }

    @Override
    public CompletableFuture<List<Multihash>> pinUpdate(PublicKeyHash owner, Multihash existing, Multihash updated) {
        throw new IllegalStateException("Unsupported operation!");
    }

    @Override
    public CompletableFuture<List<Multihash>> recursivePin(PublicKeyHash owner, Multihash hash) {
        throw new IllegalStateException("Unsupported operation!");
    }

    @Override
    public CompletableFuture<List<Multihash>> recursiveUnpin(PublicKeyHash owner, Multihash hash) {
        throw new IllegalStateException("Unsupported operation!");
    }

    @Override
    public CompletableFuture<List<byte[]>> getChampLookup(PublicKeyHash owner, Multihash root, byte[] champKey) {
        throw new IllegalStateException("Unsupported operation!");
    }

    @Override
    public CompletableFuture<Boolean> gc() {
        throw new IllegalStateException("Unsupported operation!");
    }

    @Override
    public CompletableFuture<Optional<Integer>> getSize(Multihash block) {
        throw new IllegalStateException("Unsupported operation!");
    }
}
