package peergos.server.storage;

import peergos.server.corenode.*;
import peergos.server.space.*;
import peergos.server.util.*;
import peergos.shared.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.Cid;
import peergos.shared.io.ipfs.Multihash;
import peergos.shared.mutable.*;
import peergos.shared.storage.*;
import peergos.shared.util.*;

import java.io.*;
import java.nio.file.*;
import java.time.*;
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
    private final boolean listRawFromBlockstore;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Path reachabilityDbDir;
    private final BiFunction<Long, Long, CompletableFuture<Boolean>> deleteConfirm;

    public GarbageCollector(DeletableContentAddressedStorage storage,
                            JdbcIpnsAndSocial pointers,
                            UsageStore usage,
                            Path reachabilityDbDir,
                            BiFunction<Long, Long, CompletableFuture<Boolean>> deleteConfirm,
                            boolean listRawFromBlockstore) {
        this.storage = storage;
        this.pointers = pointers;
        this.usage = usage;
        this.reachabilityDbDir = reachabilityDbDir;
        this.deleteConfirm = deleteConfirm;
        this.listRawFromBlockstore = listRawFromBlockstore;
        this.metadata = storage.getBlockMetadataStore().orElseGet(RamBlockMetadataStore::new);
    }

    public synchronized void collect(Function<Stream<Map.Entry<PublicKeyHash, byte[]>>, CompletableFuture<Boolean>> snapshotSaver) {
        collect(storage, pointers, usage, reachabilityDbDir, snapshotSaver, metadata, deleteConfirm, listRawFromBlockstore);
    }

    public void stop() {
        running.set(false);
    }

    public void start(long periodMillis, Function<Stream<Map.Entry<PublicKeyHash, byte[]>>, CompletableFuture<Boolean>> snapshotSaver) {
        running.set(true);
        Thread garbageCollector = new Thread(() -> {
            while (running.get()) {
                try {
                    collect(snapshotSaver);
                } catch (Exception e) {
                    LOG.log(Level.SEVERE, e, e::getMessage);
                }
                try {
                    Thread.sleep(periodMillis);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }
        }, "Garbage Collector");
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            garbageCollector.interrupt();
        }, "Garbage Collector - shutdown"));
        garbageCollector.setDaemon(true);
        garbageCollector.start();
    }

    private static void listBlocks(SqliteBlockReachability reachability,
                                   boolean listFromBlockstore,
                                   DeletableContentAddressedStorage storage,
                                   BlockMetadataStore metadata) {
        // the reachability store dedupes on cid + version to guarantee no duplicates which would result in data loss
        if (listFromBlockstore)
            storage.getAllBlockHashVersions(reachability::addBlocks);
        else {
            storage.getAllRawBlockVersions(reachability::addBlocks);
            metadata.listCbor(reachability::addBlocks);
        }
    }

    public static void checkIntegrity(DeletableContentAddressedStorage storage,
                                      BlockMetadataStore metadata,
                                      JdbcIpnsAndSocial pointers,
                                      UsageStore usage) {
        Map<PublicKeyHash, byte[]> allPointers = pointers.getAllEntries();

        List<Pair<Multihash, String>> usageRoots = usage.getAllTargets();
        Set<Multihash> done = new HashSet<>();
        System.out.println("Checking integrity from pointer targets...");
        allPointers.forEach((writerHash, signedRawCas) -> {
            PublicSigningKey writer = getWithBackoff(() -> storage.getSigningKey(null, writerHash).join().get());
            byte[] bothHashes = writer.unsignMessage(signedRawCas).join();
            PointerUpdate cas = PointerUpdate.fromCbor(CborObject.fromByteArray(bothHashes));
            MaybeMultihash updated = cas.updated;
            if (updated.isPresent() && !done.contains(updated.get())) {
                done.add(updated.get());
                try {
                    traverseDag(updated.get(), metadata, done);
                } catch (Exception e) {
                    try {
                        String username = usage.getUsage(writerHash).owner;
                        String msg = "Error marking reachable for user: " + username + ", writer " + writerHash + " " + e.getMessage();
                        System.err.println(msg);
                    } catch (Exception f) {
                        System.err.println("Error processing writer: " + e.getMessage() + " " + f.getMessage());
                    }
                }
            }
        });
        System.out.println("Checking integrity from usage roots...");

        for (Pair<Multihash, String> usageRoot : usageRoots) {
            if (! done.contains(usageRoot.left)) {
                try {
                traverseDag(usageRoot.left, metadata, done);
                } catch (Exception e) {
                    String username = usageRoot.right;;
                    String msg = "Error marking reachable for user: " + username + ", from usage root " + usageRoot.left;
                    System.err.println(msg);
                }
            }
        }
        System.out.println("Finished checking block DAG integrity");
    }

    private static void traverseDag(Multihash cid,
                                    BlockMetadataStore metadata,
                                    Set<Multihash> done) {
        if (cid.isIdentity())
            return;
        Optional<BlockMetadata> meta = metadata.get((Cid) cid);
        if (meta.isEmpty())
            throw new IllegalStateException("Absent block! " + cid + ", key: " + DirectS3BlockStore.hashToKey(cid));
        for (Cid link : meta.get().links) {
            done.add(link);
            traverseDag(link, metadata, done);
        }
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
                               Path reachabilityDbDir,
                               Function<Stream<Map.Entry<PublicKeyHash, byte[]>>, CompletableFuture<Boolean>> snapshotSaver,
                               BlockMetadataStore metadata,
                               BiFunction<Long, Long, CompletableFuture<Boolean>> deleteConfirm,
                               boolean listFromBlockstore) {
        System.out.println("Starting blockstore garbage collection on node " + storage.id().join() + "...");
        // TODO: do GC in O(1) RAM with a bloom filter?: mark into bloom. Then list and check bloom to delete.
        storage.clearOldTransactions(System.currentTimeMillis() - 24*3600*1000L);
        long t0 = System.nanoTime();
        Path reachabilityDbFile = reachabilityDbDir.resolve("reachability-" + LocalDate.now() + "-"
                + new Random().nextInt(100_000)+".sql");
        SqliteBlockReachability reachability = SqliteBlockReachability.createReachabilityDb(reachabilityDbFile);
        // Versions are only relevant for versioned S3 buckets, otherwise version is null
        // For S3, clients write raw blocks directly, we need to get their version directly from S3
        listBlocks(reachability, listFromBlockstore, storage, metadata);
        long t1 = System.nanoTime();
        long nBlocks = reachability.size();
        System.out.println("Listing " + nBlocks + " blocks took " + (t1-t0)/1_000_000_000 + "s");

        List<Cid> pending = storage.getOpenTransactionBlocks();
        long t2 = System.nanoTime();
        System.out.println("Listing " + pending.size() + " pending blocks took " + (t2-t1)/1_000_000_000 + "s");

        // This pointers call must happen AFTER the block and pending listing for correctness
        Map<PublicKeyHash, byte[]> allPointers = pointers.getAllEntries();
        long t3 = System.nanoTime();
        System.out.println("Listing " + allPointers.size() + " pointers took " + (t3-t2)/1_000_000_000 + "s");

        // Get the current roots from the usage store which shouldn't be GC'd until usage has been updated
        List<Pair<Multihash, String>> usageRoots = usage.getAllTargets();

        int markParallelism = 10;
        ForkJoinPool markPool = Threads.newPool(markParallelism, "GC-mark-");
        AtomicLong totalReachable = new AtomicLong(0);
        List<ForkJoinTask<Boolean>> usageMarked = usageRoots.stream()
                .map(r -> markPool.submit(() -> markReachable(storage, (Cid)r.left, r.right, reachability, metadata, totalReachable)))
                .collect(Collectors.toList());
        usageMarked.forEach(f -> f.join());
        long t4 = System.nanoTime();
        long reachableAfterUsage = totalReachable.get();
        System.out.println("Marking " + reachableAfterUsage + " reachable from " + usageRoots.size() + " usage roots took " + (t4-t3)/1_000_000_000 + "s");

        Set<Multihash> fromUsage = new HashSet<>(usageRoots.size());
        fromUsage.addAll(usageRoots.stream().map(r -> r.left).collect(Collectors.toSet()));
        List<ForkJoinTask<Boolean>> marked = allPointers.entrySet().stream()
                .map(e -> markPool.submit(() -> markReachable(e.getKey(), e.getValue(), reachability, storage, usage, fromUsage, metadata, totalReachable)))
                .collect(Collectors.toList());
        long rootsProcessed = marked.stream().filter(ForkJoinTask::join).count();

        long t5 = System.nanoTime();
        System.out.println("Marking " + (totalReachable.get() - reachableAfterUsage) + " reachable from "+rootsProcessed+" pointers took " + (t5-t4)/1_000_000_000 + "s");
        reachability.setReachable(pending, totalReachable);

        long t6 = System.nanoTime();
        System.out.println("Marking "+pending.size()+" pending blocks reachable took " + (t6-t5)/1_000_000_000 + "s");

        // Save pointers snapshot
        snapshotSaver.apply(allPointers.entrySet().stream()).join();

        AtomicLong delCount = new AtomicLong(0);
        reachability.getUnreachable(del ->  delCount.addAndGet(del.size()));
        deleteConfirm.apply(delCount.get(), nBlocks).join();

        int deleteParallelism = 4;
        ForkJoinPool pool = Threads.newPool(deleteParallelism, "GC-delete-");
        AtomicLong progressCounter = new AtomicLong(0);
        List<ForkJoinTask<Pair<Long, Long>>> futures = new ArrayList<>();
        reachability.getUnreachable(toDel -> futures.add(pool.submit(() ->
                deleteUnreachableBlocks(toDel, progressCounter, delCount.get(), storage, metadata))));
        Pair<Long, Long> deleted = futures.stream()
                .map(ForkJoinTask::join)
                .reduce((a, b) -> new Pair<>(a.left + b.left, a.right + b.right))
                .orElse(new Pair<>(0L, 0L));
        long deletedCborBlocks = deleted.left;
        long deletedRawBlocks = deleted.right;
        long t7 = System.nanoTime();
        metadata.compact();
        long t8 = System.nanoTime();
        try {
            Files.delete(reachabilityDbFile);
        } catch (IOException e) {}
        System.out.println("Deleting blocks took " + (t7-t6)/1_000_000_000 + "s");
        System.out.println("GC complete. Freed " + deletedCborBlocks + " cbor blocks and " + deletedRawBlocks +
                " raw blocks, total duration: " + (t7-t0)/1_000_000_000 + "s, metadata.compact took " + (t8-t7)/1_000_000_000 + "s");
    }

    private static boolean markReachable(PublicKeyHash writerHash,
                                         byte[] signedRawCas,
                                         SqliteBlockReachability reachability,
                                         DeletableContentAddressedStorage storage,
                                         UsageStore usage,
                                         Set<Multihash> done,
                                         BlockMetadataStore metadata,
                                         AtomicLong totalReachable) {
        try {
            PublicSigningKey writer = getWithBackoff(() -> storage.getSigningKey(null, writerHash).join().get());
            byte[] bothHashes = writer.unsignMessage(signedRawCas).join();
            PointerUpdate cas = PointerUpdate.fromCbor(CborObject.fromByteArray(bothHashes));
            MaybeMultihash updated = cas.updated;
            if (updated.isPresent() && !done.contains(updated.get())) {
                markReachable(storage, true, new ArrayList<>(1000), (Cid) updated.get(), reachability, metadata, () -> getUsername(writerHash, usage), totalReachable);
                return true;
            }
            return false;
        } catch (Exception e) {
            LOG.info("Error processing user " + getUsername(writerHash, usage));
            return false;
        }
    }

    private static String getUsername(PublicKeyHash writer, UsageStore usage) {
        try {
            return usage.getUsage(writer).owner;
        } catch (Exception e) {
            return "Orphaned writer: " + writer;
        }
    }

    private static Pair<Long, Long> deleteUnreachableBlocks(List<BlockVersion> toDelete,
                                                            AtomicLong progress,
                                                            long totalBlocksToDelete,
                                                            DeletableContentAddressedStorage storage,
                                                            BlockMetadataStore metadata) {
        if (toDelete.isEmpty())
            return new Pair<>(0L, 0L);
        long deletedCborBlocks = toDelete.stream().filter(v -> ! v.cid.isRaw()).count();
        long deletedRawBlocks = toDelete.size() - deletedCborBlocks;
        for (BlockVersion block : toDelete) {
            metadata.remove(block.cid);
        }
        getWithBackoff(() -> {storage.bulkDelete(toDelete); return true;});

        long logEvery = Math.max(1_000, totalBlocksToDelete / 10);
        long updatedProgress = progress.addAndGet(toDelete.size());
        if (updatedProgress / logEvery > (updatedProgress - toDelete.size()) / logEvery)
            System.out.println("Deleting unreachable blocks: " + updatedProgress * 100 / totalBlocksToDelete + "% done");

        return new Pair<>(deletedCborBlocks, deletedRawBlocks);
    }

    public static boolean markReachable(DeletableContentAddressedStorage storage,
                                         Cid root,
                                         String username,
                                         SqliteBlockReachability reachability,
                                         BlockMetadataStore metadata,
                                         AtomicLong totalReachable) {
        return markReachable(storage, true, new ArrayList<>(1000), root, reachability, metadata, () -> username, totalReachable);
    }

    private static boolean markReachable(DeletableContentAddressedStorage storage,
                                         boolean isRoot,
                                         List<Cid> queue,
                                         Cid block,
                                         SqliteBlockReachability reachability,
                                         BlockMetadataStore metadata,
                                         Supplier<String> username,
                                         AtomicLong totalReachable) {
        if (isRoot)
            queue.add(block);

        try {
            List<Cid> links = metadata.get(block).map(m -> m.links)
                    .orElseGet(() -> getWithBackoff(() -> storage.getLinks(block).join()));
            queue.addAll(links);
            if (queue.size() > 1000) {
                reachability.setReachable(queue, totalReachable);
                queue.clear();
            }
            for (Cid link : links) {
                markReachable(storage, false, queue, link, reachability, metadata, username, totalReachable);
            }
        } catch (Exception e) {
            LOG.info("Error processing user " + username.get());
        }
        if (isRoot)
            reachability.setReachable(queue, totalReachable);
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
