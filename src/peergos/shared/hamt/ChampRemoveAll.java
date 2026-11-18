package peergos.shared.hamt;

import peergos.shared.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.Cid;
import peergos.shared.io.ipfs.Multihash;
import peergos.shared.storage.*;
import peergos.shared.storage.auth.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;

/**
 * Bulk-delete logic for {@link Champ}, kept in a separate file so that GWT's Eclipse JDT does
 * not pay the lambda-copy cost against the full {@code Champ.java} source.
 *
 * All lambdas in this file delegate to instance methods of the private {@link Helper} class.
 * Because {@code Helper<V>} captures the type variable once at construction time, those
 * instance methods are non-generic from JDT's perspective: JDT can resolve them without
 * opening a new inference context, which prevents the bound-set exponential growth that
 * occurs when a lambda body calls a generic static method (JDK-8153748 / JDT bug).
 */
class ChampRemoveAll {

    static <V extends Cborable> CompletableFuture<Pair<Champ<V>, Multihash>> removeAll(
            Champ<V> champ,
            PublicKeyHash owner,
            SigningPrivateKeyAndPublicHash writer,
            List<Pair<ByteArrayWrapper, byte[]>> keysAndHashes,
            Map<ByteArrayWrapper, Optional<V>> expectedValues,
            int depth,
            int bitWidth,
            int maxCollisions,
            Optional<BatId> mirrorBat,
            TransactionId tid,
            ContentAddressedStorage storage,
            Hasher writeHasher,
            Multihash ourHash) {
        return new Helper<V>(champ, owner, writer, expectedValues,
                bitWidth, maxCollisions, mirrorBat, tid, storage, writeHasher)
                .run(keysAndHashes, depth, ourHash);
    }

    /**
     * All the actual work lives here. {@code V} is fixed at construction time, so every
     * method on this class is non-generic — lambdas that call them carry no unresolved type
     * variables, which means JDT never needs to copy a bound set while inside a lambda body.
     */
    private static final class Helper<V extends Cborable> {

        private final Champ<V> champ;
        private final PublicKeyHash owner;
        private final SigningPrivateKeyAndPublicHash writer;
        private final Map<ByteArrayWrapper, Optional<V>> expectedValues;
        private final int bitWidth;
        private final int maxCollisions;
        private final Optional<BatId> mirrorBat;
        private final TransactionId tid;
        private final ContentAddressedStorage storage;
        private final Hasher writeHasher;

        Helper(Champ<V> champ,
               PublicKeyHash owner,
               SigningPrivateKeyAndPublicHash writer,
               Map<ByteArrayWrapper, Optional<V>> expectedValues,
               int bitWidth,
               int maxCollisions,
               Optional<BatId> mirrorBat,
               TransactionId tid,
               ContentAddressedStorage storage,
               Hasher writeHasher) {
            this.champ = champ;
            this.owner = owner;
            this.writer = writer;
            this.expectedValues = expectedValues;
            this.bitWidth = bitWidth;
            this.maxCollisions = maxCollisions;
            this.mirrorBat = mirrorBat;
            this.tid = tid;
            this.storage = storage;
            this.writeHasher = writeHasher;
        }

        CompletableFuture<Pair<Champ<V>, Multihash>> run(
                List<Pair<ByteArrayWrapper, byte[]>> keysAndHashes,
                int depth,
                Multihash ourHash) {

            if (keysAndHashes.isEmpty())
                return CompletableFuture.completedFuture(new Pair<Champ<V>, Multihash>(champ, ourHash));

            // Group keys by bitpos at the current depth.
            Map<Integer, List<Pair<ByteArrayWrapper, byte[]>>> byBitpos = new HashMap<Integer, List<Pair<ByteArrayWrapper, byte[]>>>();
            for (Pair<ByteArrayWrapper, byte[]> kh : keysAndHashes) {
                int bitpos = Champ.mask(kh.right, depth, bitWidth);
                List<Pair<ByteArrayWrapper, byte[]>> bucket = byBitpos.get(bitpos);
                if (bucket == null) {
                    bucket = new ArrayList<Pair<ByteArrayWrapper, byte[]>>();
                    byBitpos.put(bitpos, bucket);
                }
                bucket.add(kh);
            }

            // Phase 1: Process inline data removals; build new data section in ascending bitpos order.
            BitSet newDataMap = new BitSet();
            Map<Integer, Champ.HashPrefixPayload<V>> newDataByBitpos = new LinkedHashMap<Integer, Champ.HashPrefixPayload<V>>();
            Champ.HashPrefixPayload<V>[] contents = champ.getContents();
            int di = 0;
            for (int bp = champ.dataMap.nextSetBit(0); bp >= 0; bp = champ.dataMap.nextSetBit(bp + 1)) {
                Champ.HashPrefixPayload<V> payload = contents[di++];
                List<Pair<ByteArrayWrapper, byte[]>> toRemove = byBitpos.get(bp);
                if (toRemove == null) {
                    newDataMap.set(bp);
                    newDataByBitpos.put(bp, payload);
                } else {
                    Set<ByteArrayWrapper> removing = new HashSet<ByteArrayWrapper>();
                    for (Pair<ByteArrayWrapper, byte[]> kh : toRemove)
                        removing.add(kh.left);
                    List<Champ.KeyElement<V>> remaining = new ArrayList<Champ.KeyElement<V>>();
                    for (Champ.KeyElement<V> elem : payload.mappings)
                        if (!removing.contains(elem.key))
                            remaining.add(elem);
                    if (!remaining.isEmpty()) {
                        newDataMap.set(bp);
                        newDataByBitpos.put(bp, new Champ.HashPrefixPayload<V>(remaining.toArray(new Champ.KeyElement[0])));
                    }
                }
            }

            // Phase 2: Collect nodeMap hits.
            Map<Integer, List<Pair<ByteArrayWrapper, byte[]>>> nodeMapHits = new HashMap<Integer, List<Pair<ByteArrayWrapper, byte[]>>>();
            for (Map.Entry<Integer, List<Pair<ByteArrayWrapper, byte[]>>> e : byBitpos.entrySet())
                if (champ.nodeMap.get(e.getKey()))
                    nodeMapHits.put(e.getKey(), e.getValue());

            if (nodeMapHits.isEmpty())
                return buildAndWrite(newDataMap, newDataByBitpos,
                        BitSet.valueOf(champ.nodeMap.toByteArray()),
                        Collections.<Integer, MaybeMultihash>emptyMap(), depth);

            // Phase 3: Recurse into affected children in parallel.
            // Lambdas below call non-generic instance methods (no V to infer → no bound-set copies).
            List<CompletableFuture<Pair<Integer, Pair<Champ<V>, Multihash>>>> childFutures =
                    new ArrayList<CompletableFuture<Pair<Integer, Pair<Champ<V>, Multihash>>>>();
            for (Map.Entry<Integer, List<Pair<ByteArrayWrapper, byte[]>>> e : nodeMapHits.entrySet())
                childFutures.add(recurseChild(e.getKey(), e.getValue(), depth));

            // Capture mutable accumulators for use in the lambda.
            final BitSet fdm = newDataMap;
            final Map<Integer, Champ.HashPrefixPayload<V>> fdbp = newDataByBitpos;
            final int fd = depth;
            return Futures.combineAllInOrder(childFutures)
                    .thenCompose(results -> integrateChildResults(results, fdm, fdbp, fd));
        }

        /** Phase 3: load one child node and recurse. */
        private CompletableFuture<Pair<Integer, Pair<Champ<V>, Multihash>>> recurseChild(
                int bp,
                List<Pair<ByteArrayWrapper, byte[]>> childKeys,
                int depth) {
            Champ.HashPrefixPayload<V>[] contents = champ.getContents();
            int nodeIdx = contents.length - 1 - Champ.getIndex(champ.nodeMap, bp);
            final Multihash childHash = contents[nodeIdx].link.get();
            final int fbp = bp;
            return storage.get(owner, (Cid) childHash, Optional.<BatWithId>empty())
                    .thenCompose(rawOpt -> recurseIntoChild(rawOpt, childKeys, childHash, depth))
                    .thenApply(result -> new Pair<Integer, Pair<Champ<V>, Multihash>>(fbp, result));
        }

        /** Deserialise the child block and run removeAll on it via a fresh Helper. */
        private CompletableFuture<Pair<Champ<V>, Multihash>> recurseIntoChild(
                Optional<CborObject> rawOpt,
                List<Pair<ByteArrayWrapper, byte[]>> childKeys,
                Multihash childHash,
                int depth) {
            Champ<V> child = Champ.fromCbor(rawOpt.get(), champ.getFromCbor());
            return new Helper<V>(child, owner, writer, expectedValues,
                    bitWidth, maxCollisions, mirrorBat, tid, storage, writeHasher)
                    .run(childKeys, depth + 1, childHash);
        }

        /** Phase 4: apply child results, then build and write the updated node. */
        private CompletableFuture<Pair<Champ<V>, Multihash>> integrateChildResults(
                List<Pair<Integer, Pair<Champ<V>, Multihash>>> childResults,
                BitSet newDataMap,
                Map<Integer, Champ.HashPrefixPayload<V>> newDataByBitpos,
                int depth) {
            BitSet newNodeMap = BitSet.valueOf(champ.nodeMap.toByteArray());
            Map<Integer, MaybeMultihash> nodeUpdates = new HashMap<Integer, MaybeMultihash>();
            for (Pair<Integer, Pair<Champ<V>, Multihash>> r : childResults) {
                int bp = r.left;
                Champ<V> newChild = r.right.left;
                Multihash newChildHash = r.right.right;
                if (newChild.keyCount() == 0 && newChild.nodeCount() == 0) {
                    newNodeMap.set(bp, false);
                } else if (newChild.nodeCount() == 0 && newChild.keyCount() <= maxCollisions) {
                    newNodeMap.set(bp, false);
                    newDataMap.set(bp);
                    newDataByBitpos.put(bp, new Champ.HashPrefixPayload<V>(collectAllMappings(newChild)));
                } else {
                    nodeUpdates.put(bp, MaybeMultihash.of(newChildHash));
                }
            }
            return buildAndWrite(newDataMap, newDataByBitpos, newNodeMap, nodeUpdates, depth);
        }

        @SuppressWarnings("unchecked")
        private CompletableFuture<Pair<Champ<V>, Multihash>> buildAndWrite(
                BitSet newDataMap,
                Map<Integer, Champ.HashPrefixPayload<V>> newDataByBitpos,
                BitSet newNodeMap,
                Map<Integer, MaybeMultihash> nodeUpdates,
                int depth) {
            Champ.HashPrefixPayload<V>[] contents = champ.getContents();

            List<Champ.HashPrefixPayload<V>> dataPayloads = new ArrayList<Champ.HashPrefixPayload<V>>();
            for (int bp = newDataMap.nextSetBit(0); bp >= 0; bp = newDataMap.nextSetBit(bp + 1))
                dataPayloads.add(newDataByBitpos.get(bp));

            List<MaybeMultihash> nodeLinks = new ArrayList<MaybeMultihash>();
            for (int bp = newNodeMap.nextSetBit(0); bp >= 0; bp = newNodeMap.nextSetBit(bp + 1)) {
                if (nodeUpdates.containsKey(bp)) {
                    nodeLinks.add(nodeUpdates.get(bp));
                } else {
                    nodeLinks.add(contents[contents.length - 1 - Champ.getIndex(champ.nodeMap, bp)].link);
                }
            }

            int D = dataPayloads.size(), N = nodeLinks.size();
            Champ.HashPrefixPayload<V>[] fc = new Champ.HashPrefixPayload[D + N];
            for (int i = 0; i < D; i++) fc[i] = dataPayloads.get(i);
            for (int i = 0; i < N; i++) fc[D + N - 1 - i] = new Champ.HashPrefixPayload<V>(nodeLinks.get(i));

            final Champ<V> updated = new Champ<V>(newDataMap, newNodeMap, fc, champ.getFromCbor(), mirrorBat)
                    .withMirrorBat(mirrorBat, depth);
            return storage.put(owner, writer, updated.serialize(), writeHasher, tid)
                    .thenApply(h -> new Pair<Champ<V>, Multihash>(updated, h));
        }

        @SuppressWarnings("unchecked")
        private Champ.KeyElement<V>[] collectAllMappings(Champ<V> node) {
            List<Champ.KeyElement<V>> all = new ArrayList<Champ.KeyElement<V>>();
            for (Champ.HashPrefixPayload<V> payload : node.getContents())
                if (!payload.isShard())
                    Collections.addAll(all, payload.mappings);
            all.sort(Comparator.comparing(x -> x.key));
            return all.toArray(new Champ.KeyElement[0]);
        }
    }
}
