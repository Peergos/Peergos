package peergos.shared.storage;

import peergos.shared.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.hamt.*;
import peergos.shared.io.ipfs.cid.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.storage.auth.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

public class UnauthedCachingStorage extends DelegatingStorage {
    private final ContentAddressedStorage target;
    private final BlockCache cache;
    private final LRUCache<Multihash, CompletableFuture<Optional<byte[]>>> pending;
    private final Hasher hasher;

    public UnauthedCachingStorage(ContentAddressedStorage target, BlockCache cache, Hasher hasher) {
        super(target);
        this.target = target;
        this.cache = cache;
        this.pending = new LRUCache<>(200);
        this.hasher = hasher;
    }

    @Override
    public CompletableFuture<BlockStoreProperties> blockStoreProperties() {
        return target.blockStoreProperties();
    }

    @Override
    public ContentAddressedStorage directToOrigin() {
        return new UnauthedCachingStorage(target.directToOrigin(), cache, hasher);
    }

    @Override
    public void clearBlockCache() {
        cache.clear();
        target.clearBlockCache();
    }

    @Override
    public CompletableFuture<Optional<byte[]>> getRaw(Cid key, Optional<BatWithId> bat) {
        return cache.get(key)
                .thenCompose(res -> {
                    if (res.isPresent())
                        return Futures.of(res);

                    if (pending.containsKey(key))
                        return pending.get(key);

                    CompletableFuture<Optional<byte[]>> pipe = new CompletableFuture<>();
                    pending.put(key, pipe);

                    return target.getRaw(key, bat).thenApply(blockOpt -> {
                        if (blockOpt.isPresent()) {
                            byte[] value = blockOpt.get();
                            cache.put(key, value);
                        }
                        pending.remove(key);
                        pipe.complete(blockOpt);
                        return blockOpt;
                    }).exceptionally(t -> {
                        pending.remove(key);
                        pipe.completeExceptionally(t);
                        return Optional.empty();
                    });
                });
    }

    @Override
    public CompletableFuture<List<byte[]>> getChampLookup(PublicKeyHash owner, Cid root, byte[] champKey, Optional<BatWithId> bat) {
        return Futures.asyncExceptionally(
                () -> localChampLookup(root, champKey, bat, hasher),
                t -> target.getChampLookup(owner, root, champKey, bat)
                        .thenApply(blocks -> cacheBlocks(blocks, hasher))
        );
    }

    public CompletableFuture<List<byte[]>> localChampLookup(Cid root, byte[] champKey, Optional<BatWithId> bat, Hasher hasher) {
        CachingStorage cache = new CachingStorage(new LocalOnlyStorage(this.cache), 100, 1024*1024);
        return ChampWrapper.create((Cid)root, x -> Futures.of(x.data), cache, hasher, c -> (CborObject.CborMerkleLink) c)
                .thenCompose(tree -> tree.get(champKey))
                .thenApply(c -> c.map(x -> x.target).map(MaybeMultihash::of).orElse(MaybeMultihash.empty()))
                .thenApply(btreeValue -> {
                    if (btreeValue.isPresent())
                        return cache.get((Cid) btreeValue.get(), bat);
                    return Optional.empty();
                }).thenApply(x -> new ArrayList<>(cache.getCached()));
    }

    @Override
    public CompletableFuture<List<byte[]>> getChampLookup(Cid root, byte[] champKey, Optional<BatWithId> bat, Hasher hasher) {
        return Futures.asyncExceptionally(
                () -> target.getChampLookup(root, champKey, bat, hasher),
                        t -> super.getChampLookup(root, champKey, bat, hasher)
        ).thenApply(blocks -> cacheBlocks(blocks, hasher));
    }

    private List<byte[]> cacheBlocks(List<byte[]> blocks, Hasher hasher) {
        ForkJoinPool.commonPool().execute(() -> Futures.combineAll(blocks.stream()
                        .map(b -> hasher.hash(b, false)
                                .thenApply(c -> new Pair<>(c, new ByteArrayWrapper(b))))
                        .collect(Collectors.toList()))
                .thenAccept(hashed -> hashed.stream().forEach(p -> cache.put(p.left, p.right.data))));
        return blocks;
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
                        cache.put(res.get(i), block);
                    }
                    return res;
                });
    }

    @Override
    public CompletableFuture<Optional<CborObject>> get(Cid key, Optional<BatWithId> bat) {
        return getRaw(key, bat)
                .thenApply(opt -> opt.map(CborObject::fromByteArray));
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
                        cache.put(res.get(i), block);
                    }
                    return res;
                });
    }
}
