package peergos.shared.storage;

import peergos.shared.cbor.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.Cid;
import peergos.shared.io.ipfs.Multihash;
import peergos.shared.storage.auth.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.stream.*;

public class CachingVerifyingStorage extends DelegatingStorage {

    private final ContentAddressedStorage target;
    private final LRUCache<Multihash, byte[]> cache;
    private final LRUCache<Multihash, CompletableFuture<Optional<CborObject>>> pending;
    private final LRUCache<Multihash, CompletableFuture<Optional<byte[]>>> pendingRaw;
    private final int maxValueSize, cacheSize;
    private final List<Cid> nodeIds;
    private final Hasher hasher;

    public CachingVerifyingStorage(ContentAddressedStorage target, int maxValueSize, int cacheSize, List<Cid> nodeIds, Hasher hasher) {
        super(target);
        this.target = target;
        this.cache =  new LRUCache<>(cacheSize);
        this.pending = new LRUCache<>(100);
        this.pendingRaw = new LRUCache<>(100);
        this.maxValueSize = maxValueSize;
        this.cacheSize = cacheSize;
        this.nodeIds = nodeIds;
        this.hasher = hasher;
    }

    @Override
    public CompletableFuture<Cid> id() {
        return Futures.of(nodeIds.get(nodeIds.size() - 1));
    }

    @Override
    public CompletableFuture<List<Cid>> ids() {
        return Futures.of(nodeIds);
    }

    private <T> CompletableFuture<T> verify(byte[] data, Multihash claimed, Supplier<T> result) {
        switch (claimed.type) {
            case sha2_256:
                return hasher.sha256(data)
                        .thenApply(hash -> {
                            Multihash computed = new Multihash(Multihash.Type.sha2_256, hash);
                            if (claimed instanceof Cid)
                                computed = Cid.build(((Cid) claimed).version, ((Cid) claimed).codec, computed);

                            if (computed.equals(claimed))
                                return result.get();

                            throw new IllegalStateException("Incorrect hash! Are you under attack? Expected: " + claimed + " actual: " + computed);
                        });
            case id:
                if (Arrays.equals(data, claimed.getHash()))
                    return Futures.of(result.get());
                throw new IllegalStateException("Incorrect identity hash! This shouldn't ever  happen.");
            default: throw new IllegalStateException("Unimplemented hash algorithm: " + claimed.type);
        }
    }

    @Override
    public CompletableFuture<BlockStoreProperties> blockStoreProperties() {
        return target.blockStoreProperties();
    }

    @Override
    public ContentAddressedStorage directToOrigin() {
        return new CachingVerifyingStorage(target.directToOrigin(), cacheSize, maxValueSize, nodeIds, hasher);
    }

    @Override
    public void clearBlockCache() {
        cache.clear();
        target.clearBlockCache();
    }

    private boolean cache(Multihash h, byte[] block) {
        if (block.length < maxValueSize)
            cache.put(h, block);
        return true;
    }

    @Override
    public CompletableFuture<List<byte[]>> getChampLookup(PublicKeyHash owner, Cid root, byte[] champKey, Optional<BatWithId> bat, Optional<Cid> committedRoot) {
        return target.getChampLookup(owner, root, champKey, bat,committedRoot)
                .thenCompose(blocks -> Futures.combineAllInOrder(blocks.stream()
                        .map(b -> hasher.hash(b, false)
                                .thenApply(h -> cache(h, b)))
                        .collect(Collectors.toList()))
                        .thenApply(x -> blocks));
    }

    @Override
    public CompletableFuture<List<Cid>> put(PublicKeyHash owner,
                                            PublicKeyHash writer,
                                            List<byte[]> signedHashes,
                                            List<byte[]> blocks,
                                            TransactionId tid) {
        return target.put(owner, writer, signedHashes, blocks, tid)
                .thenCompose(hashes -> Futures.combineAllInOrder(hashes.stream()
                        .map(h -> verify(blocks.get(hashes.indexOf(h)), h, () -> h))
                        .collect(Collectors.toList())))
                .thenApply(res -> {
                    for (int i=0; i < blocks.size(); i++) {
                        byte[] block = blocks.get(i);
                        cache(res.get(i), block);
                    }
                    return res;
                });
    }

    @Override
    public CompletableFuture<Optional<CborObject>> get(PublicKeyHash owner, Cid key, Optional<BatWithId> bat) {
        if (cache.containsKey(key))
            return CompletableFuture.completedFuture(Optional.of(CborObject.fromByteArray(cache.get(key))));

        if (pending.containsKey(key))
            return pending.get(key);

        CompletableFuture<Optional<CborObject>> pipe = new CompletableFuture<>();
        pending.put(key, pipe);

        CompletableFuture<Optional<CborObject>> result = new CompletableFuture<>();
        target.get(owner, key, bat)
                .thenCompose(cborOpt -> cborOpt.map(cbor -> verify(cbor.toByteArray(), key, () -> cbor)
                        .thenApply(Optional::of))
                        .orElseGet(() -> Futures.of(Optional.empty())))
                .thenAccept(cborOpt -> {
                    if (cborOpt.isPresent()) {
                        byte[] value = cborOpt.get().toByteArray();
                        if (value.length > 0)
                            cache(key, value);
                    }
                    pending.remove(key);
                    pipe.complete(cborOpt);
                    result.complete(cborOpt);
                }).exceptionally(t -> {
            pending.remove(key);
            pipe.completeExceptionally(t);
            result.completeExceptionally(t);
            return null;
        });
        return result;
    }

    @Override
    public CompletableFuture<List<Cid>> putRaw(PublicKeyHash owner,
                                               PublicKeyHash writer,
                                               List<byte[]> signatures,
                                               List<byte[]> blocks,
                                               TransactionId tid,
                                               ProgressConsumer<Long> progressConsumer) {
        return target.putRaw(owner, writer, signatures, blocks, tid, progressConsumer)
                .thenCompose(hashes -> Futures.combineAllInOrder(hashes.stream()
                        .map(h -> verify(blocks.get(hashes.indexOf(h)), h, () -> h))
                        .collect(Collectors.toList())))
                .thenApply(res -> {
                    for (int i=0; i < blocks.size(); i++) {
                        byte[] block = blocks.get(i);
                        cache(res.get(i), block);
                    }
                    return res;
                });
    }

    @Override
    public CompletableFuture<Optional<byte[]>> getRaw(PublicKeyHash owner, Cid key, Optional<BatWithId> bat) {
        if (cache.containsKey(key))
            return CompletableFuture.completedFuture(Optional.of(cache.get(key)));

        if (pendingRaw.containsKey(key))
            return pendingRaw.get(key);

        CompletableFuture<Optional<byte[]>> pipe = new CompletableFuture<>();
        pendingRaw.put(key, pipe);
        target.getRaw(owner, key, bat)
                .thenCompose(arrOpt -> arrOpt.map(bytes -> verify(bytes, key, () -> bytes)
                                .thenApply(Optional::of))
                        .orElseGet(() -> Futures.of(Optional.empty())))
                .thenApply(rawOpt -> {
                    if (rawOpt.isPresent()) {
                        byte[] value = rawOpt.get();
                        if (value.length > 0)
                            cache(key, value);
                    }
                    pendingRaw.remove(key);
                    pipe.complete(rawOpt);
                    return rawOpt;
                }).exceptionally(t -> {
                    pendingRaw.remove(key);
                    pipe.completeExceptionally(t);
                    return null;
                });
        return pipe;
    }
}
