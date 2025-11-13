package peergos.shared.storage;

import peergos.shared.*;
import peergos.shared.cbor.*;
import peergos.shared.corenode.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.hamt.*;
import peergos.shared.io.ipfs.Cid;
import peergos.shared.io.ipfs.Multihash;
import peergos.shared.mutable.*;
import peergos.shared.storage.auth.*;
import peergos.shared.user.*;
import peergos.shared.user.fs.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.stream.*;

public class BufferedStorage extends DelegatingStorage {

    private final Map<Cid, OpLog.BlockWrite> storage = new LinkedHashMap<>();
    private final ContentAddressedStorage target;
    private final Hasher hasher;

    public BufferedStorage(ContentAddressedStorage target, Hasher hasher) {
        super(target);
        if (target instanceof BufferedStorage)
            throw new IllegalStateException("Nested BufferedStorage!");
        this.target = target;
        this.hasher = hasher;
    }

    public boolean hasBufferedBlock(Cid c) {
        synchronized (storage) {
            return storage.containsKey(c);
        }
    }

    public boolean isEmpty() {
        synchronized (storage) {
            return storage.isEmpty();
        }
    }

    public ContentAddressedStorage target() {
        return target;
    }

    @Override
    public ContentAddressedStorage directToOrigin() {
        return this;
    }

    @Override
    public CompletableFuture<TransactionId> startTransaction(PublicKeyHash owner) {
        TransactionId tid = new TransactionId(Long.toString(System.currentTimeMillis()));
        return CompletableFuture.completedFuture(tid);
    }

    @Override
    public CompletableFuture<Boolean> closeTransaction(PublicKeyHash owner, TransactionId tid) {
        return CompletableFuture.completedFuture(true);
    }

    @Override
    public CompletableFuture<List<byte[]>> getChampLookup(PublicKeyHash owner,
                                                          Cid root,
                                                          List<ChunkMirrorCap> caps,
                                                          Optional<Cid> committedRoot) {
        if (isEmpty())
            return target.getChampLookup(owner, root, caps, committedRoot);
        // If we are in a write transaction try to perform a local champ lookup from the buffer,
        // falling back to a direct champ get
        //TODO collect all missing lookups into a single remote bulk get
        return Futures.combineAllInOrder(caps.stream().map(cap -> Futures.asyncExceptionally(
                () -> getChampLookup(owner, root, cap.mapKey, cap.bat, committedRoot, hasher),
                t -> target.getChampLookup(owner, root, Arrays.asList(cap), committedRoot)
        )).collect(Collectors.toList()))
                .thenApply(res ->res.stream()
                .flatMap(Collection::stream)
                .collect(Collectors.toList()));
    }

    public CompletableFuture<List<byte[]>> getChampLookup(PublicKeyHash owner,
                                                          Cid root,
                                                          byte[] champKey,
                                                          Optional<BatWithId> bat,
                                                          Optional<Cid> committedRoot,
                                                          Hasher hasher) {
        BlockCache ramBlockCache = new BlockCache() {
            Map<Cid, byte[]> localCache = new HashMap<>();

            @Override
            public CompletableFuture<Boolean> put(Cid hash, byte[] data) {
                localCache.put(hash, data);
                return Futures.of(true);
            }

            @Override
            public CompletableFuture<Optional<byte[]>> get(Cid hash) {
                synchronized (storage) {
                    return Futures.of(Optional.ofNullable(storage.get(hash))
                            .map(b -> b.block)
                            .or(() -> Optional.ofNullable(localCache.get(hash))));
                }
            }

            @Override
            public boolean hasBlock(Cid hash) {
                synchronized (storage) {
                    return storage.containsKey(hash) || localCache.containsKey(hash);
                }
            }

            @Override
            public CompletableFuture<Boolean> clear() {
                throw new IllegalStateException("Unimplemented!");
            }

            @Override
            public long getMaxSize() {
                return 0;
            }

            @Override
            public void setMaxSize(long maxSizeBytes) {

            }
        };
        LocalOnlyStorage localStorage = new LocalOnlyStorage(ramBlockCache,
                () -> committedRoot.isPresent() ?
                        get(owner, committedRoot.get(), Optional.empty())
                                .thenApply(ropt -> ropt.map(WriterData::fromCbor).flatMap(wd -> wd.tree))
                                .thenCompose(champRoot -> target.getChampLookup(owner, (Cid) champRoot.get(), Arrays.asList(new ChunkMirrorCap(champKey, bat)), Optional.empty())) :
                        target.getChampLookup(owner, root, Arrays.asList(new ChunkMirrorCap(champKey, bat)), Optional.empty()), hasher);
        CachingStorage cache = new CachingStorage(localStorage, 100, 1024 * 1024);
        return Futures.asyncExceptionally(() -> Futures.asyncExceptionally(
                                () -> ChampWrapper.create(owner, root, Optional.empty(), x -> Futures.of(x.data), cache, hasher, c -> (CborObject.CborMerkleLink) c),
                                t -> getChampRoot(committedRoot, root, owner, target)
                                        .thenCompose(champRoot -> ChampWrapper.create(owner, champRoot, Optional.empty(), x -> Futures.of(x.data), cache, hasher, c -> (CborObject.CborMerkleLink) c))
                        )
                        .thenCompose(tree -> tree.get(champKey))
                        .thenApply(c -> c.map(x -> x.target).map(MaybeMultihash::of).orElse(MaybeMultihash.empty()))
                        .thenCompose(btreeValue -> {
                            if (btreeValue.isPresent())
                                return Futures.asyncExceptionally(
                                        () -> cache.get(owner, (Cid) btreeValue.get(), bat),
                                        t -> target.get(owner, (Cid) btreeValue.get(), bat).thenCompose(res -> {
                                            if (res.isPresent()) {
                                                // add directly retrieved block to results
                                                ramBlockCache.put((Cid) btreeValue.get(), res.get().serialize());
                                                return cache.get(owner, (Cid) btreeValue.get(), bat);
                                            }
                                            return Futures.of(res);
                                        })
                                );
                            return Futures.of(Optional.empty());
                        }).thenApply(x -> new ArrayList<>(cache.getCached())),
                t -> getChampRoot(committedRoot, root, owner, this)
                        .thenCompose(champRoot -> target.getChampLookup(owner, champRoot, Arrays.asList(new ChunkMirrorCap(champKey, bat)), Optional.empty()))
        );
    }

    @Override
    public CompletableFuture<List<Cid>> put(PublicKeyHash owner,
                                            PublicKeyHash writer,
                                            List<byte[]> signedHashes,
                                            List<byte[]> blocks,
                                            TransactionId tid) {
        return put(writer, blocks, signedHashes, false,Optional.empty());
    }

    @Override
    public CompletableFuture<List<Cid>> putRaw(PublicKeyHash owner,
                                               PublicKeyHash writer,
                                               List<byte[]> signatures,
                                               List<byte[]> blocks,
                                               TransactionId tid,
                                               ProgressConsumer<Long> progressConsumer) {
        return put(writer, blocks, signatures, true, Optional.of(progressConsumer));
    }

    private CompletableFuture<List<Cid>> put(PublicKeyHash writer,
                                             List<byte[]> blocks,
                                             List<byte[]> signatures,
                                             boolean isRaw,
                                             Optional<ProgressConsumer<Long>> progressConsumer) {
        return Futures.combineAllInOrder(IntStream.range(0, blocks.size())
                .mapToObj(i -> hashToCid(blocks.get(i), isRaw)
                        .thenApply(cid -> put(cid, new OpLog.BlockWrite(writer, signatures.get(i), blocks.get(i), isRaw, progressConsumer))))
                .collect(Collectors.toList()));
    }

    private synchronized Cid put(Cid cid, OpLog.BlockWrite block) {
        synchronized (storage) {
            storage.put(cid, block);
            if (cid.isRaw())
                block.progressMonitor.ifPresent(m -> m.accept((long)block.block.length));
        }
        return cid;
    }

    @Override
    public CompletableFuture<List<FragmentWithHash>> downloadFragments(PublicKeyHash owner,
                                                                       List<Cid> hashes,
                                                                       List<BatWithId> bats,
                                                                       Hasher h,
                                                                       ProgressConsumer<Long> monitor,
                                                                       double spaceIncreaseFactor) {
        return NetworkAccess.downloadFragments(owner, hashes, bats, this, h, monitor, spaceIncreaseFactor);
    }

    @Override
    public CompletableFuture<Optional<byte[]>> getRaw(PublicKeyHash owner, Cid hash, Optional<BatWithId> bat) {
        synchronized (storage) {
            OpLog.BlockWrite local = storage.get(hash);
            if (local != null)
                return Futures.of(Optional.of(local.block));
        }
        return target.getRaw(owner, hash, bat);
    }

    @Override
    public CompletableFuture<Optional<CborObject>> get(PublicKeyHash owner, Cid hash, Optional<BatWithId> bat) {
        return getRaw(owner, hash, bat)
                .thenApply(opt -> opt.map(CborObject::fromByteArray));
    }

    @Override
    public CompletableFuture<Cid> put(PublicKeyHash owner,
                                      SigningPrivateKeyAndPublicHash writer,
                                      byte[] block,
                                      Hasher hasher,
                                      TransactionId tid) {
        // Do NOT do signature as this block will likely be GC'd before being committed, so we can delay calculating signatures until commit
        return put(writer.publicKeyHash, Collections.singletonList(block), Collections.singletonList(new byte[0]), false, Optional.empty())
                .thenApply(hashes -> hashes.get(0));
    }

    public CompletableFuture<Map<Cid, OpLog.BlockWrite>> signBlocks(Map<PublicKeyHash, SigningPrivateKeyAndPublicHash> writers) {
        synchronized (storage) {
            List<Pair<Cid, OpLog.BlockWrite>> writes = storage.entrySet()
                    .stream()
                    .map(e -> new Pair<>(e.getKey(), e.getValue()))
                    .collect(Collectors.toList());
            return Futures.combineAllInOrder(writes.stream()
                            .map(w -> {
                                OpLog.BlockWrite block = w.right;
                                return (block.signature.length > 0 ?
                                        Futures.of(block.signature) :
                                        writers.get(block.writer).secret.signMessage(w.left.getHash()))
                                        .thenApply(sig -> new Pair<>(w.left, new OpLog.BlockWrite(block.writer,
                                                sig,
                                                block.block, block.isRaw, block.progressMonitor)));
                            }).collect(Collectors.toList()))
                    .thenApply(all -> {
                        if (all.stream().map(p ->p.right).anyMatch(bw -> bw.signature.length == 0))
                            throw new IllegalStateException("Blocks with empty signature!");
                        return all.stream()
                                .collect(Collectors.toMap(p -> p.left, p -> p.right));
                    });
        }
    }

    public void gc(List<Cid> roots) {
        synchronized (storage) {
            List<Cid> all = new ArrayList<>(storage.keySet());
            List<Boolean> reachable = new ArrayList<>();
            for (int i = 0; i < all.size(); i++)
                reachable.add(false);
            for (Cid root : roots) {
                markReachable(root, reachable, all, storage);
            }
            for (int i = 0; i < all.size(); i++) {
                if (!reachable.get(i))
                    storage.remove(all.get(i));
            }
        }
    }

    private static void markReachable(Cid current, List<Boolean> reachable, List<Cid> all, Map<Cid, OpLog.BlockWrite> storage) {
        OpLog.BlockWrite block = storage.get(current);
        if (block == null)
            return;
        int index = all.indexOf(current);
        reachable.set(index, true);

        if (current.isRaw())
            return;
        List<Multihash> links = CborObject.fromByteArray(block.block).links();
        for (Multihash link : links) {
            markReachable((Cid)link, reachable, all, storage);
        }
    }

    public List<Pair<BufferedPointers.WriterUpdate, Optional<CommittedWriterData>>> getAllWriterData(List<BufferedPointers.WriterUpdate> updates) {
        synchronized (storage) {
            return updates.stream()
                    .map(u -> new Pair<>(u, u.currentHash.map(h -> new CommittedWriterData(u.currentHash,
                            WriterData.fromCbor(CborObject.fromByteArray(storage.get(h).block)), u.currentSequence))))
                    .collect(Collectors.toList());
        }
    }

    /** Commit the blocks for a given writer
     *
     * @param owner
     * @param writer
     * @param tid
     * @return
     */
    public CompletableFuture<Boolean> commit(PublicKeyHash owner,
                                             PublicKeyHash writer,
                                             TransactionId tid,
                                             Map<Cid, OpLog.BlockWrite> signed) {
        // write blocks in batches of up to 50 all in 1 transaction
        List<OpLog.BlockWrite> forWriter = new ArrayList<>();
        Set<Cid> toRemove = new HashSet<>();
        synchronized (storage) {
            for (Map.Entry<Cid, OpLog.BlockWrite> e : signed.entrySet()) {
                if (!Objects.equals(e.getValue().writer, writer))
                    continue;
                forWriter.add(e.getValue());
                toRemove.add(e.getKey());
            }
            toRemove.forEach(storage::remove);
        }

        int maxBlocksPerBatch = ContentAddressedStorage.MAX_BLOCK_AUTHS;
        List<List<OpLog.BlockWrite>> cborBatches = new ArrayList<>();
        List<List<OpLog.BlockWrite>> rawBatches = new ArrayList<>();
        List<List<OpLog.BlockWrite>> smallRawBatches = new ArrayList<>();

        int cborCount = 0, rawcount = 0, smallRawCount = 0;
        int smallBlockMax = DirectS3BlockStore.MAX_SMALL_BLOCK_SIZE;
        if (! cborBatches.isEmpty() && ! cborBatches.get(cborBatches.size() - 1).isEmpty())
            cborBatches.add(new ArrayList<>());
        if (! rawBatches.isEmpty() && ! rawBatches.get(rawBatches.size() - 1).isEmpty())
            rawBatches.add(new ArrayList<>());
        if (! smallRawBatches.isEmpty() && ! smallRawBatches.get(rawBatches.size() - 1).isEmpty())
            smallRawBatches.add(new ArrayList<>());
        for (OpLog.BlockWrite val : forWriter) {
            List<List<OpLog.BlockWrite>> batches = val.isRaw ? val.block.length < smallBlockMax ? smallRawBatches : rawBatches : cborBatches;
            int count = val.isRaw ? val.block.length < smallBlockMax ? smallRawCount : rawcount : cborCount;
            if (count % maxBlocksPerBatch == 0)
                batches.add(new ArrayList<>());
            batches.get(batches.size() - 1).add(val);
            count = (count + 1) % maxBlocksPerBatch;
            if (val.isRaw) {
                if (val.block.length < smallBlockMax)
                    smallRawCount = count;
                else
                    rawcount = count;
            } else
                cborCount = count;
        }
        return Futures.combineAllInOrder(Stream.concat(
                                rawBatches.stream().map(bs -> new Pair<>(true, bs)),
                                Stream.concat(
                                        smallRawBatches.stream().map(bs -> new Pair<>(true, bs)),
                                        cborBatches.stream().map(bs -> new Pair<>(false, bs))))
                        .filter(p -> ! p.right.isEmpty())
                        .map(p -> p.left ?
                                target.putRaw(owner, writer,
                                                p.right.stream().map(w -> w.signature).collect(Collectors.toList()),
                                                p.right.stream().map(w -> w.block).collect(Collectors.toList()), tid, x-> {})
                                        .thenApply(res -> {
                                            p.right.stream().forEach(w ->  w.progressMonitor.ifPresent(m -> m.accept((long)w.block.length)));
                                            return res;
                                        }) :
                                target.put(owner, writer,
                                        p.right.stream().map(w -> w.signature).collect(Collectors.toList()),
                                        p.right.stream().map(w -> w.block).collect(Collectors.toList()), tid))
                        .collect(Collectors.toList()))
                .thenApply(a -> true);
    }

    public BufferedStorage clone() {
        return new BufferedStorage(target, hasher);
    }

    public BufferedStorage withStorage(Function<ContentAddressedStorage, ContentAddressedStorage> modifiedStorage) {
        return new BufferedStorage(modifiedStorage.apply(target), hasher);
    }

    public synchronized void clear() {
        storage.clear();
    }

    public int size() {
        synchronized (storage) {
            return storage.size();
        }
    }

    @Override
    public CompletableFuture<Optional<Integer>> getSize(PublicKeyHash owner, Multihash block) {
        synchronized (storage) {
            if (!storage.containsKey(block))
                return target.getSize(owner, block);
            return CompletableFuture.completedFuture(Optional.of(storage.get(block).block.length));
        }
    }

    public CompletableFuture<Cid> hashToCid(byte[] input, boolean isRaw) {
        return hasher.hash(input, isRaw);
    }

    public int totalSize() {
        synchronized (storage) {
            return storage.values().stream().mapToInt(a -> a.block.length).sum();
        }
    }
}
