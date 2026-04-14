package peergos.shared.hamt;

import peergos.shared.cbor.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.Cid;
import peergos.shared.io.ipfs.Multihash;
import peergos.shared.*;
import peergos.shared.storage.*;
import peergos.shared.storage.auth.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;

/**
 * Bulk-delete logic for {@link Champ}, extracted into its own file so that GWT's Eclipse JDT
 * does not pay the lambda-copy cost against the full {@code Champ.java} source.
 *
 * Background: JDT copies the source file of a compilation unit once for every lambda expression
 * it resolves during type inference.  With many lambdas in a large file the cost is
 * O(file_size × lambda_count).  Keeping this file small bounds that cost independently of
 * the rest of {@code Champ.java}.
 */
class ChampRemoveAll {

    /**
     * Remove all specified keys from {@code champ} in a single recursive descent.
     *
     * @param keysAndHashes each pair is (key, hash-of-key)
     * @param expectedValues maps key → expected current value; reserved for future CAS use
     */
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

        if (keysAndHashes.isEmpty())
            return CompletableFuture.completedFuture(new Pair<>(champ, ourHash));

        // Group keys by bitpos at the current depth
        Map<Integer, List<Pair<ByteArrayWrapper, byte[]>>> byBitpos = new HashMap<>();
        for (Pair<ByteArrayWrapper, byte[]> kh : keysAndHashes) {
            int bitpos = Champ.mask(kh.right, depth, bitWidth);
            byBitpos.computeIfAbsent(bitpos, k -> new ArrayList<>()).add(kh);
        }

        // Phase 1: Process inline data removals; build new data section in ascending bitpos order.
        BitSet newDataMap = new BitSet();
        Map<Integer, Champ.HashPrefixPayload<V>> newDataByBitpos = new LinkedHashMap<>();
        {
            Champ.HashPrefixPayload<V>[] contents = champ.getContents();
            int di = 0;
            for (int bp = champ.dataMap.nextSetBit(0); bp >= 0; bp = champ.dataMap.nextSetBit(bp + 1)) {
                Champ.HashPrefixPayload<V> payload = contents[di++];
                List<Pair<ByteArrayWrapper, byte[]>> toRemove = byBitpos.get(bp);
                if (toRemove == null) {
                    newDataMap.set(bp);
                    newDataByBitpos.put(bp, payload);
                } else {
                    Set<ByteArrayWrapper> removing = new HashSet<>();
                    for (Pair<ByteArrayWrapper, byte[]> kh : toRemove) removing.add(kh.left);
                    List<Champ.KeyElement<V>> remaining = new ArrayList<>();
                    for (Champ.KeyElement<V> elem : payload.mappings)
                        if (!removing.contains(elem.key)) remaining.add(elem);
                    if (!remaining.isEmpty()) {
                        newDataMap.set(bp);
                        newDataByBitpos.put(bp, new Champ.HashPrefixPayload<>(remaining.toArray(new Champ.KeyElement[0])));
                    }
                }
            }
        }

        // Phase 2: Collect nodeMap hits for async processing.
        Map<Integer, List<Pair<ByteArrayWrapper, byte[]>>> nodeMapHits = new HashMap<>();
        for (Map.Entry<Integer, List<Pair<ByteArrayWrapper, byte[]>>> e : byBitpos.entrySet())
            if (champ.nodeMap.get(e.getKey()))
                nodeMapHits.put(e.getKey(), e.getValue());

        if (nodeMapHits.isEmpty()) {
            return buildAndWrite(champ, owner, writer, newDataMap, newDataByBitpos,
                    BitSet.valueOf(champ.nodeMap.toByteArray()), Collections.emptyMap(),
                    mirrorBat, depth, storage, writeHasher, tid);
        }

        // Phase 3: Recurse into affected children in parallel.
        List<CompletableFuture<Pair<Integer, Pair<Champ<V>, Multihash>>>> childFutures = new ArrayList<>();
        for (Map.Entry<Integer, List<Pair<ByteArrayWrapper, byte[]>>> e : nodeMapHits.entrySet()) {
            childFutures.add(recurseChild(champ, e.getKey(), e.getValue(), owner, writer,
                    expectedValues, depth, bitWidth, maxCollisions, mirrorBat, tid, storage, writeHasher));
        }

        // Phase 4: Integrate child results.
        return Futures.combineAllInOrder(childFutures)
                .thenCompose(results -> integrateChildResults(champ, results, newDataMap, newDataByBitpos,
                        maxCollisions, owner, writer, mirrorBat, depth, storage, writeHasher, tid));
    }

    /** Phase 3: fetch one child node and recurse removeAll into it. */
    private static <V extends Cborable> CompletableFuture<Pair<Integer, Pair<Champ<V>, Multihash>>> recurseChild(
            Champ<V> champ,
            int bp,
            List<Pair<ByteArrayWrapper, byte[]>> childKeys,
            PublicKeyHash owner,
            SigningPrivateKeyAndPublicHash writer,
            Map<ByteArrayWrapper, Optional<V>> expectedValues,
            int depth,
            int bitWidth,
            int maxCollisions,
            Optional<BatId> mirrorBat,
            TransactionId tid,
            ContentAddressedStorage storage,
            Hasher writeHasher) {
        Champ.HashPrefixPayload<V>[] contents = champ.getContents();
        int nodeIdx = contents.length - 1 - Champ.getIndex(champ.nodeMap, bp);
        Multihash childHash = contents[nodeIdx].link.get();
        return storage.get(owner, (Cid) childHash, Optional.empty())
                .thenCompose(rawOpt -> recurseIntoChild(champ, rawOpt, childKeys, childHash,
                        owner, writer, expectedValues, depth, bitWidth, maxCollisions,
                        mirrorBat, tid, storage, writeHasher))
                .thenApply(result -> new Pair<>(bp, result));
    }

    /** Deserialise the child block and invoke removeAll on it. */
    private static <V extends Cborable> CompletableFuture<Pair<Champ<V>, Multihash>> recurseIntoChild(
            Champ<V> champ,
            Optional<CborObject> rawOpt,
            List<Pair<ByteArrayWrapper, byte[]>> childKeys,
            Multihash childHash,
            PublicKeyHash owner,
            SigningPrivateKeyAndPublicHash writer,
            Map<ByteArrayWrapper, Optional<V>> expectedValues,
            int depth,
            int bitWidth,
            int maxCollisions,
            Optional<BatId> mirrorBat,
            TransactionId tid,
            ContentAddressedStorage storage,
            Hasher writeHasher) {
        Champ<V> child = Champ.fromCbor(rawOpt.get(), champ.getFromCbor());
        return child.removeAll(owner, writer, childKeys, expectedValues, depth + 1,
                bitWidth, maxCollisions, mirrorBat, tid, storage, writeHasher, childHash);
    }

    /** Phase 4: classify each child result and build the updated node. */
    private static <V extends Cborable> CompletableFuture<Pair<Champ<V>, Multihash>> integrateChildResults(
            Champ<V> champ,
            List<Pair<Integer, Pair<Champ<V>, Multihash>>> childResults,
            BitSet newDataMap,
            Map<Integer, Champ.HashPrefixPayload<V>> newDataByBitpos,
            int maxCollisions,
            PublicKeyHash owner,
            SigningPrivateKeyAndPublicHash writer,
            Optional<BatId> mirrorBat,
            int depth,
            ContentAddressedStorage storage,
            Hasher writeHasher,
            TransactionId tid) {
        BitSet newNodeMap = BitSet.valueOf(champ.nodeMap.toByteArray());
        Map<Integer, MaybeMultihash> nodeUpdates = new HashMap<>();
        for (Pair<Integer, Pair<Champ<V>, Multihash>> r : childResults) {
            int bp = r.left;
            Champ<V> newChild = r.right.left;
            Multihash newChildHash = r.right.right;
            if (newChild.keyCount() == 0 && newChild.nodeCount() == 0) {
                newNodeMap.set(bp, false);
            } else if (newChild.nodeCount() == 0 && newChild.keyCount() <= maxCollisions) {
                newNodeMap.set(bp, false);
                newDataMap.set(bp);
                newDataByBitpos.put(bp, new Champ.HashPrefixPayload<>(collectAllMappings(newChild)));
            } else {
                nodeUpdates.put(bp, MaybeMultihash.of(newChildHash));
            }
        }
        return buildAndWrite(champ, owner, writer, newDataMap, newDataByBitpos,
                newNodeMap, nodeUpdates, mirrorBat, depth, storage, writeHasher, tid);
    }

    /**
     * Assemble and write a new CHAMP node from the given data and node sections.
     * Unchanged child links are resolved from the original {@code champ} contents.
     */
    @SuppressWarnings("unchecked")
    static <V extends Cborable> CompletableFuture<Pair<Champ<V>, Multihash>> buildAndWrite(
            Champ<V> champ,
            PublicKeyHash owner,
            SigningPrivateKeyAndPublicHash writer,
            BitSet newDataMap,
            Map<Integer, Champ.HashPrefixPayload<V>> newDataByBitpos,
            BitSet newNodeMap,
            Map<Integer, MaybeMultihash> nodeUpdates,
            Optional<BatId> mirrorBat,
            int depth,
            ContentAddressedStorage storage,
            Hasher writeHasher,
            TransactionId tid) {

        Champ.HashPrefixPayload<V>[] contents = champ.getContents();

        List<Champ.HashPrefixPayload<V>> dataPayloads = new ArrayList<>();
        for (int bp = newDataMap.nextSetBit(0); bp >= 0; bp = newDataMap.nextSetBit(bp + 1))
            dataPayloads.add(newDataByBitpos.get(bp));

        List<MaybeMultihash> nodeLinks = new ArrayList<>();
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
        for (int i = 0; i < N; i++) fc[D + N - 1 - i] = new Champ.HashPrefixPayload<>(nodeLinks.get(i));

        Champ<V> updated = new Champ<>(newDataMap, newNodeMap, fc, champ.getFromCbor(), mirrorBat)
                .withMirrorBat(mirrorBat, depth);
        return storage.put(owner, writer, updated.serialize(), writeHasher, tid)
                .thenApply(h -> new Pair<>(updated, h));
    }

    @SuppressWarnings("unchecked")
    static <V extends Cborable> Champ.KeyElement<V>[] collectAllMappings(Champ<V> node) {
        List<Champ.KeyElement<V>> all = new ArrayList<>();
        for (Champ.HashPrefixPayload<V> payload : node.getContents())
            if (!payload.isShard())
                Collections.addAll(all, payload.mappings);
        all.sort(Comparator.comparing(x -> x.key));
        return all.toArray(new Champ.KeyElement[0]);
    }
}
