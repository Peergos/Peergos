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
    public Optional<BlockCache> getBlockCache() {
        return Optional.of(cache);
    }

    private synchronized CompletableFuture<Optional<byte[]>> getPending(Cid key) {
        return pending.get(key);
    }

    private synchronized void putPending(Cid key, CompletableFuture<Optional<byte[]>> val) {
        pending.put(key, val);
    }

    private synchronized void removePending(Cid key) {
        pending.remove(key);
    }

    @Override
    public CompletableFuture<Optional<byte[]>> getRaw(PublicKeyHash owner, Cid key, Optional<BatWithId> bat) {
        return cache.get(key)
                .thenCompose(res -> {
                    if (res.isPresent())
                        return Futures.of(res);

                    CompletableFuture<Optional<byte[]>> inProgress = getPending(key);
                    if (inProgress != null)
                        return inProgress;

                    CompletableFuture<Optional<byte[]>> pipe = new CompletableFuture<>();
                    putPending(key, pipe);

                    return Futures.asyncExceptionally(
                            () -> target.getRaw(owner, key, bat).thenApply(blockOpt -> {
                                if (blockOpt.isPresent()) {
                                    byte[] value = blockOpt.get();
                                    cache.put(key, value);
                                }
                                removePending(key);
                                pipe.complete(blockOpt);
                                return blockOpt;
                            }),
                            t -> {
                                removePending(key);
                                pipe.completeExceptionally(t);
                                return Futures.errored(t);
                            });
                });
    }

    @Override
    public CompletableFuture<List<byte[]>> getChampLookup(PublicKeyHash owner,
                                                          Cid root,
                                                          List<ChunkMirrorCap> caps,
                                                          Optional<Cid> committedRoot) {
        // Do local champ gets using cache, then do a single bulk call for those absent
        CachingStorage cache = new CachingStorage(new LocalOnlyStorage(this.cache,
                () -> Futures.errored(new IllegalStateException("Absent block")), hasher),
                100 * (1 + caps.size()), 1024*1024);

        return ChampWrapper.create(owner, root, Optional.empty(), x -> Futures.of(x.data), cache, hasher, c -> (CborObject.CborMerkleLink) c)
                .thenCompose(tree -> Futures.combineAll(caps.stream()
                        .map(cap -> tree.get(cap.mapKey)
                                .thenApply(c -> c.map(x -> x.target)
                                        .map(MaybeMultihash::of).orElse(MaybeMultihash.empty()))
                                .thenCompose(btreeValue -> {
                                    if (btreeValue.isPresent())
                                        return cache.get(owner, (Cid) btreeValue.get(), cap.bat)
                                                .thenApply(x -> Optional.<ChunkMirrorCap>empty());
                                    return Futures.of(Optional.of(cap));
                                }).exceptionally(t -> Optional.of(cap)))
                        .collect(Collectors.toList())))
                .thenApply(missing -> missing.stream()
                                .flatMap(Optional::stream)
                                .collect(Collectors.toList()))
                .exceptionally(t -> caps)
                .thenCompose(missing -> committedRoot.isPresent() ?
                        get(owner, committedRoot.get(), Optional.empty())
                                .thenApply(ropt -> ropt.map(WriterData::fromCbor).flatMap(wd ->  wd.tree))
                                .thenCompose(champRoot -> target.getChampLookup(owner, (Cid) champRoot.get(), missing, Optional.empty())) :
                        target.getChampLookup(owner, root, missing, Optional.empty()))
                .thenApply(blocks -> cacheBlocks(blocks, hasher))
                .thenApply(remote -> {
                    Collection<byte[]> cached = cache.getCached();
                    ArrayList<byte[]> res = new ArrayList<>(cached.size() + remote.size());
                    res.addAll(cached);
                    res.addAll(remote);
                    return res;
                });
    }

    @Override
    public CompletableFuture<List<byte[]>> getChampLookup(PublicKeyHash owner,
                                                          Cid root,
                                                          List<ChunkMirrorCap> caps,
                                                          Optional<Cid> committedRoot,
                                                          Hasher hasher) {
        System.out.println("UnauthedCachingStorage::getChampLookup " + root);
        return Futures.asyncExceptionally(
                () -> target.getChampLookup(owner, root, caps, committedRoot, hasher),
                        t -> super.getChampLookup(owner, root, caps, committedRoot, hasher)
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
        return Futures.combineAllInOrder(IntStream.range(0, hashes.size())
                        .mapToObj(i -> hashes.get(i))
                        .map(c -> c.isIdentity() ? Futures.of(Optional.of(c.getHash())) : cache.get(c))
                        .collect(Collectors.toList()))
                .thenCompose(cached -> {
                    List<Pair<Cid, Optional<BatWithId>>> toGet = IntStream.range(0, hashes.size())
                            .filter(i -> cached.get(i).isEmpty())
                            .mapToObj(i -> new Pair<>(hashes.get(i), i < bats.size() ? Optional.of(bats.get(i)) : Optional.<BatWithId>empty()))
                            .collect(Collectors.toList());
                    if (toGet.isEmpty())
                        return Futures.of(IntStream.range(0, hashes.size())
                                .mapToObj(i -> new FragmentWithHash(new Fragment(cached.get(i).get()), Optional.of(hashes.get(i))))
                                .collect(Collectors.toList()));
                    List<Cid> cidsToGet = toGet.stream().map(p -> p.left).collect(Collectors.toList());
                    List<BatWithId> remainingBats = toGet.stream().flatMap(p -> p.right.stream()).collect(Collectors.toList());
                    return target.downloadFragments(owner, cidsToGet, remainingBats, h, monitor, spaceIncreaseFactor)
                            .thenApply(retrieved -> {
                                retrieved.forEach(f -> cache.put(f.hash.get(), f.fragment.data));
                                return IntStream.range(0, hashes.size())
                                        .mapToObj(i -> new FragmentWithHash(cached.get(i).map(Fragment::new)
                                                .orElse(retrieved.stream()
                                                        .filter(f -> f.hash.get().equals(hashes.get(i)))
                                                        .findFirst()
                                                        .orElseThrow(() -> new IllegalStateException("Missing fragment: " + hashes.get(i)))
                                                        .fragment), Optional.of(hashes.get(i))))
                                        .collect(Collectors.toList());
                            });
                });
    }
}
