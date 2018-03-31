package peergos.shared.hamt;

import peergos.shared.cbor.*;
import peergos.shared.crypto.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.merklebtree.*;
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
        return ((hash[index] >> shift) & ((1 << lowBits) - 1)) |
                ((hash[index + 1] & ((1 << hiBits) - 1)) << lowBits);
    }

    private static int index(BitSet bitmap, int bitpos) {
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
        int index = contents.length - 1 - index(this.nodeMap, bitpos);
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

    public CompletableFuture<MaybeMultihash> get(ByteArrayWrapper key, byte[] hash, int depth, int bitWidth, ContentAddressedStorage storage) {
        final int bitpos = mask(hash, depth, bitWidth);

        if (dataMap.get(bitpos)) { // local value
            int index = index(this.dataMap, bitpos);
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

    public CompletableFuture<Pair<Champ, Multihash>> put(SigningPrivateKeyAndPublicHash writer,
                                                         ByteArrayWrapper key,
                                                         byte[] hash,
                                                         int depth,
                                                         MaybeMultihash expected,
                                                         MaybeMultihash value,
                                                         int bitWidth,
                                                         int maxCollisions,
                                                         Function<ByteArrayWrapper, byte[]> hasher,
                                                         ContentAddressedStorage storage,
                                                         Multihash ourHash) {
        int bitpos = mask(hash, depth, bitWidth);

        if (dataMap.get(bitpos)) { // local value
            int index = index(this.dataMap, bitpos);
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
                    return storage.put(writer, champ.serialize()).thenApply(h -> new Pair<>(champ, h));
                }
            }
            if (mappings.length < maxCollisions) {
                Champ champ = insertIntoPrefix(index, key, value);
                return storage.put(writer, champ.serialize()).thenApply(h -> new Pair<>(champ, h));
            }

            return pushMappingsDownALevel(writer, mappings,
                    key, hash, value, depth + 1, bitWidth, maxCollisions, hasher, storage)
                    .thenCompose(p -> {
                        Champ champ = copyAndMigrateFromInlineToNode(bitpos, p);
                        return storage.put(writer, champ.serialize()).thenApply(h -> new Pair<>(champ, h));
                    });
        } else if (nodeMap.get(bitpos)) { // child node
            return getChild(hash, depth, bitWidth, storage)
                    .thenCompose(child -> child.right.get().put(writer, key, hash, depth + 1, expected, value,
                            bitWidth, maxCollisions, hasher, storage, child.left)
                            .thenCompose(newChild -> {
                                if (newChild.right.equals(child.left))
                                    return CompletableFuture.completedFuture(new Pair<>(this, ourHash));
                                Champ champ = overwriteChildLink(bitpos, newChild);
                                return storage.put(writer, champ.serialize()).thenApply(h -> new Pair<>(champ, h));
                            }));
        } else {
            // no value
            Champ champ = addNewPrefix(bitpos, key, value);
            return storage.put(writer, champ.serialize()).thenApply(h -> new Pair<>(champ, h));
        }
    }

    private CompletableFuture<Pair<Champ, Multihash>> pushMappingsDownALevel(SigningPrivateKeyAndPublicHash writer,
                                                                             KeyElement[] mappings,
                                                                             ByteArrayWrapper key1,
                                                                             byte[] hash1,
                                                                             MaybeMultihash val1,
                                                                             final int depth,
                                                                             int bitWidth,
                                                                             int maxCollisions,
                                                                             Function<ByteArrayWrapper, byte[]> hasher,
                                                                             ContentAddressedStorage storage) {
        if (depth >= HASH_CODE_LENGTH) {
             throw new IllegalStateException("Hash collision!");
        }

        Champ empty = empty();
        return storage.put(writer, empty.serialize())
                .thenApply(h -> new Pair<>(empty, h))
                .thenCompose(p -> p.left.put(writer, key1, hash1, depth, MaybeMultihash.empty(), val1,
                        bitWidth, maxCollisions, hasher, storage, p.right))
                .thenCompose(one -> Futures.reduceAll(
                        Arrays.stream(mappings).collect(Collectors.toList()),
                        one,
                        (p, e) -> p.left.put(writer, e.key, hasher.apply(e.key), depth, MaybeMultihash.empty(), e.valueHash,
                                bitWidth, maxCollisions, hasher, storage, p.right),
                        (a, b) -> a)
                );
    }

    private Champ copyAndSetValue(final int setIndex, final int payloadIndex, final MaybeMultihash val) {
        final HashPrefixPayload[] src = this.contents;
        final HashPrefixPayload[] dst = new HashPrefixPayload[src.length];

        System.arraycopy(src, 0, dst, 0, src.length);
        HashPrefixPayload existing = dst[setIndex];
        KeyElement[] updated = new KeyElement[existing.mappings.length];
        System.arraycopy(existing.mappings, 0, updated, 0, existing.mappings.length);
        updated[payloadIndex] = new KeyElement(existing.mappings[payloadIndex].key, val);
        dst[setIndex] = new HashPrefixPayload(updated, null);

        return new Champ(dataMap, nodeMap, dst);
    }

    private Champ insertIntoPrefix(final int index, final ByteArrayWrapper key, final MaybeMultihash val) {
        final HashPrefixPayload[] src = this.contents;
        final HashPrefixPayload[] result = new HashPrefixPayload[src.length];

        System.arraycopy(src, 0, result, 0, src.length);
        KeyElement[] prefix = new KeyElement[src[index].mappings.length + 1];
        System.arraycopy(src[index].mappings, 0, prefix, 0, src[index].mappings.length);
        prefix[prefix.length - 1] = new KeyElement(key, val);
        // ensure canonical structure
        Arrays.sort(prefix, Comparator.comparing(m -> m.key));
        result[index] = new HashPrefixPayload(prefix, null);

        return new Champ(dataMap, nodeMap, result);
    }

    private Champ addNewPrefix(final int bitpos, final ByteArrayWrapper key, final MaybeMultihash val) {
        final int insertIndex = index(dataMap, bitpos);

        final HashPrefixPayload[] src = this.contents;
        final HashPrefixPayload[] result = new HashPrefixPayload[src.length + 1];

        System.arraycopy(src, 0, result, 0, insertIndex);
        System.arraycopy(src, insertIndex, result, insertIndex + 1, src.length - insertIndex);
        result[insertIndex] = new HashPrefixPayload(new KeyElement[]{new KeyElement(key, val)}, null);

        BitSet newDataMap = BitSet.valueOf(dataMap.toByteArray());
        newDataMap.set(bitpos);
        return new Champ(newDataMap, nodeMap, result);
    }

    private Champ copyAndMigrateFromInlineToNode(final int bitpos, final Pair<Champ, Multihash> node) {

        final int oldIndex = index(dataMap, bitpos);
        final int newIndex = this.contents.length - 1 - index(nodeMap, bitpos);

        final HashPrefixPayload[] src = this.contents;
        final HashPrefixPayload[] dst = new HashPrefixPayload[src.length];

        // copy 'src' and remove 1 element at position oldIndex and insert 1 element at position newIndex
        assert oldIndex <= newIndex;
        System.arraycopy(src, 0, dst, 0, oldIndex);
        System.arraycopy(src, oldIndex + 1, dst, oldIndex, newIndex - oldIndex);
        dst[newIndex] = new HashPrefixPayload(null, MaybeMultihash.of(node.right));
        System.arraycopy(src, newIndex + 1, dst, newIndex + 1, src.length - newIndex - 1);

        BitSet newNodeMap = BitSet.valueOf(nodeMap.toByteArray());
        newNodeMap.set(bitpos);
        BitSet newDataMap = BitSet.valueOf(dataMap.toByteArray());
        newDataMap.set(bitpos, false);
        return new Champ(newDataMap, newNodeMap, dst);
    }

    private Champ overwriteChildLink(final int bitpos, final Pair<Champ, Multihash> node) {

        final int setIndex = this.contents.length - 1 - index(nodeMap, bitpos);

        final HashPrefixPayload[] src = this.contents;
        final HashPrefixPayload[] dst = new HashPrefixPayload[src.length];

        System.arraycopy(src, 0, dst, 0, src.length);
        dst[setIndex] = new HashPrefixPayload(null, MaybeMultihash.of(node.right));

        return new Champ(dataMap, nodeMap, dst);
    }

    public CompletableFuture<Pair<Champ, Multihash>> remove(SigningPrivateKeyAndPublicHash writer,
                                                            ByteArrayWrapper key,
                                                            byte[] hash,
                                                            int depth,
                                                            MaybeMultihash expected,
                                                            int bitWidth,
                                                            int maxCollisions,
                                                            ContentAddressedStorage storage,
                                                            Multihash ourHash) {
        int bitpos = mask(hash, depth, bitWidth);

        if (dataMap.get(bitpos)) { // in place value
            final int dataIndex = index(dataMap, bitpos);

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
						 * will a) either become the new root returned, or b)
						 * unwrapped and inlined during returning.
						 */
                        final BitSet newDataMap = BitSet.valueOf((depth == 0) ? dataMap.toByteArray() : new byte[0]);
                        boolean lastInPrefix = mappings.length == 1;
                        if (depth == 0 && lastInPrefix)
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
                            dst[dataIndex] = new HashPrefixPayload(remaining, null);
                        }

                        Champ champ = new Champ(newDataMap, new BitSet(), dst);
                        return storage.put(writer, champ.serialize()).thenApply(h -> new Pair<>(champ, h));
                    } else {
                        Champ champ = removeMapping(bitpos, payloadIndex);
                        return storage.put(writer, champ.serialize()).thenApply(h -> new Pair<>(champ, h));
                    }
                }
            }
            return CompletableFuture.completedFuture(new Pair<>(this, ourHash));
        } else if (nodeMap.get(bitpos)) { // node (not value)
            return getChild(hash, depth, bitWidth, storage)
                    .thenCompose(child -> child.right.get().remove(writer, key, hash, depth + 1, expected, bitWidth, maxCollisions, storage, child.left)
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
                                        return storage.put(writer, champ.serialize()).thenApply(h -> new Pair<>(champ, h));
                                    }
                                } else {
                                    // modify current node (set replacement node)
                                    Champ champ = overwriteChildLink(bitpos, newChild);
                                    return storage.put(writer, champ.serialize()).thenApply(h -> new Pair<>(champ, h));
                                }
                            }));
        }

        return CompletableFuture.completedFuture(new Pair<>(this, ourHash));
    }

    private Champ copyAndMigrateFromNodeToInline(final int bitpos, final Champ node) {

        final int oldIndex = this.contents.length - 1 - index(nodeMap, bitpos);
        final int newIndex = index(dataMap, bitpos);

        final HashPrefixPayload[] src = this.contents;
        final HashPrefixPayload[] dst = new HashPrefixPayload[src.length];

        // copy src and remove element at position oldIndex and insert element at position newIndex
        assert oldIndex >= newIndex;
        System.arraycopy(src, 0, dst, 0, newIndex);
        KeyElement[] merged = new KeyElement[node.keyCount()];
        int count = 0;
        for (int i=0; i < node.contents.length; i++) {
            KeyElement[] toAdd = node.contents[i].mappings;
            System.arraycopy(toAdd, 0, merged, count, toAdd.length);
            count += toAdd.length;
        }
        Arrays.sort(merged, Comparator.comparing(x -> x.key));
        dst[newIndex] = new HashPrefixPayload(merged, null);
        System.arraycopy(src, newIndex, dst, newIndex + 1, oldIndex - newIndex);
        System.arraycopy(src, oldIndex + 1, dst, oldIndex + 1, src.length - oldIndex - 1);

        BitSet newNodeMap = BitSet.valueOf(nodeMap.toByteArray());
        newNodeMap.set(bitpos, false);
        BitSet newDataMap = BitSet.valueOf(dataMap.toByteArray());
        newDataMap.set(bitpos, true);
        return new Champ(newDataMap, newNodeMap, dst);
    }

    private Champ removeMapping(final int bitpos, final int payloadIndex) {
        final int index = index(dataMap, bitpos);
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
            dst[index] = new HashPrefixPayload(remaining, null);
        }

        BitSet newDataMap = BitSet.valueOf(dataMap.toByteArray());
        if (lastInPrefix)
            newDataMap.clear(bitpos);
        return new Champ(newDataMap, nodeMap, dst);
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
                contents.add(new HashPrefixPayload(mappings.toArray(new KeyElement[0]), null));
            } else {
                contents.add(new HashPrefixPayload(null, MaybeMultihash.of(((CborObject.CborMerkleLink)keyOrHash).target)));
            }
        }
        return new Champ(dataMap, nodeMap, contents.toArray(new HashPrefixPayload[contents.size()]));
    }
}
