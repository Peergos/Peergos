package peergos.server.storage;

import peergos.server.AggregatedMetrics;
import peergos.server.util.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.storage.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.logging.*;

public class GarbageCollector implements ContentAddressedStorage {

    private static final long MAX_WAIT_FOR_TRANSACTION_MILLIS = 10_000;

    private final ContentAddressedStorage target;
    private final long gcPeriodMillis;
    // This lock is used to make new transactions block until a pending GC completes
    private final Object gcLock = new Object();
    private final ConcurrentHashMap<PublicKeyHash, AtomicInteger> openTransactions = new ConcurrentHashMap<>();

    public GarbageCollector(ContentAddressedStorage target, long gcPeriodMillis) {
        this.target = target;
        this.gcPeriodMillis = gcPeriodMillis;
    }

    public void start() {
        new Thread(this::run).start();
    }

    private int openTransactions() {
        int res = 0;
        for (AtomicInteger open : openTransactions.values()) {
            res += Math.max(0, open.get());
        }
        return res;
    }

    public void run() {
        while (true) {
            try {
                synchronized (gcLock) {
                    long start = System.nanoTime();
                    while (openTransactions() > 0) {
                        if ((System.nanoTime() - start) / 1_000_000 > MAX_WAIT_FOR_TRANSACTION_MILLIS) {
                            System.out.println("Aborting in flight transactions!");
                            openTransactions.clear();
                        }
                        System.out.println("GC sleeping waiting for " + openTransactions() + " open transactions..");
                        Thread.sleep(100);
                    }
                    Logging.LOG().info("Starting GC...");
                    long ready = System.nanoTime();
                    target.gc().join();
                    long done = System.nanoTime();
                    long gcWaitingToStart = (ready - start) / 1_000_000;
                    long gcDuration = (done - ready) / 1_000_0000;
                    Logging.LOG().info(String.format("GC took: %d ms waiting to start, %d ms in actual GC",
                            gcWaitingToStart, gcDuration));

                    AggregatedMetrics.IPFS_PRE_GC_DURATION.observe(gcWaitingToStart);
                    AggregatedMetrics.IPFS_GC_DURATION.observe(gcDuration);
                }
                Thread.sleep(gcPeriodMillis);
            } catch (Throwable t) {
                Logging.LOG().log(Level.WARNING, t.getMessage(), t);
            }
        }
    }

    @Override
    public CompletableFuture<TransactionId> startTransaction(PublicKeyHash owner) {
        synchronized (gcLock) {
            openTransactions.putIfAbsent(owner, new AtomicInteger(0));
            openTransactions.get(owner).incrementAndGet();
        }
        return target.startTransaction(owner);
    }

    @Override
    public CompletableFuture<Boolean> closeTransaction(PublicKeyHash owner, TransactionId tid) {
        AtomicInteger openTransactionsForUser = openTransactions.get(owner);
        if (openTransactionsForUser != null)
            openTransactionsForUser.decrementAndGet();
        return target.closeTransaction(owner, tid);
    }

    @Override
    public CompletableFuture<Multihash> id() {
        return target.id();
    }

    @Override
    public CompletableFuture<List<Multihash>> put(PublicKeyHash owner,
                                                  PublicKeyHash writer,
                                                  List<byte[]> signatures,
                                                  List<byte[]> blocks,
                                                  TransactionId tid) {
        return target.put(owner, writer, signatures, blocks, tid);
    }

    @Override
    public CompletableFuture<Optional<CborObject>> get(Multihash hash) {
        return target.get(hash);
    }

    @Override
    public CompletableFuture<List<Multihash>> putRaw(PublicKeyHash owner,
                                                     PublicKeyHash writer,
                                                     List<byte[]> signatures,
                                                     List<byte[]> blocks,
                                                     TransactionId tid,
                                                     ProgressConsumer<Long> progressConsumer) {
        return target.putRaw(owner, writer, signatures, blocks, tid, progressConsumer);
    }

    @Override
    public CompletableFuture<Optional<byte[]>> getRaw(Multihash hash) {
        return target.getRaw(hash);
    }

    @Override
    public CompletableFuture<List<Multihash>> pinUpdate(PublicKeyHash owner, Multihash existing, Multihash updated) {
        return target.pinUpdate(owner, existing, updated);
    }

    @Override
    public CompletableFuture<List<Multihash>> recursivePin(PublicKeyHash owner, Multihash hash) {
        return target.recursivePin(owner, hash);
    }

    @Override
    public CompletableFuture<List<Multihash>> recursiveUnpin(PublicKeyHash owner, Multihash hash) {
        return target.recursiveUnpin(owner, hash);
    }

    /** This method is ignored because we decide when we are calling gc
     *
     * @return
     */
    @Override
    public CompletableFuture<Boolean> gc() {
        return CompletableFuture.completedFuture(true);
    }

    @Override
    public CompletableFuture<List<Multihash>> getLinks(Multihash root) {
        return target.getLinks(root);
    }

    @Override
    public CompletableFuture<Optional<Integer>> getSize(Multihash block) {
        return target.getSize(block);
    }
}
