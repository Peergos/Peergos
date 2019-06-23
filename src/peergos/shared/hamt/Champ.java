package peergos.shared.hamt;

import peergos.shared.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.storage.*;
import peergos.shared.user.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.stream.*;

/**
 * A Compressed Hash-Array Mapped Prefix-tree (CHAMP), a refinement of a Hash Array Mapped Trie (HAMT)
 */
public class Champ implements Cborable {

    private static final int HASH_CODE_LENGTH = 32;

    private static class KeyElement {
        public final ByteArrayWrapper key;
        public final MaybeMultihash valueHash;

        public KeyElement(ByteArrayWrapper key, MaybeMultihash valueHash) {
            this.key = key;
            this.valueHash = valueHash;
        }
    }

    private static class HashPrefixPayload {
        public final KeyElement[] mappings;
        public final MaybeMultihash link;

        public HashPrefixPayload(KeyElement[] mappings, MaybeMultihash link) {
            this.mappings = mappings;
            this.link = link;
            if ((mappings == null) ^ (link != null))
                throw new IllegalStateException("Payload can either be mappings or a link, not both!");
        }

        public HashPrefixPayload(KeyElement[] mappings) {
            this(mappings, null);
        }

        public HashPrefixPayload(MaybeMultihash link) {
            this(null, link);
        }

        public boolean isShard() {
            return link != null;
        }

        public int keyCount() {
            return mappings.length;
        }
    }

    private static final Champ EMPTY = new Champ(new BitSet(), new BitSet(), new HashPrefixPayload[0]);

    public static Champ empty() {
        return EMPTY;
    }

    private final BitSet dataMap, nodeMap;
    private final HashPrefixPayload[] contents;

    public Champ(BitSet dataMap, BitSet nodeMap, HashPrefixPayload[] contents) {
        this.dataMap = dataMap;
        this.nodeMap = nodeMap;
        this.contents = contents;
        for (int i=0; i< contents.length; i++)
            if (contents[i] == null)
                throw new IllegalStateException();
    }

    private int keyCount() {
        int count = 0;
        for (HashPrefixPayload payload : contents) {
            if (! payload.isShard())
                    count += payload.keyCount();
        }
        return count;
    }

    private int nodeCount() {
        int count = 0;
        for (HashPrefixPayload payload : contents)
            if (payload.isShard())
                count++;

        return count;
    }

    private static int mask(byte[] hash, int depth, int nbits) {
        int index = (depth * nbits) / 8;
        int shift = (depth * nbits) % 8;
        int lowBits = Math.min(nbits, 8 - shift);
        int hiBits = nbits - lowBits;
        byte val1 = index < hash.length ? hash[index] : 0;
        byte val2 = index + 1 < hash.length ? hash[index + 1] : 0;
        return ((val1 >> shift) & ((1 << lowBits) - 1)) |
                ((val2 & ((1 << hiBits) - 1)) << lowBits);
    }

    private static int getIndex(BitSet bitmap, int bitpos) {
        int total = 0;
        for (int i = 0; i < bitpos;) {
            int next = bitmap.nextSetBit(i);
            if (next == -1 || next >= bitpos)
                return total;
            total++;
            i = next + 1;
        }
        return total;
    }

    CompletableFuture<Pair<Multihash, Optional<Champ>>> getChild(byte[] hash, int depth, int bitWidth, ContentAddressedStorage storage) {
        int bitpos = mask(hash, depth, bitWidth);
        int index = contents.length - 1 - getIndex(this.nodeMap, bitpos);
        Multihash childHash = contents[index].link.get();
        return storage.get(childHash)
                .thenApply(x -> new Pair<>(childHash, x.map(Champ::fromCbor)));
    }

    public CompletableFuture<Long> size(int depth, ContentAddressedStorage storage) {
        long keys = keyCount();
        if (nodeCount() == 0)
            return CompletableFuture.completedFuture(keys);

        List<CompletableFuture<Long>> childCounts = new ArrayList<>();
        for (int i = contents.length - 1; i >= 0; i--) {
            HashPrefixPayload pointer = contents[i];
            if (! pointer.isShard())
                break; // we reach the key section
            childCounts.add(storage.get(pointer.link.get())
                    .thenApply(x -> new Pair<>(pointer.link.get(), x.map(Champ::fromCbor)))
                    .thenCompose(child -> child.right.map(c -> c.size(depth + 1, storage))
                            .orElse(CompletableFuture.completedFuture(0L)))
            );
        }
        List<Integer> indices = IntStream.range(0, childCounts.size())
                .mapToObj(x -> x)
                .collect(Collectors.toList());
        return Futures.reduceAll(indices, keys, (t, index) -> childCounts.get(index).thenApply(c -> c + t), (a, b) -> a + b);
    }

    /**
     *
     * @param key The key to get the value for
     * @param hash The hash of the key
     * @param depth The current depth in the champ (top = 0)
     * @param bitWidth The champ bitwidth
     * @param storage The storage
     * @return The value, if any, that this key maps to
     */
    public CompletableFuture<MaybeMultihash> get(ByteArrayWrapper key, byte[] hash, int depth, int bitWidth, ContentAddressedStorage storage) {
        final int bitpos = mask(hash, depth, bitWidth);

        if (dataMap.get(bitpos)) { // local value
            int index = getIndex(this.dataMap, bitpos);
            HashPrefixPayload payload = contents[index];
            for (KeyElement candidate : payload.mappings) {
                if (candidate.key.equals(key)) {
                    return CompletableFuture.completedFuture(candidate.valueHash);
                }
            }

            return CompletableFuture.completedFuture(MaybeMultihash.empty());
        }

        if (nodeMap.get(bitpos)) { // child node
            return getChild(hash, depth, bitWidth, storage)
                    .thenCompose(child -> child.right.map(c -> c.get(key, hash, depth + 1, bitWidth, storage))
                            .orElse(CompletableFuture.completedFuture(MaybeMultihash.empty())));
        }

        return CompletableFuture.completedFuture(MaybeMultihash.empty());
    }

    /**
     *
     * @param writer The writer key with permission to write
     * @param key The key to set the value for
     * @param hash The hash of the key
     * @param depth The current depth in the champ (top = 0)
     * @param expected The expected value, if any, currently stored for this key
     * @param value The new value to map this key to
     * @param bitWidth The champ bitwidth
     * @param maxCollisions The maximum number of hash collision per layer in this champ
     * @param hasher The function to calculate the hash of keys
     * @param tid The transaction id for this write operation
     * @param storage The storage
     * @param ourHash The hash of the current champ node
     * @return A new champ and its hash after the put
     */
    public CompletableFuture<Pair<Champ, Multihash>> put(PublicKeyHash owner,
                                                         SigningPrivateKeyAndPublicHash writer,
                                                         ByteArrayWrapper key,
                                                         byte[] hash,
                                                         int depth,
                                                         MaybeMultihash expected,
                                                         MaybeMultihash value,
                                                         int bitWidth,
                                                         int maxCollisions,
                                                         Function<ByteArrayWrapper, byte[]> hasher,
                                                         TransactionId tid,
                                                         ContentAddressedStorage storage,
                                                         Multihash ourHash) {
        int bitpos = mask(hash, depth, bitWidth);

        if (dataMap.get(bitpos)) { // local value
            int index = getIndex(this.dataMap, bitpos);
            HashPrefixPayload payload = contents[index];
            KeyElement[] mappings = payload.mappings;
            for (int payloadIndex = 0; payloadIndex < mappings.length; payloadIndex++) {
                KeyElement mapping = mappings[payloadIndex];
                final ByteArrayWrapper currentKey = mapping.key;
                final MaybeMultihash currentVal = mapping.valueHash;
                if (currentKey.equals(key)) {
                    if (! currentVal.equals(expected)) {
                        CompletableFuture<Pair<Champ, Multihash>> err = new CompletableFuture<>();
                        err.completeExceptionally(new MutableTree.CasException(currentVal, expected));
                        return err;
                    }

                    // update mapping
                    Champ champ = copyAndSetValue(index, payloadIndex, value);
                    return storage.put(owner, writer, champ.serialize(), tid).thenApply(h -> new Pair<>(champ, h));
                }
            }
            if (mappings.length < maxCollisions) {
                Champ champ = insertIntoPrefix(index, key, value);
                return storage.put(owner, writer, champ.serialize(), tid).thenApply(h -> new Pair<>(champ, h));
            }

            return pushMappingsDownALevel(owner, writer, mappings,
                    key, hash, value, depth + 1, bitWidth, maxCollisions, hasher, tid, storage)
                    .thenCompose(p -> {
                        Champ champ = copyAndMigrateFromInlineToNode(bitpos, p);
                        return storage.put(owner, writer, champ.serialize(), tid).thenApply(h -> new Pair<>(champ, h));
                    });
        } else if (nodeMap.get(bitpos)) { // child node
            return getChild(hash, depth, bitWidth, storage)
                    .thenCompose(child -> child.right.get().put(owner, writer, key, hash, depth + 1, expected, value,
                            bitWidth, maxCollisions, hasher, tid, storage, child.left)
                            .thenCompose(newChild -> {
                                if (newChild.right.equals(child.left))
                                    return CompletableFuture.completedFuture(new Pair<>(this, ourHash));
                                Champ champ = overwriteChildLink(bitpos, newChild);
                                return storage.put(owner, writer, champ.serialize(), tid).thenApply(h -> new Pair<>(champ, h));
                            }));
        } else {
            // no value
            Champ champ = addNewPrefix(bitpos, key, value);
            return storage.put(owner, writer, champ.serialize(), tid).thenApply(h -> new Pair<>(champ, h));
        }
    }

    private CompletableFuture<Pair<Champ, Multihash>> pushMappingsDownALevel(PublicKeyHash owner,
                                                                             SigningPrivateKeyAndPublicHash writer,
                                                                             KeyElement[] mappings,
                                                                             ByteArrayWrapper key1,
                                                                             byte[] hash1,
                                                                             MaybeMultihash val1,
                                                                             final int depth,
                                                                             int bitWidth,
                                                                             int maxCollisions,
                                                                             Function<ByteArrayWrapper, byte[]> hasher,
                                                                             TransactionId tid,
                                                                             ContentAddressedStorage storage) {
        if (depth >= HASH_CODE_LENGTH) {
             throw new IllegalStateException("Hash collision!");
        }

        Champ empty = empty();
        return storage.put(owner, writer, empty.serialize(), tid)
                .thenApply(h -> new Pair<>(empty, h))
                .thenCompose(p -> p.left.put(owner, writer, key1, hash1, depth, MaybeMultihash.empty(), val1,
                        bitWidth, maxCollisions, hasher, tid, storage, p.right))
                .thenCompose(one -> Futures.reduceAll(
                        Arrays.stream(mappings).collect(Collectors.toList()),
                        one,
                        (p, e) -> p.left.put(owner, writer, e.key, hasher.apply(e.key), depth, MaybeMultihash.empty(), e.valueHash,
                                bitWidth, maxCollisions, hasher, tid, storage, p.right),
                        (a, b) -> a)
                );
    }

    private Champ copyAndSetValue(final int setIndex, final int payloadIndex, final MaybeMultihash val) {
        final HashPrefixPayload[] src = this.contents;
        final HashPrefixPayload[] dst = Arrays.copyOf(src, src.length);

        HashPrefixPayload existing = dst[setIndex];
        KeyElement[] updated = new KeyElement[existing.mappings.length];
        System.arraycopy(existing.mappings, 0, updated, 0, existing.mappings.length);
        updated[payloadIndex] = new KeyElement(existing.mappings[payloadIndex].key, val);
        dst[setIndex] = new HashPrefixPayload(updated);

        return new Champ(dataMap, nodeMap, dst);
    }

    private Champ insertIntoPrefix(final int index, final ByteArrayWrapper key, final MaybeMultihash val) {
        final HashPrefixPayload[] src = this.contents;
        final HashPrefixPayload[] result = Arrays.copyOf(src, src.length);

        KeyElement[] prefix = new KeyElement[src[index].mappings.length + 1];
        System.arraycopy(src[index].mappings, 0, prefix, 0, src[index].mappings.length);
        prefix[prefix.length - 1] = new KeyElement(key, val);
        // ensure canonical structure
        Arrays.sort(prefix, Comparator.comparing(m -> m.key));
        result[index] = new HashPrefixPayload(prefix);

        return new Champ(dataMap, nodeMap, result);
    }

    private Champ addNewPrefix(final int bitpos, final ByteArrayWrapper key, final MaybeMultihash val) {
        final int insertIndex = getIndex(dataMap, bitpos);

        final HashPrefixPayload[] src = this.contents;
        final HashPrefixPayload[] result = new HashPrefixPayload[src.length + 1];

        System.arraycopy(src, 0, result, 0, insertIndex);
        System.arraycopy(src, insertIndex, result, insertIndex + 1, src.length - insertIndex);
        result[insertIndex] = new HashPrefixPayload(new KeyElement[]{new KeyElement(key, val)});

        BitSet newDataMap = BitSet.valueOf(dataMap.toByteArray());
        newDataMap.set(bitpos);
        return new Champ(newDataMap, nodeMap, result);
    }

    private Champ copyAndMigrateFromInlineToNode(final int bitpos, final Pair<Champ, Multihash> node) {

        final int oldIndex = getIndex(dataMap, bitpos);
        final int newIndex = this.contents.length - 1 - getIndex(nodeMap, bitpos);

        final HashPrefixPayload[] src = this.contents;
        final HashPrefixPayload[] dst = new HashPrefixPayload[src.length];

        // copy 'src' and remove 1 element at position oldIndex and insert 1 element at position newIndex
        if (oldIndex > newIndex)
            throw new IllegalStateException("Invalid champ!");
        System.arraycopy(src, 0, dst, 0, oldIndex);
        System.arraycopy(src, oldIndex + 1, dst, oldIndex, newIndex - oldIndex);
        dst[newIndex] = new HashPrefixPayload(MaybeMultihash.of(node.right));
        System.arraycopy(src, newIndex + 1, dst, newIndex + 1, src.length - newIndex - 1);

        BitSet newNodeMap = BitSet.valueOf(nodeMap.toByteArray());
        newNodeMap.set(bitpos);
        BitSet newDataMap = BitSet.valueOf(dataMap.toByteArray());
        newDataMap.set(bitpos, false);
        return new Champ(newDataMap, newNodeMap, dst);
    }

    private Champ overwriteChildLink(final int bitpos, final Pair<Champ, Multihash> node) {

        final int setIndex = this.contents.length - 1 - getIndex(nodeMap, bitpos);

        final HashPrefixPayload[] src = this.contents;
        final HashPrefixPayload[] dst = Arrays.copyOf(src, src.length);

        dst[setIndex] = new HashPrefixPayload(MaybeMultihash.of(node.right));

        return new Champ(dataMap, nodeMap, dst);
    }

    /**
     *
     * @param writer The writer key with permission to write
     * @param key The key to remove the value for
     * @param hash The hash of the key
     * @param depth The current depth in the champ (top = 0)
     * @param expected The expected value, if any, currently stored for this key
     * @param bitWidth The champ bitwidth
     * @param maxCollisions The maximum number of hash collision per layer in this champ
     * @param storage The storage
     * @param ourHash The hash of the current champ node
     * @return A new champ and its hash after the remove
     */
    public CompletableFuture<Pair<Champ, Multihash>> remove(PublicKeyHash owner,
                                                            SigningPrivateKeyAndPublicHash writer,
                                                            ByteArrayWrapper key,
                                                            byte[] hash,
                                                            int depth,
                                                            MaybeMultihash expected,
                                                            int bitWidth,
                                                            int maxCollisions,
                                                            TransactionId tid,
                                                            ContentAddressedStorage storage,
                                                            Multihash ourHash) {
        int bitpos = mask(hash, depth, bitWidth);

        if (dataMap.get(bitpos)) { // in place value
            final int dataIndex = getIndex(dataMap, bitpos);

            HashPrefixPayload payload = contents[dataIndex];
            KeyElement[] mappings = payload.mappings;
            for (int payloadIndex = 0; payloadIndex < mappings.length; payloadIndex++) {
                KeyElement mapping = mappings[payloadIndex];
                final ByteArrayWrapper currentKey = mapping.key;
                final MaybeMultihash currentVal = mapping.valueHash;
                if (Objects.equals(currentKey, key)) {
                    if (!currentVal.equals(expected)) {
                        CompletableFuture<Pair<Champ, Multihash>> err = new CompletableFuture<>();
                        err.completeExceptionally(new MutableTree.CasException(currentVal, expected));
                        return err;
                    }

                    if (this.keyCount() == maxCollisions + 1 && this.nodeCount() == 0) {
                        /*
				         * Create new node with remaining pairs. The new node
					     * will either
					     * a) become the new root returned, or
					     * b) be unwrapped and inlined during returning.
					     */
                        Champ champ;
                        if (depth > 0) {
                            // inline all mappings into a single node because at a higher level, all mappings have the
                            // same hash prefix
                            final BitSet newDataMap = new BitSet();
                            newDataMap.set(mask(hash, 0, bitWidth));

                            KeyElement[] remainingMappings = new KeyElement[maxCollisions];
                            int nextIndex = 0;
                            for (HashPrefixPayload grouped : contents) {
                                for (KeyElement pair : grouped.mappings) {
                                    if (!pair.key.equals(key))
                                        remainingMappings[nextIndex++] = pair;
                                }
                            }
                            Arrays.sort(remainingMappings, Comparator.comparing(x -> x.key));
                            HashPrefixPayload[] oneBucket = new HashPrefixPayload[]{new HashPrefixPayload(remainingMappings)};

                            champ = new Champ(newDataMap, new BitSet(), oneBucket);
                        } else {
                            final BitSet newDataMap = BitSet.valueOf(dataMap.toByteArray());
                            boolean lastInPrefix = mappings.length == 1;
                            if (lastInPrefix)
                                newDataMap.clear(bitpos);
                            else
                                newDataMap.set(mask(hash, 0, bitWidth));

                            HashPrefixPayload[] src = this.contents;
                            HashPrefixPayload[] dst = new HashPrefixPayload[src.length - (lastInPrefix ? 1 : 0)];
                            System.arraycopy(src, 0, dst, 0, dataIndex);
                            System.arraycopy(src, dataIndex + 1, dst, dataIndex + (lastInPrefix ? 0 : 1), src.length - dataIndex - 1);
                            if (! lastInPrefix) {
                                KeyElement[] remaining = new KeyElement[mappings.length - 1];
                                System.arraycopy(mappings, 0, remaining, 0, payloadIndex);
                                System.arraycopy(mappings, payloadIndex + 1, remaining, payloadIndex, mappings.length - payloadIndex - 1);
                                dst[dataIndex] = new HashPrefixPayload(remaining);
                            }

                            champ = new Champ(newDataMap, new BitSet(), dst);
                        }
                        return storage.put(owner, writer, champ.serialize(), tid).thenApply(h -> new Pair<>(champ, h));
                    } else {
                        Champ champ = removeMapping(bitpos, payloadIndex);
                        return storage.put(owner, writer, champ.serialize(), tid).thenApply(h -> new Pair<>(champ, h));
                    }
                }
            }
            return CompletableFuture.completedFuture(new Pair<>(this, ourHash));
        } else if (nodeMap.get(bitpos)) { // node (not value)
            return getChild(hash, depth, bitWidth, storage)
                    .thenCompose(child -> child.right.get().remove(owner, writer, key, hash, depth + 1, expected, bitWidth, maxCollisions, tid, storage, child.left)
                            .thenCompose(newChild -> {
                                if (child.left.equals(newChild.right))
                                    return CompletableFuture.completedFuture(new Pair<>(this, ourHash));

                                if (newChild.left.contents.length == 0) {
                                    throw new IllegalStateException("Sub-node must have at least one element.");
                                } else if (newChild.left.nodeCount() == 0 && newChild.left.keyCount() == maxCollisions) {
                                    if (this.keyCount() == 0 && this.nodeCount() == 1) {
                                        // escalate singleton result (the child already has the depth corrected index)
                                        return CompletableFuture.completedFuture(newChild);
                                    } else {
                                        // inline value (move to front)
                                        Champ champ = copyAndMigrateFromNodeToInline(bitpos, newChild.left);
                                        return storage.put(owner, writer, champ.serialize(), tid).thenApply(h -> new Pair<>(champ, h));
                                    }
                                } else {
                                    // modify current node (set replacement node)
                                    Champ champ = overwriteChildLink(bitpos, newChild);
                                    return storage.put(owner, writer, champ.serialize(), tid).thenApply(h -> new Pair<>(champ, h));
                                }
                            }));
        }

        return CompletableFuture.completedFuture(new Pair<>(this, ourHash));
    }

    private Champ copyAndMigrateFromNodeToInline(final int bitpos, final Champ node) {

        final int oldIndex = this.contents.length - 1 - getIndex(nodeMap, bitpos);
        final int newIndex = getIndex(dataMap, bitpos);

        final HashPrefixPayload[] src = this.contents;
        final HashPrefixPayload[] dst = new HashPrefixPayload[src.length];

        // copy src and remove element at position oldIndex and insert element at position newIndex
        if (oldIndex < newIndex)
            throw new IllegalStateException("Invalid champ!");
        System.arraycopy(src, 0, dst, 0, newIndex);
        KeyElement[] merged = new KeyElement[node.keyCount()];
        int count = 0;
        for (int i=0; i < node.contents.length; i++) {
            KeyElement[] toAdd = node.contents[i].mappings;
            System.arraycopy(toAdd, 0, merged, count, toAdd.length);
            count += toAdd.length;
        }
        Arrays.sort(merged, Comparator.comparing(x -> x.key));
        dst[newIndex] = new HashPrefixPayload(merged);
        System.arraycopy(src, newIndex, dst, newIndex + 1, oldIndex - newIndex);
        System.arraycopy(src, oldIndex + 1, dst, oldIndex + 1, src.length - oldIndex - 1);

        BitSet newNodeMap = BitSet.valueOf(nodeMap.toByteArray());
        newNodeMap.set(bitpos, false);
        BitSet newDataMap = BitSet.valueOf(dataMap.toByteArray());
        newDataMap.set(bitpos, true);
        return new Champ(newDataMap, newNodeMap, dst);
    }

    private Champ removeMapping(final int bitpos, final int payloadIndex) {
        final int index = getIndex(dataMap, bitpos);
        final HashPrefixPayload[] src = this.contents;
        KeyElement[] existing = src[index].mappings;
        boolean lastInPrefix = existing.length == 1;
        final HashPrefixPayload[] dst = new HashPrefixPayload[src.length - (lastInPrefix ? 1 : 0)];

        // copy src and remove element at position index
        System.arraycopy(src, 0, dst, 0, index);
        System.arraycopy(src, index + 1, dst, lastInPrefix ? index : index + 1, src.length - index - 1);
        if (! lastInPrefix) {
            KeyElement[] remaining = new KeyElement[existing.length - 1];
            System.arraycopy(existing, 0, remaining, 0, payloadIndex);
            System.arraycopy(existing, payloadIndex + 1, remaining, payloadIndex, existing.length - payloadIndex - 1);
            dst[index] = new HashPrefixPayload(remaining);
        }

        BitSet newDataMap = BitSet.valueOf(dataMap.toByteArray());
        if (lastInPrefix)
            newDataMap.clear(bitpos);
        return new Champ(newDataMap, nodeMap, dst);
    }

    public <T> CompletableFuture<T> applyToAllMappings(T identity,
                                                       BiFunction<T, Pair<ByteArrayWrapper, MaybeMultihash>, CompletableFuture<T>> consumer,
                                                       ContentAddressedStorage storage) {
        return Futures.reduceAll(Arrays.stream(contents).collect(Collectors.toList()), identity, (res, payload) ->
                (! payload.isShard() ?
                        Futures.reduceAll(
                                Arrays.stream(payload.mappings).collect(Collectors.toList()),
                                res,
                                (x, mapping) -> consumer.apply(x, new Pair<>(mapping.key, mapping.valueHash)),
                                (a, b) ->  a) :
                        CompletableFuture.completedFuture(res)
                ).thenCompose(newRes ->
                        payload.isShard() && payload.link.isPresent() ?
                                storage.get(payload.link.get())
                                        .thenApply(rawOpt -> Champ.fromCbor(rawOpt.orElseThrow(() -> new IllegalStateException("Hash not present! " + payload.link))))
                                        .thenCompose(child -> child.applyToAllMappings(newRes, consumer, storage)) :
                                CompletableFuture.completedFuture(newRes)
                ), (a, b) -> a);
    }

    private List<KeyElement> getMappings() {
        return Arrays.stream(contents)
                .filter(p -> !p.isShard())
                .flatMap(p -> Arrays.stream(p.mappings))
                .collect(Collectors.toList());
    }

    private List<HashPrefixPayload> getLinks() {
        return Arrays.stream(contents)
                .filter(p -> p.isShard())
                .collect(Collectors.toList());
    }

    private static Optional<HashPrefixPayload> getElement(int bitIndex, int dataIndex, int nodeIndex, Optional<Champ> c) {
        if (! c.isPresent())
            return Optional.empty();
        Champ champ = c.get();
        if (champ.dataMap.get(bitIndex))
            return Optional.of(champ.contents[dataIndex]);
        if (champ.nodeMap.get(bitIndex))
            return Optional.of(champ.contents[champ.contents.length - 1 - nodeIndex]);
        return Optional.empty();
    }

    public static CompletableFuture<Boolean> applyToDiff(
            MaybeMultihash original,
            MaybeMultihash updated,
            int depth,
            Function<ByteArrayWrapper, byte[]> hasher,
            List<KeyElement> higherLeftMappings,
            List<KeyElement> higherRightMappings,
            Consumer<Triple<ByteArrayWrapper, MaybeMultihash, MaybeMultihash>> consumer,
            int bitWidth,
            ContentAddressedStorage storage) {

        if (updated.equals(original))
            return CompletableFuture.completedFuture(true);
        return original.map(storage::get).orElseGet(() -> CompletableFuture.completedFuture(Optional.empty()))
                .thenApply(rawOpt -> rawOpt.map(Champ::fromCbor))
                .thenCompose(left -> updated.map(storage::get).orElseGet(() -> CompletableFuture.completedFuture(Optional.empty()))
                        .thenApply(rawOpt -> rawOpt.map(Champ::fromCbor))
                        .thenCompose(right -> {
                            int leftMax = left.map(c -> Math.max(c.dataMap.length(), c.nodeMap.length())).orElse(0);
                            int rightMax = right.map(c -> Math.max(c.dataMap.length(), c.nodeMap.length())).orElse(0);
                            int maxBit = Math.max(leftMax, rightMax);
                            int leftDataIndex = 0, rightDataIndex = 0, leftNodeCount = 0, rightNodeCount = 0;
                            Map<Integer, List<KeyElement>> leftHigherMappingsByBit = higherLeftMappings.stream()
                                    .collect(Collectors.groupingBy(m -> mask(hasher.apply(m.key), depth, bitWidth)));
                            Map<Integer, List<KeyElement>> rightHigherMappingsByBit = higherRightMappings.stream()
                                    .collect(Collectors.groupingBy(m -> mask(hasher.apply(m.key), depth, bitWidth)));

                            List<CompletableFuture<Boolean>> deeperLayers = new ArrayList<>();

                            for (int i = 0; i < maxBit; i++) {
                                // either the payload is present OR higher mappings are non empty OR the champ is absent
                                Optional<HashPrefixPayload> leftPayload = getElement(i, leftDataIndex, leftNodeCount, left);
                                Optional<HashPrefixPayload> rightPayload = getElement(i, rightDataIndex, rightNodeCount, right);

                                List<KeyElement> leftHigherMappings = leftHigherMappingsByBit.getOrDefault(i, Collections.emptyList());
                                List<KeyElement> leftMappings = leftPayload
                                        .filter(p -> !p.isShard())
                                        .map(p -> Arrays.asList(p.mappings))
                                        .orElse(leftHigherMappings);
                                List<KeyElement> rightHigherMappings = rightHigherMappingsByBit.getOrDefault(i, Collections.emptyList());
                                List<KeyElement> rightMappings = rightPayload
                                        .filter(p -> !p.isShard())
                                        .map(p -> Arrays.asList(p.mappings))
                                        .orElse(rightHigherMappings);

                                Optional<MaybeMultihash> leftShard = leftPayload
                                        .filter(p -> p.isShard())
                                        .map(p -> p.link);

                                Optional<MaybeMultihash> rightShard = rightPayload
                                        .filter(p -> p.isShard())
                                        .map(p -> p.link);

                                if (leftShard.isPresent() || rightShard.isPresent()) {
                                    deeperLayers.add(applyToDiff(
                                            leftShard.orElse(MaybeMultihash.empty()),
                                            rightShard.orElse(MaybeMultihash.empty()), depth + 1, hasher,
                                            leftMappings, rightMappings, consumer, bitWidth, storage));
                                } else {
                                    Map<ByteArrayWrapper, MaybeMultihash> leftMap = leftMappings.stream()
                                            .collect(Collectors.toMap(e -> e.key, e -> e.valueHash));
                                    Map<ByteArrayWrapper, MaybeMultihash> rightMap = rightMappings.stream()
                                            .collect(Collectors.toMap(e -> e.key, e -> e.valueHash));

                                    HashSet<ByteArrayWrapper> both = new HashSet<>(leftMap.keySet());
                                    both.retainAll(rightMap.keySet());

                                    for (Map.Entry<ByteArrayWrapper, MaybeMultihash> entry : leftMap.entrySet()) {
                                        if (! both.contains(entry.getKey()))
                                            consumer.accept(new Triple<>(entry.getKey(), entry.getValue(), MaybeMultihash.empty()));
                                        else if (! entry.getValue().equals(rightMap.get(entry.getKey())))
                                            consumer.accept(new Triple<>(entry.getKey(), entry.getValue(), rightMap.get(entry.getKey())));
                                    }
                                    for (Map.Entry<ByteArrayWrapper, MaybeMultihash> entry : rightMap.entrySet()) {
                                        if (! both.contains(entry.getKey()))
                                            consumer.accept(new Triple<>(entry.getKey(), MaybeMultihash.empty(), entry.getValue()));
                                    }
                                }

                                if (leftPayload.isPresent()) {
                                    if (leftPayload.get().isShard())
                                        leftNodeCount++;
                                    else
                                        leftDataIndex++;
                                }
                                if (rightPayload.isPresent()) {
                                    if (rightPayload.get().isShard())
                                        rightNodeCount++;
                                    else
                                        rightDataIndex++;
                                }
                            }

                            return Futures.combineAll(deeperLayers).thenApply(x -> true);
                        })
        );
    }

    @Override
    public CborObject toCbor() {
        return new CborObject.CborList(Arrays.asList(
                new CborObject.CborByteArray(dataMap.toByteArray()),
                new CborObject.CborByteArray(nodeMap.toByteArray()),
                new CborObject.CborList(Arrays.stream(contents)
                        .flatMap(e -> e.link != null ?
                                Stream.of(new CborObject.CborMerkleLink(e.link.get())) :
                                Stream.of(new CborObject.CborList(Arrays.stream(e.mappings)
                                        .flatMap(m -> Stream.of(
                                                new CborObject.CborByteArray(m.key.data),
                                                m.valueHash.isPresent() ?
                                                        new CborObject.CborMerkleLink(m.valueHash.get()) :
                                                        new CborObject.CborNull()
                                        ))
                                        .collect(Collectors.toList()))))
                        .collect(Collectors.toList()))
        ));
    }

    public static Champ fromCbor(Cborable cbor) {
        if (! (cbor instanceof CborObject.CborList))
            throw new IllegalStateException("Invalid cbor for CHAMP! " + cbor);
        List<? extends Cborable> list = ((CborObject.CborList) cbor).value;

        if (! (list.get(0) instanceof CborObject.CborByteArray))
            throw new IllegalStateException("Invalid cbor for a champ, is this a btree?");
        BitSet dataMap = BitSet.valueOf(((CborObject.CborByteArray)list.get(0)).value);
        BitSet nodeMap = BitSet.valueOf(((CborObject.CborByteArray)list.get(1)).value);
        List<? extends Cborable> contentsCbor = ((CborObject.CborList) list.get(2)).value;

        List<HashPrefixPayload> contents = new ArrayList<>();
        for (int i=0; i < contentsCbor.size(); i++) {
            Cborable keyOrHash = contentsCbor.get(i);
            if (keyOrHash instanceof CborObject.CborList) {
                List<KeyElement> mappings = new ArrayList<>();
                List<? extends Cborable> mappingsCbor = ((CborObject.CborList) keyOrHash).value;
                for (int j=0; j < mappingsCbor.size(); j += 2) {
                    byte[] key = ((CborObject.CborByteArray) mappingsCbor.get(j)).value;
                    Cborable value = mappingsCbor.get(j + 1);
                    mappings.add(new KeyElement(new ByteArrayWrapper(key),
                        value instanceof CborObject.CborNull ?
                                MaybeMultihash.empty() :
                                MaybeMultihash.of(((CborObject.CborMerkleLink) value).target)));
                }
                contents.add(new HashPrefixPayload(mappings.toArray(new KeyElement[0])));
            } else {
                contents.add(new HashPrefixPayload(MaybeMultihash.of(((CborObject.CborMerkleLink)keyOrHash).target)));
            }
        }
        return new Champ(dataMap, nodeMap, contents.toArray(new HashPrefixPayload[contents.size()]));
    }
}
