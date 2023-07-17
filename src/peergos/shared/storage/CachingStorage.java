package peergos.shared.storage;

import peergos.shared.cbor.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.Cid;
import peergos.shared.io.ipfs.Multihash;
import peergos.shared.storage.auth.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;

public class CachingStorage extends DelegatingStorage {
    private final ContentAddressedStorage target;
    private final LRUCache<Multihash, byte[]> cache;
    private final LRUCache<Multihash, CompletableFuture<Optional<CborObject>>> pending;
    private final LRUCache<Multihash, CompletableFuture<Optional<byte[]>>> pendingRaw;
    private final int maxValueSize, cacheSize;

    public CachingStorage(ContentAddressedStorage target, int cacheSize, int maxValueSize) {
        super(target);
        this.target = target;
        this.cache = new LRUCache<>(cacheSize);
        this.maxValueSize = maxValueSize;
        this.cacheSize = cacheSize;
        this.pending = new LRUCache<>(100);
        this.pendingRaw = new LRUCache<>(100);
    }

    public Collection<byte[]> getCached() {
        return cache.values();
    }

    @Override
    public CompletableFuture<BlockStoreProperties> blockStoreProperties() {
        return target.blockStoreProperties();
    }

    @Override
    public ContentAddressedStorage directToOrigin() {
        return new CachingStorage(target.directToOrigin(), cacheSize, maxValueSize);
    }

    @Override
    public void clearBlockCache() {
        cache.clear();
        target.clearBlockCache();
    }

    @Override
    public CompletableFuture<List<Cid>> put(PublicKeyHash owner,
                                            PublicKeyHash writer,
                                            List<byte[]> signedHashes,
                                            List<byte[]> blocks,
                                            TransactionId tid) {
        return target.put(owner, writer, signedHashes, blocks, tid)
                .thenApply(res -> {
                    for (int i=0; i < blocks.size(); i++) {
                        byte[] block = blocks.get(i);
                        if (block.length < maxValueSize)
                            cache.put(res.get(i), block);
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
        target.get(owner, key, bat).thenAccept(cborOpt -> {
            if (cborOpt.isPresent()) {
                byte[] value = cborOpt.get().toByteArray();
                if (value.length > 0 && value.length < maxValueSize)
                    cache.put(key, value);
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
                .thenApply(res -> {
                    for (int i=0; i < blocks.size(); i++) {
                        byte[] block = blocks.get(i);
                        if (block.length < maxValueSize)
                            cache.put(res.get(i), block);
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
        return target.getRaw(owner, key, bat).thenApply(rawOpt -> {
            if (rawOpt.isPresent()) {
                byte[] value = rawOpt.get();
                if (value.length > 0 && value.length < maxValueSize)
                    cache.put(key, value);
            }
            pendingRaw.remove(key);
            pipe.complete(rawOpt);
            return rawOpt;
        }).exceptionally(t -> {
            pending.remove(key);
            pipe.completeExceptionally(t);
            return Optional.empty();
        });
    }
}
