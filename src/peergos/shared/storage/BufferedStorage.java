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
    /** Blocks taken out of the buffer for a commit that hasn't landed yet. They are still ours to serve:
     *  until the server has them, a reader that goes looking would find them in neither place. */
    private final Map<Cid, OpLog.BlockWrite> inFlight = new LinkedHashMap<>();
    private int bufferedBytes = 0;
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
            return storage.containsKey(c) || inFlight.containsKey(c);
        }
    }

    /** A block we hold, whether it is still awaiting a commit or already in one that is in flight. */
    private OpLog.BlockWrite buffered(Cid hash) {
        synchronized (storage) {
            OpLog.BlockWrite block = storage.get(hash);
            return block != null ? block : inFlight.get(hash);
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
        if (isEmpty() || ! hasBufferedBlock(root))
            return Futures.asyncExceptionally(
                    () -> target.getChampLookup(owner, root, caps, committedRoot),
                    t -> getChampRoot(committedRoot, root, owner, this)
                            .thenCompose(updatedRoot -> target.getChampLookup(owner, updatedRoot, caps, Optional.empty()))
                    );
        // If we are in a write transaction, first try local traversal for all caps using only
        // buffered blocks (no HTTP). Caps that can't be resolved locally are then batched into
        // a single remote call instead of making one HTTP call per cap.
        List<CompletableFuture<Optional<List<byte[]>>>> localAttempts = caps.stream()
                .map(cap -> tryLocalChampLookup(owner, root, cap.mapKey, cap.bat))
                .collect(Collectors.toList());
        return Futures.combineAllInOrder(localAttempts)
                .thenCompose(localResults -> {
                    List<byte[]> localBlocks = new ArrayList<>();
                    List<ChunkMirrorCap> remoteCaps = new ArrayList<>();
                    for (int i = 0; i < localResults.size(); i++) {
                        if (localResults.get(i).isPresent())
                            localBlocks.addAll(localResults.get(i).get());
                        else
                            remoteCaps.add(caps.get(i));
                    }
                    if (remoteCaps.isEmpty())
                        return Futures.of(localBlocks);
                    // Single batch HTTP call for all caps that couldn't be resolved from the buffer
                    return Futures.asyncExceptionally(
                            () -> getChampRoot(committedRoot, root, owner, this)
                                    .thenCompose(champRoot -> target.getChampLookup(owner, champRoot, remoteCaps, Optional.empty())),
                            t -> target.getChampLookup(owner, root, remoteCaps, committedRoot)
                    ).thenApply(remoteBlocks -> {
                        List<byte[]> all = new ArrayList<>(localBlocks);
                        all.addAll(remoteBlocks);
                        // The remote call used committedChampRoot (not root), so the buffered
                        // root block itself is absent from remoteBlocks. Include it so that any
                        // caller building a LocalRamStorage from these blocks can serve
                        // ChampWrapper.create(root, ..., fromBlocks) without "Champ root not present".
                        OpLog.BlockWrite rootWrite = buffered(root);
                        if (rootWrite != null)
                            all.add(rootWrite.block);
                        return all;
                    });
                });
    }

    /** Try a CHAMP lookup for a single key using only buffered blocks — no HTTP fallback.
     *  Returns Optional.empty() if any required block is absent from the buffer. */
    private CompletableFuture<Optional<List<byte[]>>> tryLocalChampLookup(PublicKeyHash owner, Cid root,
                                                                           byte[] champKey, Optional<BatWithId> bat) {
        BlockCache bufferOnlyCache = new BlockCache() {
            final Map<Cid, byte[]> localCache = new HashMap<>();

            @Override
            public CompletableFuture<Boolean> put(Cid hash, byte[] data) {
                localCache.put(hash, data);
                return Futures.of(true);
            }

            @Override
            public CompletableFuture<Optional<byte[]>> get(Cid hash) {
                return Futures.of(Optional.ofNullable(buffered(hash))
                        .map(b -> b.block)
                        .or(() -> Optional.ofNullable(localCache.get(hash))));
            }

            @Override
            public boolean hasBlock(Cid hash) {
                return buffered(hash) != null || localCache.containsKey(hash);
            }

            @Override
            public CompletableFuture<Boolean> clear() {
                throw new IllegalStateException("Unimplemented!");
            }

            @Override
            public long getMaxSize() { return 0; }

            @Override
            public void setMaxSize(long maxSizeBytes) {}
        };
        LocalOnlyStorage pureLocal = new LocalOnlyStorage(bufferOnlyCache,
                () -> Futures.errored(new RuntimeException("block not in buffer")), hasher);
        CachingStorage cache = new CachingStorage(pureLocal, 100, 1024 * 1024);
        return Futures.asyncExceptionally(
                () -> Futures.asyncExceptionally(
                        () -> ChampWrapper.create(owner, root, Optional.empty(), x -> Futures.of(x.data), cache, hasher, c -> (CborObject.CborMerkleLink) c),
                        t -> getChampRoot(Optional.empty(), root, owner, pureLocal)
                                .thenCompose(champRoot -> ChampWrapper.create(owner, champRoot, Optional.empty(), x -> Futures.of(x.data), cache, hasher, c -> (CborObject.CborMerkleLink) c))
                )
                .thenCompose(tree -> tree.get(champKey))
                .thenApply(c -> c.map(x -> x.target).map(MaybeMultihash::of).orElse(MaybeMultihash.empty()))
                .thenCompose(btreeValue -> {
                    if (btreeValue.isPresent())
                        return cache.get(owner, (Cid) btreeValue.get(), bat);
                    return Futures.of(Optional.empty());
                })
                .thenApply(x -> Optional.of(new ArrayList<>(cache.getCached()))),
                t -> Futures.of(Optional.empty()));
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
                return Futures.of(Optional.ofNullable(buffered(hash))
                        .map(b -> b.block)
                        .or(() -> Optional.ofNullable(localCache.get(hash))));
            }

            @Override
            public boolean hasBlock(Cid hash) {
                return buffered(hash) != null || localCache.containsKey(hash);
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
            OpLog.BlockWrite existing = storage.put(cid, block);
            bufferedBytes += block.block.length - (existing == null ? 0 : existing.block.length);
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
        OpLog.BlockWrite local = buffered(hash);
        if (local != null)
            return Futures.of(Optional.of(local.block));
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

    public void gc(List<Cid> roots) {
        synchronized (storage) {
            Set<Cid> reachable = new HashSet<>();
            for (Cid root : roots) {
                markReachable(root, reachable, storage);
            }
            if (reachable.size() == storage.size())
                return;
            List<Cid> unreachable = storage.keySet().stream()
                    .filter(c -> ! reachable.contains(c))
                    .collect(Collectors.toList());
            unreachable.forEach(this::remove);
        }
    }

    /** Remove a buffered block. Must be called whilst holding the storage monitor.
     */
    private void remove(Cid cid) {
        OpLog.BlockWrite removed = storage.remove(cid);
        if (removed != null)
            bufferedBytes -= removed.block.length;
    }

    private static void markReachable(Cid current, Set<Cid> reachable, Map<Cid, OpLog.BlockWrite> storage) {
        OpLog.BlockWrite block = storage.get(current);
        if (block == null)
            return;
        if (! reachable.add(current))
            return;

        if (current.isRaw())
            return;
        List<Multihash> links = CborObject.fromByteArray(block.block).links();
        for (Multihash link : links) {
            markReachable((Cid)link, reachable, storage);
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

    /** Whether any buffered block is too large to travel inline, and so must be written under a
     *  transaction that keeps it alive until the commit naming it lands.
     */
    public boolean needsTransaction() {
        synchronized (storage) {
            return storage.values().stream()
                    .anyMatch(b -> b.isRaw && b.block.length >= DirectS3BlockStore.MAX_SMALL_BLOCK_SIZE);
        }
    }

    /** Assign each buffered block to the first of these roots that reaches it.
     *
     *  Which writer wrote a block is not a reliable guide to which root reaches it - the same block can
     *  be written under two writers, and only the last one is recorded - and a commit is only accepted
     *  if every block in it hangs off the root it signs, so the graph decides.
     */
    public List<Set<Cid>> partitionByRoot(List<MaybeMultihash> roots) {
        synchronized (storage) {
            Set<Cid> claimed = new HashSet<>();
            List<Set<Cid>> partitions = new ArrayList<>();
            for (MaybeMultihash root : roots) {
                Set<Cid> reachable = new HashSet<>();
                root.toOptional().ifPresent(h -> markReachable((Cid) h, reachable, storage));
                reachable.removeAll(claimed);
                claimed.addAll(reachable);
                partitions.add(reachable);
            }
            return partitions;
        }
    }

    /** Take the given buffered blocks and partition them into a commit for one writer.
     *
     *  Large raw blocks are written first, direct to S3 where that is available, so only their
     *  hashes travel in the commit itself. Everything else travels inline.
     */
    public CompletableFuture<WriterCommit> buildWriterCommit(PublicKeyHash owner,
                                                             PublicKeyHash writer,
                                                             Optional<SignedPointerUpdate> pointer,
                                                             SigningPrivateKeyAndPublicHash signer,
                                                             Optional<TransactionId> tid,
                                                             Set<Cid> blocks) {
        List<byte[]> cborBlocks = new ArrayList<>();
        List<byte[]> rawBlocks = new ArrayList<>();
        List<OpLog.BlockWrite> inlineRaw = new ArrayList<>();
        List<Pair<Cid, OpLog.BlockWrite>> large = new ArrayList<>();
        synchronized (storage) {
            List<Cid> toRemove = new ArrayList<>();
            for (Map.Entry<Cid, OpLog.BlockWrite> e : storage.entrySet()) {
                OpLog.BlockWrite block = e.getValue();
                if (! blocks.contains(e.getKey()))
                    continue;
                toRemove.add(e.getKey());
                if (! block.isRaw)
                    cborBlocks.add(block.block);
                else if (block.block.length < DirectS3BlockStore.MAX_SMALL_BLOCK_SIZE) {
                    rawBlocks.add(block.block);
                    inlineRaw.add(block);
                } else
                    large.add(new Pair<>(e.getKey(), block));
            }
            for (Cid claimed : toRemove) {
                OpLog.BlockWrite block = storage.get(claimed);
                remove(claimed);
                inFlight.put(claimed, block);
            }
        }
        return preWrite(owner, writer, signer, large, tid)
                .thenApply(preWritten -> {
                    inlineRaw.forEach(b -> b.progressMonitor.ifPresent(m -> m.accept((long) b.block.length)));
                    return new WriterCommit(writer, cborBlocks, rawBlocks, preWritten, pointer, Optional.empty());
                });
    }

    /** Write the blocks that are too large to travel inline, in batches, and return their hashes. */
    private CompletableFuture<List<Cid>> preWrite(PublicKeyHash owner,
                                                  PublicKeyHash writer,
                                                  SigningPrivateKeyAndPublicHash signer,
                                                  List<Pair<Cid, OpLog.BlockWrite>> large,
                                                  Optional<TransactionId> tid) {
        if (large.isEmpty())
            return Futures.of(Collections.emptyList());
        if (tid.isEmpty())
            throw new IllegalStateException("Blocks written ahead of a commit need a transaction to hold them!");
        int MAX_CONCURRENT_BATCH_UPLOADS = 4;
        AsyncSemaphore semaphore = new AsyncSemaphore(MAX_CONCURRENT_BATCH_UPLOADS);
        List<CompletableFuture<List<Cid>>> futures = new ArrayList<>();
        for (List<Pair<Cid, OpLog.BlockWrite>> batch : ArrayOps.group(large, ContentAddressedStorage.MAX_BLOCK_AUTHS)) {
            CompletableFuture<List<Cid>> work = semaphore.acquire()
                    .thenCompose(v -> Futures.combineAllInOrder(batch.stream()
                                    .map(p -> p.right.signature.length > 0 ?
                                            Futures.of(p.right.signature) :
                                            signer.secret.signMessage(p.left.getHash()))
                                    .collect(Collectors.toList()))
                            .thenCompose(sigs -> target.putRaw(owner, writer, sigs,
                                    batch.stream().map(p -> p.right.block).collect(Collectors.toList()), tid.get(), x -> {}))
                            .thenApply(res -> {
                                batch.forEach(p -> p.right.progressMonitor.ifPresent(m -> m.accept((long) p.right.block.length)));
                                return res;
                            }));
            work.exceptionally(t -> {
                semaphore.release();
                return null;
            });
            futures.add(work.thenApply(r -> {
                semaphore.release();
                return r;
            }));
        }
        return Futures.combineAllInOrder(futures)
                .thenApply(groups -> groups.stream().flatMap(List::stream).collect(Collectors.toList()));
    }

    public BufferedStorage clone() {
        return new BufferedStorage(target, hasher);
    }

    public BufferedStorage withStorage(Function<ContentAddressedStorage, ContentAddressedStorage> modifiedStorage) {
        return new BufferedStorage(modifiedStorage.apply(target), hasher);
    }

    public synchronized void clear() {
        synchronized (storage) {
            storage.clear();
            inFlight.clear();
            bufferedBytes = 0;
        }
    }

    public int size() {
        synchronized (storage) {
            return storage.size();
        }
    }

    @Override
    public CompletableFuture<Optional<Integer>> getSize(PublicKeyHash owner, Multihash block) {
        OpLog.BlockWrite local = block instanceof Cid ? buffered((Cid) block) : null;
        if (local == null)
            return target.getSize(owner, block);
        return CompletableFuture.completedFuture(Optional.of(local.block.length));
    }

    public CompletableFuture<Cid> hashToCid(byte[] input, boolean isRaw) {
        return hasher.hash(input, isRaw);
    }

    public int totalSize() {
        synchronized (storage) {
            return bufferedBytes;
        }
    }
}
