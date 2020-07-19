package peergos.server.storage;

import peergos.server.corenode.*;
import peergos.shared.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.mutable.*;
import peergos.shared.storage.*;

import java.util.*;
import java.util.concurrent.*;
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
        System.out.println("Listing block store took " + (t1-t0)/1_000_000_000 + "s");

        List<Multihash> pending = storage.getOpenTransactionBlocks();
        long t2 = System.nanoTime();
        System.out.println("Listing pending blocks took " + (t2-t1)/1_000_000_000 + "s");

        // This pointers call must happen AFTER the previous two for correctness
        Map<PublicKeyHash, byte[]> allPointers = pointers.getAllEntries();
        long t3 = System.nanoTime();
        System.out.println("Listing pointers took " + (t3-t2)/1_000_000_000 + "s");

        BitSet reachable = new BitSet(present.size());
        for (PublicKeyHash writerHash : allPointers.keySet()) {
            byte[] signedRawCas = allPointers.get(writerHash);
            PublicSigningKey writer = storage.getSigningKey(writerHash).join().get();
            byte[] bothHashes = writer.unsignMessage(signedRawCas);
            HashCasPair cas = HashCasPair.fromCbor(CborObject.fromByteArray(bothHashes));
            MaybeMultihash updated = cas.updated;
            if (updated.isPresent())
                markReachable(storage, updated.get(), present, reachable);
        }
        for (Multihash additional : pending) {
            int index = present.indexOf(additional);
            if (index >= 0)
                reachable.set(index);
        }
        long t4 = System.nanoTime();
        System.out.println("Marking reachable took " + (t4-t3)/1_000_000_000 + "s");

        // Save pointers snapshot
        snapshotSaver.apply(allPointers.entrySet().stream()).join();

        long deletedBlocks = 0;
        long deletedSize = 0;
        for (int i = reachable.nextClearBit(0); i >= 0 && i < present.size(); i = reachable.nextClearBit(i + 1)) {
            Multihash hash = present.get(i);
            try {
                int size = storage.getSize(hash).join().get();
                deletedBlocks++;
                deletedSize += size;
                storage.delete(hash);
            } catch (Exception e) {
                LOG.info("GC Unable to read " + hash + " during delete phase, ignoring block and continuing.");
            }
        }
        long t5 = System.nanoTime();
        System.out.println("Deleting blocks took " + (t5-t4)/1_000_000_000 + "s");
        System.out.println("GC complete. Freed " + deletedBlocks + " blocks totalling " + deletedSize + " bytes");
    }

    private static void markReachable(ContentAddressedStorage storage, Multihash root, List<Multihash> present, BitSet reachable) {
        int index = present.indexOf(root);
        if (index >= 0)
            reachable.set(index);
        List<Multihash> links = storage.getLinks(root).join();
        for (Multihash link : links) {
            markReachable(storage, link, present, reachable);
        }
    }
}
