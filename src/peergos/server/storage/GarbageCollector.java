package peergos.server.storage;

import peergos.server.corenode.*;
import peergos.server.space.*;
import peergos.shared.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.cid.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.mutable.*;
import peergos.shared.storage.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import java.util.logging.*;
import java.util.stream.*;

public class GarbageCollector {
    private static final Logger LOG = Logger.getGlobal();

    private final DeletableContentAddressedStorage storage;
    private final JdbcIpnsAndSocial pointers;
    private final UsageStore usage;
    private final BlockMetadataStore metadata;

    public GarbageCollector(DeletableContentAddressedStorage storage,
                            JdbcIpnsAndSocial pointers,
                            UsageStore usage) {
        this.storage = storage;
        this.pointers = pointers;
        this.usage = usage;
        this.metadata = storage.getBlockMetadataStore().orElseGet(RamBlockMetadataStore::new);
    }

    public synchronized void collect(Function<Stream<Map.Entry<PublicKeyHash, byte[]>>, CompletableFuture<Boolean>> snapshotSaver) {
        collect(storage, pointers, usage, snapshotSaver, metadata);
    }

    public void start(long periodMillis, Function<Stream<Map.Entry<PublicKeyHash, byte[]>>, CompletableFuture<Boolean>> snapshotSaver) {
        Thread garbageCollector = new Thread(() -> {
            while (true) {
                try {
                    collect(snapshotSaver);
                    Thread.sleep(periodMillis);
                } catch (Exception e) {
                    LOG.log(Level.SEVERE, e, e::getMessage);
                }
            }
        }, "Garbage Collector");
        garbageCollector.setDaemon(true);
        garbageCollector.start();
    }

    /** The result of this method is a snapshot of the mutable pointers that is consistent with the blocks store
     * after GC has completed (saved to a file which can be independently backed up).
     *
     * @param storage
     * @param pointers
     * @param snapshotSaver
     * @return
     */
    public static void collect(DeletableContentAddressedStorage storage,
                               JdbcIpnsAndSocial pointers,
                               UsageStore usage,
                               Function<Stream<Map.Entry<PublicKeyHash, byte[]>>, CompletableFuture<Boolean>> snapshotSaver,
                               BlockMetadataStore metadata) {
        System.out.println("Starting blockstore garbage collection on node " + storage.id().join() + "...");
        // TODO: do this more efficiently with a bloom filter, and actual streaming and multithreading
        storage.clearOldTransactions(System.currentTimeMillis() - 24*3600*1000L);
        long t0 = System.nanoTime();
        List<Multihash> present = storage.getAllBlockHashes().collect(Collectors.toList());
        long t1 = System.nanoTime();
        System.out.println("Listing " + present.size() + " blocks took " + (t1-t0)/1_000_000_000 + "s");

        List<Multihash> pending = storage.getOpenTransactionBlocks();
        long t2 = System.nanoTime();
        System.out.println("Listing " + pending.size() + " pending blocks took " + (t2-t1)/1_000_000_000 + "s");

        // This pointers call must happen AFTER the block and pending listing for correctness
        Map<PublicKeyHash, byte[]> allPointers = pointers.getAllEntries();
        long t3 = System.nanoTime();
        System.out.println("Listing " + allPointers.size() + " pointers took " + (t3-t2)/1_000_000_000 + "s");

        // Get the current roots from the usage store which shouldn't be GC'd until usage has been updated
        List<Multihash> usageRoots = usage.getAllTargets();

        Map<Multihash, Integer> toIndex = new HashMap<>();
        for (int i=0; i < present.size(); i++)
            toIndex.put(present.get(i), i);
        BitSet reachable = new BitSet(present.size());

        int markParallelism = 10;
        ForkJoinPool markPool = new ForkJoinPool(markParallelism);
        List<ForkJoinTask<Boolean>> usageMarked = usageRoots.stream()
                .map(r -> markPool.submit(() -> markReachable(storage, (Cid)r, toIndex, reachable, metadata)))
                .collect(Collectors.toList());
        usageMarked.forEach(f -> f.join());
        long t4 = System.nanoTime();
        System.out.println("Marking reachable from "+usageRoots.size()+" usage roots took " + (t4-t3)/1_000_000_000 + "s");

        Set<Multihash> fromUsage = new HashSet<>(usageRoots);
        List<ForkJoinTask<Boolean>> marked = allPointers.entrySet().stream()
                .map(e -> markPool.submit(() -> markReachable(e.getKey(), e.getValue(), reachable, toIndex, storage, fromUsage, metadata)))
                .collect(Collectors.toList());
        long rootsProcessed = marked.stream().filter(ForkJoinTask::join).count();

        long t5 = System.nanoTime();
        System.out.println("Marking reachable from "+rootsProcessed+" pointers took " + (t5-t4)/1_000_000_000 + "s");
        for (Multihash additional : pending) {
            int index = toIndex.getOrDefault(additional, -1);
            if (index >= 0)
                reachable.set(index);
        }
        long t6 = System.nanoTime();
        System.out.println("Marking "+pending.size()+" pending blocks reachable took " + (t6-t5)/1_000_000_000 + "s");

        // Save pointers snapshot
        snapshotSaver.apply(allPointers.entrySet().stream()).join();

        int deleteParallelism = 4;
        ForkJoinPool pool = new ForkJoinPool(deleteParallelism);
        int batchSize = present.size() / deleteParallelism;
        AtomicLong progressCounter = new AtomicLong(0);
        List<ForkJoinTask<Pair<Long, Long>>> futures = IntStream.range(0, deleteParallelism)
                .mapToObj(i -> pool.submit(() -> deleteUnreachableBlocks(i * batchSize,
                        Math.min((i + 1) * batchSize, present.size()), reachable, present, progressCounter, storage, metadata)))
                .collect(Collectors.toList());
        Pair<Long, Long> deleted = futures.stream()
                .map(ForkJoinTask::join).reduce((a, b) -> new Pair<>(a.left + b.left, a.right + b.right))
                .get();
        long deletedCborBlocks = deleted.left;
        long deletedRawBlocks = deleted.right;
        long t7 = System.nanoTime();
        metadata.compact();
        long t8 = System.nanoTime();
        System.out.println("Deleting blocks took " + (t7-t6)/1_000_000_000 + "s");
        System.out.println("GC complete. Freed " + deletedCborBlocks + " cbor blocks and " + deletedRawBlocks +
                " raw blocks, total duration: " + (t7-t0)/1_000_000_000 + "s, metadata.compact took " + (t8-t7)/1_000_000_000 + "s");
    }

    private static boolean markReachable(PublicKeyHash writerHash,
                                         byte[] signedRawCas,
                                         BitSet reachable,
                                         Map<Multihash, Integer> toIndex,
                                         DeletableContentAddressedStorage storage,
                                         Set<Multihash> done,
                                         BlockMetadataStore metadata) {
        PublicSigningKey writer = getWithBackoff(() -> storage.getSigningKey(writerHash).join().get());
        byte[] bothHashes = writer.unsignMessage(signedRawCas);
        PointerUpdate cas = PointerUpdate.fromCbor(CborObject.fromByteArray(bothHashes));
        MaybeMultihash updated = cas.updated;
        if (updated.isPresent() && ! done.contains(updated.get())) {
            markReachable(storage, (Cid) updated.get(), toIndex, reachable, metadata);
            return true;
        }
        return false;
    }

    private static Pair<Long, Long> deleteUnreachableBlocks(int startIndex,
                                                            int endIndex,
                                                            BitSet reachable,
                                                            List<Multihash> present,
                                                            AtomicLong progress,
                                                            DeletableContentAddressedStorage storage,
                                                            BlockMetadataStore metadata) {
        long deletedCborBlocks = 0, deletedRawBlocks = 0;
        long logPoint = startIndex;
        final int maxDeleteCount = 1000;
        List<Multihash> pendingDeletes = new ArrayList<>();
        for (int i = reachable.nextClearBit(startIndex); i >= startIndex && i < endIndex; i = reachable.nextClearBit(i + 1)) {
            Multihash hash = present.get(i);
            if (hash instanceof Cid && ((Cid) hash).isRaw())
                deletedRawBlocks++;
            else
                deletedCborBlocks++;
            pendingDeletes.add(hash);

            if (pendingDeletes.size() >= maxDeleteCount) {
                getWithBackoff(() -> {storage.bulkDelete(pendingDeletes); return true;});
                for (Multihash block : pendingDeletes) {
                    if (block instanceof Cid)
                        metadata.remove((Cid)block);
                }
                pendingDeletes.clear();
            }

            int tenth = (endIndex - startIndex) / 10;
            if (i > logPoint + tenth) {
                logPoint += tenth;
                long updatedProgress = progress.addAndGet(tenth);
                if (updatedProgress * 10 / present.size() > (updatedProgress - tenth) * 10 / present.size())
                    System.out.println("Deleting unreachable blocks: " + updatedProgress * 100 / present.size() + "% done");
            }
        }
        if (pendingDeletes.size() > 0) {
            getWithBackoff(() -> {storage.bulkDelete(pendingDeletes); return true;});
        }

        return new Pair<>(deletedCborBlocks, deletedRawBlocks);
    }

    private static boolean markReachable(DeletableContentAddressedStorage storage,
                                         Cid root,
                                         Map<Multihash, Integer> toIndex,
                                         BitSet reachable,
                                         BlockMetadataStore metadata) {
        int index = toIndex.getOrDefault(root, -1);
        if (index >= 0) {
            synchronized (reachable) {
                reachable.set(index);
            }
        }
        List<Cid> links = metadata.get(root).map(m -> m.links)
                .orElseGet(() -> getWithBackoff(() -> storage.getLinks(root, "").join()));
        for (Cid link : links) {
            markReachable(storage, link, toIndex, reachable, metadata);
        }
        return true;
    }

    private static <V> V getWithBackoff(Supplier<V> req) {
        long sleep = 1000;
        for (int i=0; i < 20; i++) {
            try {
                return req.get();
            } catch (RateLimitException e) {
                try {
                    Thread.sleep(sleep);
                } catch (InterruptedException f) {}
                sleep *= 2;
            }
        }
        throw new IllegalStateException("Couldn't process request because of rate limit!");
    }
}
