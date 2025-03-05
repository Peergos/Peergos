package peergos.shared.storage;

import peergos.shared.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.hamt.*;
import peergos.shared.io.ipfs.Cid;
import peergos.shared.io.ipfs.Multihash;
import peergos.shared.storage.auth.*;
import peergos.shared.user.*;
import peergos.shared.user.fs.Fragment;
import peergos.shared.user.fs.FragmentWithHash;
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
    public CompletableFuture<Optional<byte[]>> getRaw(PublicKeyHash owner, Cid key, Optional<BatWithId> bat) {
        return cache.get(key)
                .thenCompose(res -> {
                    if (res.isPresent())
                        return Futures.of(res);

                    if (pending.containsKey(key))
                        return pending.get(key);

                    CompletableFuture<Optional<byte[]>> pipe = new CompletableFuture<>();
                    pending.put(key, pipe);

                    return target.getRaw(owner, key, bat).thenApply(blockOpt -> {
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
    public CompletableFuture<List<byte[]>> getChampLookup(PublicKeyHash owner,
                                                          Cid root,
                                                          byte[] champKey,
                                                          Optional<BatWithId> bat,
                                                          Optional<Cid> committedRoot) {
        return Futures.asyncExceptionally(
                () -> localChampLookup(owner, root, champKey, bat, committedRoot, hasher),
                t -> target.getChampLookup(owner, root, champKey, bat,  committedRoot)
                        .thenApply(blocks -> cacheBlocks(blocks, hasher)));
    }

    public CompletableFuture<List<byte[]>> localChampLookup(PublicKeyHash owner,
                                                            Cid root,
                                                            byte[] champKey,
                                                            Optional<BatWithId> bat,
                                                            Optional<Cid> committedRoot,
                                                            Hasher hasher) {
        CachingStorage cache = new CachingStorage(new LocalOnlyStorage(this.cache,
                () -> (committedRoot.isPresent() ?
                        get(owner, committedRoot.get(), Optional.empty())
                                .thenApply(ropt -> ropt.map(WriterData::fromCbor).flatMap(wd ->  wd.tree))
                                .thenCompose(champRoot -> target.getChampLookup(owner, (Cid) champRoot.get(), champKey, bat, Optional.empty())) :
                        target.getChampLookup(owner, root, champKey, bat, Optional.empty()))
                        .thenApply(blocks -> cacheBlocks(blocks, hasher)), hasher),
                100, 1024*1024);
        return ChampWrapper.create(owner, root, Optional.empty(), x -> Futures.of(x.data), cache, hasher, c -> (CborObject.CborMerkleLink) c)
                .thenCompose(tree -> tree.get(champKey))
                .thenApply(c -> c.map(x -> x.target).map(MaybeMultihash::of).orElse(MaybeMultihash.empty()))
                .thenApply(btreeValue -> {
                    if (btreeValue.isPresent())
                        return cache.get(owner, (Cid) btreeValue.get(), bat);
                    return Optional.empty();
                }).thenApply(x -> new ArrayList<>(cache.getCached()));
    }

    @Override
    public CompletableFuture<List<byte[]>> getChampLookup(PublicKeyHash owner, Cid root,
                                                          byte[] champKey,
                                                          Optional<BatWithId> bat,
                                                          Optional<Cid> committedRoot,
                                                          Hasher hasher) {
        System.out.println("UnauthedCachingStorage::getChampLookup " + root);
        return Futures.asyncExceptionally(
                () -> target.getChampLookup(owner, root, champKey, bat, committedRoot, hasher),
                        t -> super.getChampLookup(owner, root, champKey, bat, committedRoot, hasher)
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
    public CompletableFuture<Optional<CborObject>> get(PublicKeyHash owner, Cid key, Optional<BatWithId> bat) {
        return getRaw(owner, key, bat)
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

    @Override
    public CompletableFuture<List<FragmentWithHash>> downloadFragments(PublicKeyHash owner,
                                                                       List<Cid> hashes,
                                                                       List<BatWithId> bats,
                                                                       Hasher h,
                                                                       ProgressConsumer<Long> monitor,
                                                                       double spaceIncreaseFactor) {
        return Futures.combineAllInOrder(IntStream.range(0, hashes.size()).mapToObj(i -> cache.get(hashes.get(i))).collect(Collectors.toList()))
                .thenCompose(cached -> {
                    List<Pair<Cid, BatWithId>> toGet = IntStream.range(0, hashes.size())
                            .filter(i -> cached.get(i).isEmpty())
                            .mapToObj(i -> new Pair<>(hashes.get(i), bats.get(i)))
                            .collect(Collectors.toList());
                    if (toGet.isEmpty())
                        return Futures.of(IntStream.range(0, hashes.size())
                                .mapToObj(i -> new FragmentWithHash(new Fragment(cached.get(i).get()), Optional.of(hashes.get(i))))
                                .collect(Collectors.toList()));
                    List<Cid> cidsToGet = toGet.stream().map(p -> p.left).collect(Collectors.toList());
                    List<BatWithId> remainingBats = toGet.stream().map(p -> p.right).collect(Collectors.toList());
                    return target.downloadFragments(owner, cidsToGet, remainingBats, h, monitor, spaceIncreaseFactor)
                            .thenApply(retrieved -> {
                                retrieved.forEach(f -> cache.put(f.hash.get(), f.fragment.data));
                                return IntStream.range(0, hashes.size())
                                        .mapToObj(i -> new FragmentWithHash(cached.get(i).map(Fragment::new)
                                                .orElse(retrieved.stream()
                                                        .filter(f -> f.hash.get().equals(hashes.get(i)))
                                                        .findFirst()
                                                        .get().fragment), Optional.of(hashes.get(i))))
                                        .collect(Collectors.toList());
                            });
                });
    }
}
