package peergos.shared.hamt;

import peergos.server.storage.*;
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
import java.util.function.*;
import java.util.stream.*;

/**
 * A Compressed Hash-Array Mapped Prefix-tree (CHAMP), a refinement of a Hash Array Mapped Trie (HAMT)
 */
public class Champ<V extends Cborable> implements Cborable {

    private static final int HASH_CODE_LENGTH = 32;

    public static class KeyElement<V extends Cborable> {
        public final ByteArrayWrapper key;
        public final Optional<V> valueHash;

        public KeyElement(ByteArrayWrapper key, Optional<V> valueHash) {
            this.key = key;
            this.valueHash = valueHash;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            KeyElement<?> that = (KeyElement<?>) o;
            return key.equals(that.key) &&
                    valueHash.equals(that.valueHash);
        }

        @Override
        public int hashCode() {
            return Objects.hash(key, valueHash);
        }
    }

    public static class HashPrefixPayload<V extends Cborable> {
        public final KeyElement<V>[] mappings;
        public final MaybeMultihash link;

        public HashPrefixPayload(KeyElement<V>[] mappings, MaybeMultihash link) {
            this.mappings = mappings;
            this.link = link;
            if ((mappings == null) ^ (link != null))
                throw new IllegalStateException("Payload can either be mappings or a link, not both!");
        }

        public HashPrefixPayload(KeyElement<V>[] mappings) {
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

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            HashPrefixPayload<?> that = (HashPrefixPayload<?>) o;
            return Arrays.equals(mappings, that.mappings) &&
                    Objects.equals(link, that.link);
        }

        @Override
        public int hashCode() {
            int result = Objects.hash(link);
            result = 31 * result + Arrays.hashCode(mappings);
            return result;
        }
    }

    public static <V extends Cborable> Champ<V> empty(Function<Cborable, V> fromCbor) {
        return new Champ<>(new BitSet(), new BitSet(), new HashPrefixPayload[0], fromCbor, Optional.empty());
    }

    public final BitSet dataMap, nodeMap;
    private final HashPrefixPayload<V>[] contents;
    private final Function<Cborable, V> fromCbor;
    public final Optional<BatId> mirrorBat;

    public Champ(BitSet dataMap, BitSet nodeMap, HashPrefixPayload<V>[] contents, Function<Cborable, V> fromCbor, Optional<BatId> mirrorBat) {
        this.dataMap = dataMap;
        this.nodeMap = nodeMap;
        this.contents = contents;
        this.fromCbor = fromCbor;
        this.mirrorBat = mirrorBat;
        for (int i=0; i< contents.length; i++)
            if (contents[i] == null)
                throw new IllegalStateException();
    }

    public Champ<V> withBat(Optional<BatId> newMirrorBat) {
        return new Champ<V>(dataMap, nodeMap, contents, fromCbor, newMirrorBat);
    }

    private int keyCount() {
        int count = 0;
        for (HashPrefixPayload<V> payload : contents) {
            if (! payload.isShard())
                    count += payload.keyCount();
        }
        return count;
    }

    private int nodeCount() {
        int count = 0;
        for (HashPrefixPayload<V> payload : contents)
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

    CompletableFuture<Pair<Multihash, Optional<Champ<V>>>> getChild(PublicKeyHash owner, byte[] hash, int depth, int bitWidth, ContentAddressedStorage storage) {
        int bitpos = mask(hash, depth, bitWidth);
        int index = contents.length - 1 - getIndex(this.nodeMap, bitpos);
        Multihash childHash = contents[index].link.get();
        return storage.get(owner, (Cid) childHash, Optional.empty())
                .thenApply(x -> new Pair<>(childHash, x.map(y -> Champ.fromCbor(y, fromCbor))));
    }

    public CompletableFuture<Long> size(PublicKeyHash owner, int depth, ContentAddressedStorage storage) {
        long keys = keyCount();
        if (nodeCount() == 0)
            return CompletableFuture.completedFuture(keys);

        List<CompletableFuture<Long>> childCounts = new ArrayList<>();
        for (int i = contents.length - 1; i >= 0; i--) {
            HashPrefixPayload<V> pointer = contents[i];
            if (! pointer.isShard())
                break; // we reach the key section
            childCounts.add(storage.get(owner, (Cid) pointer.link.get(), Optional.empty())
                    .thenApply(x -> new Pair<>(pointer.link.get(), x.map(y -> Champ.fromCbor(y, fromCbor))))
                    .thenCompose(child -> child.right.map(c -> c.size(owner, depth + 1, storage))
                            .orElse(CompletableFuture.completedFuture(0L)))
            );
        }
        List<Integer> indices = IntStream.range(0, childCounts.size())
                .mapToObj(x -> x)
                .collect(Collectors.toList());
        return Futures.reduceAll(indices, keys, (t, index) -> childCounts.get(index).thenApply(c -> c + t), (a, b) -> a + b);
    }

    public boolean hasMultipleMappings() {
        return keyCount() > 1 || nodeCount() > 0;
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
    public CompletableFuture<Optional<V>> get(PublicKeyHash owner, ByteArrayWrapper key, byte[] hash, int depth, int bitWidth, ContentAddressedStorage storage) {
        final int bitpos = mask(hash, depth, bitWidth);

        if (dataMap.get(bitpos)) { // local value
            int index = getIndex(this.dataMap, bitpos);
            HashPrefixPayload<V> payload = contents[index];
            for (KeyElement<V> candidate : payload.mappings) {
                if (candidate.key.equals(key)) {
                    return CompletableFuture.completedFuture(candidate.valueHash);
                }
            }

            return CompletableFuture.completedFuture(Optional.empty());
        }

        if (nodeMap.get(bitpos)) { // child node
            return getChild(owner, hash, depth, bitWidth, storage)
                    .thenCompose(child -> child.right.map(c -> c.get(owner, key, hash, depth + 1, bitWidth, storage))
                            .orElse(CompletableFuture.completedFuture(Optional.empty())));
        }

        return CompletableFuture.completedFuture(Optional.empty());
    }

    private Champ<V> withMirrorBat(Optional<BatId> mirrorBat, int depth) {
        if (depth > 0 || mirrorBat.isEmpty())
            return this;
        return new Champ<>(dataMap, nodeMap, contents, fromCbor, mirrorBat);
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
    public CompletableFuture<Pair<Champ<V>, Multihash>> put(PublicKeyHash owner,
                                                            SigningPrivateKeyAndPublicHash writer,
                                                            ByteArrayWrapper key,
                                                            byte[] hash,
                                                            int depth,
                                                            Optional<V> expected,
                                                            Optional<V> value,
                                                            int bitWidth,
                                                            int maxCollisions,
                                                            Optional<BatId> mirrorBat,
                                                            Function<ByteArrayWrapper, CompletableFuture<byte[]>> hasher,
                                                            TransactionId tid,
                                                            ContentAddressedStorage storage,
                                                            Hasher writeHasher,
                                                            Multihash ourHash) {
        int bitpos = mask(hash, depth, bitWidth);

        if (dataMap.get(bitpos)) { // local value
            int index = getIndex(this.dataMap, bitpos);
            HashPrefixPayload<V> payload = contents[index];
            KeyElement<V>[] mappings = payload.mappings;
            for (int payloadIndex = 0; payloadIndex < mappings.length; payloadIndex++) {
                KeyElement<V> mapping = mappings[payloadIndex];
                final ByteArrayWrapper currentKey = mapping.key;
                final Optional<V> currentVal = mapping.valueHash;
                if (currentKey.equals(key)) {
                    if (! currentVal.equals(expected)) {
                        CompletableFuture<Pair<Champ<V>, Multihash>> err = new CompletableFuture<>();
                        err.completeExceptionally(new CasException(currentVal, expected));
                        return err;
                    }

                    // update mapping
                    Champ<V> champ = copyAndSetValue(index, payloadIndex, value).withMirrorBat(mirrorBat, depth);
                    return storage.put(owner, writer, champ.serialize(), writeHasher, tid).thenApply(h -> new Pair<>(champ, h));
                }
            }
            if (mappings.length < maxCollisions) {
                Champ<V> champ = insertIntoPrefix(index, key, value).withMirrorBat(mirrorBat, depth);
                return storage.put(owner, writer, champ.serialize(), writeHasher, tid).thenApply(h -> new Pair<>(champ, h));
            }

            return pushMappingsDownALevel(owner, writer, mappings,
                    key, hash, value, depth + 1, bitWidth, maxCollisions, mirrorBat, hasher, tid, storage, writeHasher)
                    .thenCompose(p -> {
                        Champ<V> champ = copyAndMigrateFromInlineToNode(bitpos, p).withMirrorBat(mirrorBat, depth);
                        return storage.put(owner, writer, champ.serialize(), writeHasher, tid).thenApply(h -> new Pair<>(champ, h));
                    });
        } else if (nodeMap.get(bitpos)) { // child node
            return getChild(owner, hash, depth, bitWidth, storage)
                    .thenCompose(child -> child.right.get().put(owner, writer, key, hash, depth + 1, expected, value,
                            bitWidth, maxCollisions, mirrorBat, hasher, tid, storage, writeHasher, child.left)
                            .thenCompose(newChild -> {
                                if (newChild.right.equals(child.left))
                                    return CompletableFuture.completedFuture(new Pair<>(this, ourHash));
                                Champ<V> champ = overwriteChildLink(bitpos, newChild);
                                return storage.put(owner, writer, champ.serialize(), writeHasher, tid).thenApply(h -> new Pair<>(champ, h));
                            }));
        } else {
            // no value
            Champ<V> champ = addNewPrefix(bitpos, key, value).withMirrorBat(mirrorBat, depth);
            return storage.put(owner, writer, champ.serialize(), writeHasher, tid).thenApply(h -> new Pair<>(champ, h));
        }
    }

    private CompletableFuture<Pair<Champ<V>, Multihash>> pushMappingsDownALevel(PublicKeyHash owner,
                                                                                SigningPrivateKeyAndPublicHash writer,
                                                                                KeyElement<V>[] mappings,
                                                                                ByteArrayWrapper key1,
                                                                                byte[] hash1,
                                                                                Optional<V> val1,
                                                                                final int depth,
                                                                                int bitWidth,
                                                                                int maxCollisions,
                                                                                Optional<BatId> mirrorBat,
                                                                                Function<ByteArrayWrapper, CompletableFuture<byte[]>> hasher,
                                                                                TransactionId tid,
                                                                                ContentAddressedStorage storage,
                                                                                Hasher writeHasher) {
        if (depth >= HASH_CODE_LENGTH) {
             throw new IllegalStateException("Hash collision!");
        }

        Champ<V> empty = empty(fromCbor);
        return storage.put(owner, writer, empty.serialize(), writeHasher, tid)
                .thenApply(h -> new Pair<>(empty, h))
                .thenCompose(p -> p.left.put(owner, writer, key1, hash1, depth, Optional.empty(), val1,
                        bitWidth, maxCollisions, mirrorBat, hasher, tid, storage, writeHasher, p.right))
                .thenCompose(one -> Futures.reduceAll(
                        Arrays.stream(mappings).collect(Collectors.toList()),
                        one,
                        (p, e) -> hasher.apply(e.key)
                                .thenCompose(eHash -> p.left.put(owner, writer, e.key, eHash, depth, Optional.empty(),
                                        e.valueHash, bitWidth, maxCollisions, mirrorBat, hasher, tid, storage, writeHasher, p.right)),
                        (a, b) -> a)
                );
    }

    private Champ<V> copyAndSetValue(final int setIndex, final int payloadIndex, final Optional<V> val) {
        final HashPrefixPayload<V>[] src = this.contents;
        final HashPrefixPayload<V>[] dst = Arrays.copyOf(src, src.length);

        HashPrefixPayload<V> existing = dst[setIndex];
        KeyElement<V>[] updated = new KeyElement[existing.mappings.length];
        System.arraycopy(existing.mappings, 0, updated, 0, existing.mappings.length);
        updated[payloadIndex] = new KeyElement<>(existing.mappings[payloadIndex].key, val);
        dst[setIndex] = new HashPrefixPayload<>(updated);

        return new Champ<>(dataMap, nodeMap, dst, fromCbor, mirrorBat);
    }

    private Champ<V> insertIntoPrefix(final int index, final ByteArrayWrapper key, final Optional<V> val) {
        final HashPrefixPayload<V>[] src = this.contents;
        final HashPrefixPayload<V>[] result = Arrays.copyOf(src, src.length);

        KeyElement<V>[] prefix = new KeyElement[src[index].mappings.length + 1];
        System.arraycopy(src[index].mappings, 0, prefix, 0, src[index].mappings.length);
        prefix[prefix.length - 1] = new KeyElement<>(key, val);
        // ensure canonical structure
        Arrays.sort(prefix, Comparator.comparing(m -> m.key));
        result[index] = new HashPrefixPayload<>(prefix);

        return new Champ<>(dataMap, nodeMap, result, fromCbor, mirrorBat);
    }

    private Champ<V> addNewPrefix(final int bitpos, final ByteArrayWrapper key, final Optional<V> val) {
        final int insertIndex = getIndex(dataMap, bitpos);

        final HashPrefixPayload<V>[] src = this.contents;
        final HashPrefixPayload<V>[] result = new HashPrefixPayload[src.length + 1];

        System.arraycopy(src, 0, result, 0, insertIndex);
        System.arraycopy(src, insertIndex, result, insertIndex + 1, src.length - insertIndex);
        result[insertIndex] = new HashPrefixPayload<>(new KeyElement[]{new KeyElement<>(key, val)});

        BitSet newDataMap = BitSet.valueOf(dataMap.toByteArray());
        newDataMap.set(bitpos);
        return new Champ<>(newDataMap, nodeMap, result, fromCbor, mirrorBat);
    }

    private Champ<V> copyAndMigrateFromInlineToNode(final int bitpos, final Pair<Champ<V>, Multihash> node) {

        final int oldIndex = getIndex(dataMap, bitpos);
        final int newIndex = this.contents.length - 1 - getIndex(nodeMap, bitpos);

        final HashPrefixPayload<V>[] src = this.contents;
        final HashPrefixPayload<V>[] dst = new HashPrefixPayload[src.length];

        // copy 'src' and remove 1 element at position oldIndex and insert 1 element at position newIndex
        if (oldIndex > newIndex)
            throw new IllegalStateException("Invalid champ!");
        System.arraycopy(src, 0, dst, 0, oldIndex);
        System.arraycopy(src, oldIndex + 1, dst, oldIndex, newIndex - oldIndex);
        dst[newIndex] = new HashPrefixPayload<>(MaybeMultihash.of(node.right));
        System.arraycopy(src, newIndex + 1, dst, newIndex + 1, src.length - newIndex - 1);

        BitSet newNodeMap = BitSet.valueOf(nodeMap.toByteArray());
        newNodeMap.set(bitpos);
        BitSet newDataMap = BitSet.valueOf(dataMap.toByteArray());
        newDataMap.set(bitpos, false);
        return new Champ<>(newDataMap, newNodeMap, dst, fromCbor, mirrorBat);
    }

    private Champ<V> overwriteChildLink(final int bitpos, final Pair<Champ<V>, Multihash> node) {

        final int setIndex = this.contents.length - 1 - getIndex(nodeMap, bitpos);

        final HashPrefixPayload<V>[] src = this.contents;
        final HashPrefixPayload<V>[] dst = Arrays.copyOf(src, src.length);

        dst[setIndex] = new HashPrefixPayload<>(MaybeMultihash.of(node.right));

        return new Champ<>(dataMap, nodeMap, dst, fromCbor, mirrorBat);
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
    public CompletableFuture<Pair<Champ<V>, Multihash>> remove(PublicKeyHash owner,
                                                               SigningPrivateKeyAndPublicHash writer,
                                                               ByteArrayWrapper key,
                                                               byte[] hash,
                                                               int depth,
                                                               Optional<V> expected,
                                                               int bitWidth,
                                                               int maxCollisions,
                                                               Optional<BatId> mirrorBat,
                                                               TransactionId tid,
                                                               ContentAddressedStorage storage,
                                                               Hasher writeHasher,
                                                               Multihash ourHash) {
        int bitpos = mask(hash, depth, bitWidth);

        if (dataMap.get(bitpos)) { // in place value
            final int dataIndex = getIndex(dataMap, bitpos);

            HashPrefixPayload<V> payload = contents[dataIndex];
            KeyElement<V>[] mappings = payload.mappings;
            for (int payloadIndex = 0; payloadIndex < mappings.length; payloadIndex++) {
                KeyElement<V> mapping = mappings[payloadIndex];
                final ByteArrayWrapper currentKey = mapping.key;
                final Optional<V> currentVal = mapping.valueHash;
                if (Objects.equals(currentKey, key)) {
                    if (!currentVal.equals(expected)) {
                        CompletableFuture<Pair<Champ<V>, Multihash>> err = new CompletableFuture<>();
                        err.completeExceptionally(new CasException(currentVal, expected));
                        return err;
                    }

                    if (this.keyCount() == maxCollisions + 1 && this.nodeCount() == 0) {
                        /*
				         * Create new node with remaining pairs. The new node
					     * will either
					     * a) become the new root returned, or
					     * b) be unwrapped and inlined during returning.
					     */
                        Champ<V> champ;
                        if (depth > 0) {
                            // inline all mappings into a single node because at a higher level, all mappings have the
                            // same hash prefix
                            final BitSet newDataMap = new BitSet();
                            newDataMap.set(mask(hash, 0, bitWidth));

                            KeyElement<V>[] remainingMappings = new KeyElement[maxCollisions];
                            int nextIndex = 0;
                            for (HashPrefixPayload<V> grouped : contents) {
                                for (KeyElement<V> pair : grouped.mappings) {
                                    if (!pair.key.equals(key))
                                        remainingMappings[nextIndex++] = pair;
                                }
                            }
                            Arrays.sort(remainingMappings, Comparator.comparing(x -> x.key));
                            HashPrefixPayload<V>[] oneBucket = new HashPrefixPayload[]{new HashPrefixPayload(remainingMappings)};

                            champ = new Champ<>(newDataMap, new BitSet(), oneBucket, fromCbor, Optional.empty()).withMirrorBat(mirrorBat, 0);
                        } else {
                            final BitSet newDataMap = BitSet.valueOf(dataMap.toByteArray());
                            boolean lastInPrefix = mappings.length == 1;
                            if (lastInPrefix)
                                newDataMap.clear(bitpos);
                            else
                                newDataMap.set(mask(hash, 0, bitWidth));

                            HashPrefixPayload<V>[] src = this.contents;
                            HashPrefixPayload<V>[] dst = new HashPrefixPayload[src.length - (lastInPrefix ? 1 : 0)];
                            System.arraycopy(src, 0, dst, 0, dataIndex);
                            System.arraycopy(src, dataIndex + 1, dst, dataIndex + (lastInPrefix ? 0 : 1), src.length - dataIndex - 1);
                            if (! lastInPrefix) {
                                KeyElement<V>[] remaining = new KeyElement[mappings.length - 1];
                                System.arraycopy(mappings, 0, remaining, 0, payloadIndex);
                                System.arraycopy(mappings, payloadIndex + 1, remaining, payloadIndex, mappings.length - payloadIndex - 1);
                                dst[dataIndex] = new HashPrefixPayload<>(remaining);
                            }

                            champ = new Champ(newDataMap, new BitSet(), dst, fromCbor, mirrorBat);
                        }
                        return storage.put(owner, writer, champ.serialize(), writeHasher, tid).thenApply(h -> new Pair<>(champ, h));
                    } else {
                        Champ<V> champ = removeMapping(bitpos, payloadIndex).withMirrorBat(mirrorBat, depth);
                        return storage.put(owner, writer, champ.serialize(), writeHasher, tid).thenApply(h -> new Pair<>(champ, h));
                    }
                }
            }
            return CompletableFuture.completedFuture(new Pair<>(this, ourHash));
        } else if (nodeMap.get(bitpos)) { // node (not value)
            return getChild(owner, hash, depth, bitWidth, storage)
                    .thenCompose(child -> child.right.get().remove(owner, writer, key, hash, depth + 1, expected,
                            bitWidth, maxCollisions, mirrorBat, tid, storage, writeHasher, child.left)
                            .thenCompose(newChild -> {
                                if (child.left.equals(newChild.right))
                                    return CompletableFuture.completedFuture(new Pair<>(this, ourHash));

                                if (newChild.left.contents.length == 0) {
                                    throw new IllegalStateException("Sub-node must have at least one element.");
                                } else if (newChild.left.nodeCount() == 0 && newChild.left.keyCount() == maxCollisions) {
                                    if (this.keyCount() == 0 && this.nodeCount() == 1) {
                                        // escalate singleton result (the child already has the depth corrected index and mirror bat)
                                        return CompletableFuture.completedFuture(newChild);
                                    } else {
                                        // inline value (move to front)
                                        Champ<V> champ = copyAndMigrateFromNodeToInline(bitpos, newChild.left);
                                        return storage.put(owner, writer, champ.serialize(), writeHasher, tid).thenApply(h -> new Pair<>(champ, h));
                                    }
                                } else {
                                    // modify current node (set replacement node)
                                    Champ<V> champ = overwriteChildLink(bitpos, newChild);
                                    return storage.put(owner, writer, champ.serialize(), writeHasher, tid).thenApply(h -> new Pair<>(champ, h));
                                }
                            }));
        }

        return CompletableFuture.completedFuture(new Pair<>(this, ourHash));
    }

    private Champ<V> copyAndMigrateFromNodeToInline(final int bitpos, final Champ<V> node) {

        final int oldIndex = this.contents.length - 1 - getIndex(nodeMap, bitpos);
        final int newIndex = getIndex(dataMap, bitpos);

        final HashPrefixPayload<V>[] src = this.contents;
        final HashPrefixPayload<V>[] dst = new HashPrefixPayload[src.length];

        // copy src and remove element at position oldIndex and insert element at position newIndex
        if (oldIndex < newIndex)
            throw new IllegalStateException("Invalid champ!");
        System.arraycopy(src, 0, dst, 0, newIndex);
        KeyElement<V>[] merged = new KeyElement[node.keyCount()];
        int count = 0;
        for (int i=0; i < node.contents.length; i++) {
            KeyElement<V>[] toAdd = node.contents[i].mappings;
            System.arraycopy(toAdd, 0, merged, count, toAdd.length);
            count += toAdd.length;
        }
        Arrays.sort(merged, Comparator.comparing(x -> x.key));
        dst[newIndex] = new HashPrefixPayload<>(merged);
        System.arraycopy(src, newIndex, dst, newIndex + 1, oldIndex - newIndex);
        System.arraycopy(src, oldIndex + 1, dst, oldIndex + 1, src.length - oldIndex - 1);

        BitSet newNodeMap = BitSet.valueOf(nodeMap.toByteArray());
        newNodeMap.set(bitpos, false);
        BitSet newDataMap = BitSet.valueOf(dataMap.toByteArray());
        newDataMap.set(bitpos, true);
        return new Champ<>(newDataMap, newNodeMap, dst, fromCbor, mirrorBat);
    }

    private Champ<V> removeMapping(final int bitpos, final int payloadIndex) {
        final int index = getIndex(dataMap, bitpos);
        final HashPrefixPayload<V>[] src = this.contents;
        KeyElement<V>[] existing = src[index].mappings;
        boolean lastInPrefix = existing.length == 1;
        final HashPrefixPayload<V>[] dst = new HashPrefixPayload[src.length - (lastInPrefix ? 1 : 0)];

        // copy src and remove element at position index
        System.arraycopy(src, 0, dst, 0, index);
        System.arraycopy(src, index + 1, dst, lastInPrefix ? index : index + 1, src.length - index - 1);
        if (! lastInPrefix) {
            KeyElement<V>[] remaining = new KeyElement[existing.length - 1];
            System.arraycopy(existing, 0, remaining, 0, payloadIndex);
            System.arraycopy(existing, payloadIndex + 1, remaining, payloadIndex, existing.length - payloadIndex - 1);
            dst[index] = new HashPrefixPayload<>(remaining);
        }

        BitSet newDataMap = BitSet.valueOf(dataMap.toByteArray());
        if (lastInPrefix)
            newDataMap.clear(bitpos);
        return new Champ<>(newDataMap, nodeMap, dst, fromCbor, mirrorBat);
    }

    public <T> CompletableFuture<T> reduceAllMappings(PublicKeyHash owner,
                                                      T identity,
                                                      BiFunction<T, Pair<ByteArrayWrapper, Optional<V>>, CompletableFuture<T>> consumer,
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
                                storage.get(owner, (Cid)payload.link.get(), Optional.empty())
                                        .thenApply(rawOpt -> Champ.fromCbor(rawOpt.orElseThrow(() -> new IllegalStateException("Hash not present! " + payload.link)), fromCbor))
                                        .thenCompose(child -> child.reduceAllMappings(owner, newRes, consumer, storage)) :
                                CompletableFuture.completedFuture(newRes)
                ), (a, b) -> a);
    }

    public CompletableFuture<Boolean> applyToAllMappings(PublicKeyHash owner,
                                                         Function<Pair<ByteArrayWrapper, Optional<V>>, CompletableFuture<Boolean>> mapper,
                                                         ContentAddressedStorage storage) {
        return Futures.combineAll(Arrays.stream(contents).parallel().map(payload ->
                        (! payload.isShard() ?
                                Futures.combineAll(
                                                Arrays.stream(payload.mappings).parallel()
                                                        .map(mapping -> mapper.apply(new Pair<>(mapping.key, mapping.valueHash)))
                                                        .collect(Collectors.toList()))
                                        .thenApply(x -> true) :
                                Futures.of(true)
                        ).thenCompose(newRes ->
                                payload.isShard() && payload.link.isPresent() ?
                                        storage.get(owner, (Cid)payload.link.get(), Optional.empty())
                                                .thenApply(rawOpt -> Champ.fromCbor(rawOpt.orElseThrow(() -> new IllegalStateException("Hash not present! " + payload.link)), fromCbor))
                                                .thenCompose(child -> child.applyToAllMappings(owner, mapper, storage)) :
                                        Futures.of(true)
                        )).collect(Collectors.toList()))
                .thenApply(x -> true);
    }

    private List<KeyElement<V>> getMappings() {
        return Arrays.stream(contents)
                .filter(p -> !p.isShard())
                .flatMap(p -> Arrays.stream(p.mappings))
                .collect(Collectors.toList());
    }

    private List<HashPrefixPayload<V>> getLinks() {
        return Arrays.stream(contents)
                .filter(p -> p.isShard())
                .collect(Collectors.toList());
    }

    public static <V extends Cborable> Optional<HashPrefixPayload<V>> getElement(int bitIndex,
                                                                                 int dataIndex,
                                                                                 int nodeIndex,
                                                                                 Optional<Champ<V>> c) {
        if (! c.isPresent())
            return Optional.empty();
        Champ<V> champ = c.get();
        if (champ.dataMap.get(bitIndex))
            return Optional.of(champ.contents[dataIndex]);
        if (champ.nodeMap.get(bitIndex))
            return Optional.of(champ.contents[champ.contents.length - 1 - nodeIndex]);
        return Optional.empty();
    }

    public static <V extends Cborable> CompletableFuture<Map<Integer, List<KeyElement<V>>>> hashAndMaskKeys(
            List<KeyElement<V>> mappings,
            int depth,
            int bitWidth,
            Function<ByteArrayWrapper, CompletableFuture<byte[]>> hasher) {
        List<Pair<KeyElement<V>, Integer>> empty = Collections.emptyList();
        return Futures.reduceAll(mappings, empty,
                (acc, m) -> hasher.apply(m.key)
                        .thenApply(hash -> new Pair<>(m, mask(hash, depth, bitWidth)))
                        .thenApply(p -> Stream.concat(acc.stream(), Stream.of(p)).collect(Collectors.toList())),
                (a, b) -> Stream.concat(a.stream(), b.stream()).collect(Collectors.toList()))
                .thenApply(hashed -> hashed.stream().collect(Collectors.groupingBy(p -> p.right)))
                .thenApply(grouped -> grouped.entrySet().stream()
                        .map(e -> new Pair<>(e.getKey(), e.getValue().stream().map(p -> p.left).collect(Collectors.toList())))
                        .collect(Collectors.toMap(p -> p.left, p -> p.right))
                );
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Champ<?> champ = (Champ<?>) o;
        return dataMap.equals(champ.dataMap) &&
                nodeMap.equals(champ.nodeMap) &&
                Arrays.equals(contents, champ.contents);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(dataMap, nodeMap);
        result = 31 * result + Arrays.hashCode(contents);
        return result;
    }

    private CborObject.CborList toCborList() {
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
                                                        m.valueHash.get().toCbor() :
                                                        new CborObject.CborNull()
                                        ))
                                        .collect(Collectors.toList()))))
                        .collect(Collectors.toList()))
        ));
    }

    @Override
    public CborObject toCbor() {
        if (mirrorBat.isPresent()) {
            SortedMap<String, Cborable> state = new TreeMap<>();
            state.put("d", toCborList());
            state.put("bats", new CborObject.CborList(Arrays.asList(mirrorBat.get())));
            return CborObject.CborMap.build(state);
        }
        return toCborList();
    }

    public static <V extends Cborable> Champ<V> fromCbor(Cborable cbor, Function<Cborable, V> fromCbor) {
        if (cbor instanceof CborObject.CborMap) {
            Optional<BatId> mirrorBat = ((CborObject.CborMap) cbor).getList("bats", BatId::fromCbor).stream().findFirst();
            return fromCborList(((CborObject.CborMap) cbor).get("d"), fromCbor).withBat(mirrorBat);
        }
        return fromCborList(cbor, fromCbor);
    }

    public static <V extends Cborable> Champ<V> fromCborList(Cborable cbor, Function<Cborable, V> fromCbor) {
        if (! (cbor instanceof CborObject.CborList))
            throw new IllegalStateException("Invalid cbor for CHAMP! " + cbor);
        List<? extends Cborable> list = ((CborObject.CborList) cbor).value;

        if (! (list.get(0) instanceof CborObject.CborByteArray))
            throw new IllegalStateException("Invalid cbor for a champ, is this a btree?");
        BitSet dataMap = BitSet.valueOf(((CborObject.CborByteArray)list.get(0)).value);
        BitSet nodeMap = BitSet.valueOf(((CborObject.CborByteArray)list.get(1)).value);
        List<? extends Cborable> contentsCbor = ((CborObject.CborList) list.get(2)).value;

        List<HashPrefixPayload<V>> contents = new ArrayList<>();
        for (int i=0; i < contentsCbor.size(); i++) {
            Cborable keyOrHash = contentsCbor.get(i);
            if (keyOrHash instanceof CborObject.CborList) {
                List<KeyElement<V>> mappings = new ArrayList<>();
                List<? extends Cborable> mappingsCbor = ((CborObject.CborList) keyOrHash).value;
                for (int j=0; j < mappingsCbor.size(); j += 2) {
                    byte[] key = ((CborObject.CborByteArray) mappingsCbor.get(j)).value;
                    Cborable value = mappingsCbor.get(j + 1);
                    mappings.add(new KeyElement<>(new ByteArrayWrapper(key),
                        value instanceof CborObject.CborNull ?
                                Optional.empty() :
                                Optional.of(fromCbor.apply(value))));
                }
                contents.add(new HashPrefixPayload<>(mappings.toArray(new KeyElement[0])));
            } else {
                contents.add(new HashPrefixPayload<>(MaybeMultihash.of(((CborObject.CborMerkleLink)keyOrHash).target)));
            }
        }
        return new Champ<>(dataMap, nodeMap, contents.toArray(new HashPrefixPayload[contents.size()]), fromCbor, Optional.empty());
    }
}
