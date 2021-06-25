package peergos.server.storage;

import peergos.server.corenode.*;
import peergos.shared.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.hash.*;
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

    public GarbageCollector(DeletableContentAddressedStorage storage, JdbcIpnsAndSocial pointers) {
        this.storage = storage;
        this.pointers = pointers;
    }

    public synchronized void collect(Function<Stream<Map.Entry<PublicKeyHash, byte[]>>, CompletableFuture<Boolean>> snapshotSaver) {
        collect(storage, pointers, snapshotSaver);
    }

    public void start(long periodMillis, Function<Stream<Map.Entry<PublicKeyHash, byte[]>>, CompletableFuture<Boolean>> snapshotSaver) {
        new Thread(() -> {
            while (true) {
                try {
                    collect(snapshotSaver);
                    Thread.sleep(periodMillis);
                } catch (Exception e) {
                    LOG.log(Level.SEVERE, e, e::getMessage);
                }
            }
        }, "Garbage Collector").start();
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
                               Function<Stream<Map.Entry<PublicKeyHash, byte[]>>, CompletableFuture<Boolean>> snapshotSaver) {
        System.out.println("Starting blockstore garbage collection on node " + storage.id().join() + "...");
        // TODO: do this more efficiently with a bloom filter, and actual streaming and multithreading
        long t0 = System.nanoTime();
        List<Multihash> present = storage.getAllBlockHashes().collect(Collectors.toList());
        long t1 = System.nanoTime();
        System.out.println("Listing " + present.size() + " blocks took " + (t1-t0)/1_000_000_000 + "s");

        List<Multihash> pending = storage.getOpenTransactionBlocks();
        long t2 = System.nanoTime();
        System.out.println("Listing " + pending.size() + " pending blocks took " + (t2-t1)/1_000_000_000 + "s");

        // This pointers call must happen AFTER the previous two for correctness
        Map<PublicKeyHash, byte[]> allPointers = pointers.getAllEntries();
        long t3 = System.nanoTime();
        System.out.println("Listing " + allPointers.size() + " pointers took " + (t3-t2)/1_000_000_000 + "s");

        Map<Multihash, Integer> toIndex = new HashMap<>();
        for (int i=0; i < present.size(); i++)
            toIndex.put(present.get(i), i);
        BitSet reachable = new BitSet(present.size());

        int markParallelism = 10;
        ForkJoinPool markPool = new ForkJoinPool(markParallelism);
        List<ForkJoinTask<Boolean>> marked = allPointers.entrySet().stream()
                .map(e -> markPool.submit(() -> markReachable(e.getKey(), e.getValue(), reachable, toIndex, storage)))
                .collect(Collectors.toList());
        marked.forEach(f -> f.join());

        for (Multihash additional : pending) {
            int index = toIndex.getOrDefault(additional, -1);
            if (index >= 0)
                reachable.set(index);
        }
        long t4 = System.nanoTime();
        System.out.println("Marking reachable took " + (t4-t3)/1_000_000_000 + "s");

        // Save pointers snapshot
        snapshotSaver.apply(allPointers.entrySet().stream()).join();

        int deleteParallelism = 4;
        ForkJoinPool pool = new ForkJoinPool(deleteParallelism);
        int batchSize = present.size() / deleteParallelism;
        AtomicLong progressCounter = new AtomicLong(0);
        List<ForkJoinTask<Pair<Long, Long>>> futures = IntStream.range(0, deleteParallelism)
                .mapToObj(i -> pool.submit(() -> deleteUnreachableBlocks(i * batchSize,
                        Math.min((i + 1) * batchSize, present.size()), reachable, present, progressCounter, storage)))
                .collect(Collectors.toList());
        Pair<Long, Long> deleted = futures.stream()
                .map(ForkJoinTask::join).reduce((a, b) -> new Pair<>(a.left + b.left, a.right + b.right))
                .get();
        long deletedBlocks = deleted.left;
        long deletedSize = deleted.right;
        long t5 = System.nanoTime();
        System.out.println("Deleting blocks took " + (t5-t4)/1_000_000_000 + "s");
        System.out.println("GC complete. Freed " + deletedBlocks + " blocks totalling " + deletedSize + " bytes in " + (t5-t0)/1_000_000_000 + "s");
    }

    private static boolean markReachable(PublicKeyHash writerHash,
                                         byte[] signedRawCas,
                                         BitSet reachable,
                                         Map<Multihash, Integer> toIndex,
                                         DeletableContentAddressedStorage storage) {
        PublicSigningKey writer = getWithBackoff(() -> storage.getSigningKey(writerHash).join().get());
        byte[] bothHashes = writer.unsignMessage(signedRawCas);
        HashCasPair cas = HashCasPair.fromCbor(CborObject.fromByteArray(bothHashes));
        MaybeMultihash updated = cas.updated;
        if (updated.isPresent())
            markReachable(storage, updated.get(), toIndex, reachable);
        return true;
    }

    private static Pair<Long, Long> deleteUnreachableBlocks(int startIndex,
                                                            int endIndex,
                                                            BitSet reachable,
                                                            List<Multihash> present,
                                                            AtomicLong progress,
                                                            DeletableContentAddressedStorage storage) {
        long deletedBlocks = 0, deletedSize = 0;
        long logPoint = startIndex;
        final int maxDeleteCount = 1000;
        long pendingDeleteSize = 0;
        long ignoredBlocks = 0;
        List<Multihash> pendingDeletes = new ArrayList<>();
        for (int i = reachable.nextClearBit(startIndex); i >= startIndex && i < endIndex; i = reachable.nextClearBit(i + 1)) {
            Multihash hash = present.get(i);
            try {
                int size = getWithBackoff(() -> storage.getSize(hash).join().get());
                deletedBlocks++;
                pendingDeleteSize += size;
                pendingDeletes.add(hash);
            } catch (Exception e) {
                ignoredBlocks++;
                if (ignoredBlocks < 10)
                    e.printStackTrace();
            }
            if (pendingDeletes.size() >= maxDeleteCount) {
                getWithBackoff(() -> {storage.bulkDelete(pendingDeletes); return true;});
                deletedSize += pendingDeleteSize;
                pendingDeleteSize = 0;
                deletedBlocks += pendingDeletes.size();
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
            deletedSize += pendingDeleteSize;
            deletedBlocks += pendingDeletes.size();
        }

        if (ignoredBlocks > 0)
            System.out.println("Ignored blocks in delete phase: " + ignoredBlocks);
        return new Pair<>(deletedBlocks, deletedSize);
    }

    private static void markReachable(ContentAddressedStorage storage,
                                      Multihash root,
                                      Map<Multihash, Integer> toIndex,
                                      BitSet reachable) {
        int index = toIndex.getOrDefault(root, -1);
        if (index >= 0) {
            synchronized (reachable) {
                reachable.set(index);
            }
        }
        List<Multihash> links = getWithBackoff(() -> storage.getLinks(root).join());
        for (Multihash link : links) {
            markReachable(storage, link, toIndex, reachable);
        }
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
