package peergos.shared.hamt;

import peergos.shared.cbor.*;
import peergos.shared.crypto.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.merklebtree.*;
import peergos.shared.storage.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

/**
 * A Compressed Hash-Array Mapped Prefix-tree (CHAMP), a refinement of a Hash Array Mapped Trie (HAMT)
 */
public class Champ implements Cborable {

    private static final int HASH_CODE_LENGTH = 32;

    private static class KeyElement implements Cborable {
        public final ByteArrayWrapper key;
        public final MaybeMultihash valueHash;

        public KeyElement(ByteArrayWrapper key, MaybeMultihash valueHash) {
            this.key = key;
            this.valueHash = valueHash;
        }

        @Override
        public CborObject toCbor() {
            return new CborObject.CborList(Arrays.asList(
                    key == null ? new CborObject.CborNull() : new CborObject.CborByteArray(key.data),
                    valueHash.isPresent() ? new CborObject.CborMerkleLink(valueHash.get()) : new CborObject.CborNull()));
        }

        public static KeyElement fromCbor(Cborable cbor) {
            if (! (cbor instanceof CborObject.CborList))
                throw new IllegalStateException("Invalid cbor for KeyElement! " + cbor);
            List<? extends Cborable> list = ((CborObject.CborList) cbor).value;
            ByteArrayWrapper key = list.get(0) instanceof CborObject.CborNull ?
                    null :
                    new ByteArrayWrapper(((CborObject.CborByteArray) list.get(0)).value);
            Cborable rawValue = list.get(1);
            MaybeMultihash value = rawValue instanceof CborObject.CborNull ?
                    MaybeMultihash.empty() :
                    MaybeMultihash.of(((CborObject.CborMerkleLink)rawValue).target);
            return new KeyElement(key, value);
        }
    }

    private static final Champ EMPTY = new Champ(new BitSet(), new BitSet(), new KeyElement[0]);

    public static Champ empty() {
        return EMPTY;
    }

    private final BitSet dataMap, nodeMap;
    private final KeyElement[] contents;

    public Champ(BitSet dataMap, BitSet nodeMap, KeyElement[] contents) {
        this.dataMap = dataMap;
        this.nodeMap = nodeMap;
        this.contents = contents;
        for (int i=0; i< contents.length; i++)
            if (contents[i] == null)
                throw new IllegalStateException();
    }

    private int keyCount() {
        int count = 0;
        for (KeyElement p : contents) {
            if (p.key != null)
                count++;
        }
        return count;
    }

    private int nodeCount() {
        int count = 0;
        for (KeyElement p : contents) {
            if (p.key == null)
                count++;
        }
        return count;
    }

    private static int mask(ByteArrayWrapper key, int depth) {
        return key.data[depth] & 0xff;
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

    MaybeMultihash getValue(ByteArrayWrapper key, int depth) {
        int bitpos = mask(key, depth);
        int index = index(this.dataMap, bitpos);
        return contents[index].valueHash;
    }

    CompletableFuture<Pair<Multihash, Optional<Champ>>> getChild(ByteArrayWrapper key, int depth, ContentAddressedStorage storage) {
        int bitpos = mask(key, depth);
        int index = contents.length - 1 - index(this.nodeMap, bitpos);
        Multihash childHash = contents[index].valueHash.get();
        return storage.get(childHash)
                .thenApply(x -> new Pair<>(childHash, x.map(Champ::fromCbor)));
    }


    public CompletableFuture<MaybeMultihash> get(ByteArrayWrapper key, int depth, ContentAddressedStorage storage) {
        final int bitpos = mask(key, depth);

        if (dataMap.get(bitpos)) { // local value
            int index = index(this.dataMap, bitpos);
            if (contents[index].key.equals(key)) {
                return CompletableFuture.completedFuture(contents[index].valueHash);
            }

            return CompletableFuture.completedFuture(MaybeMultihash.empty());
        }

        if (nodeMap.get(bitpos)) { // child node
            return getChild(key, depth, storage)
                    .thenCompose(child -> child.right.map(c -> c.get(key, depth + 1, storage))
                            .orElse(CompletableFuture.completedFuture(MaybeMultihash.empty())));
        }

        return CompletableFuture.completedFuture(MaybeMultihash.empty());
    }

    public CompletableFuture<Pair<Champ, Multihash>> put(SigningPrivateKeyAndPublicHash writer,
                                                         ByteArrayWrapper key,
                                                         int depth,
                                                         MaybeMultihash expected,
                                                         Multihash value,
                                                         ContentAddressedStorage storage,
                                                         Multihash ourHash) {
        int bitpos = mask(key, depth);

        if (dataMap.get(bitpos)) { // local value
            int index = index(this.dataMap, bitpos);
            final ByteArrayWrapper currentKey = contents[index].key;
            final MaybeMultihash currentVal = contents[index].valueHash;

            if (currentKey.equals(key)) {
                if (! currentVal.equals(expected)) {
                    CompletableFuture<Pair<Champ, Multihash>> err = new CompletableFuture<>();
                    err.completeExceptionally(new IllegalStateException(
                            "Champ CAS exception: expected " + expected +", actual: " + currentVal));
                    return err;
                }

                // update mapping
                Champ champ = copyAndSetValue(bitpos, MaybeMultihash.of(value));
                return storage.put(writer, champ.serialize()).thenApply(h -> new Pair<>(champ, h));
            } else {
                return mergeTwoKeyValPairs(writer, currentKey,
                        currentVal, key, MaybeMultihash.of(value), depth + 1, storage)
                        .thenCompose(p -> {
                            Champ champ = copyAndMigrateFromInlineToNode(bitpos, p);
                            return storage.put(writer, champ.serialize()).thenApply(h -> new Pair<>(champ, h));
                        });
            }
        } else if (nodeMap.get(bitpos)) { // child node
            return getChild(key, depth, storage)
                    .thenCompose(child -> child.right.get().put(writer, key, depth + 1, expected, value, storage, child.left)
                            .thenCompose(newChild -> {
                                if (newChild.right.equals(child.left))
                                    return CompletableFuture.completedFuture(new Pair<>(this, ourHash));
                                Champ champ = copyAndSetNode(bitpos, newChild);
                                return storage.put(writer, champ.serialize()).thenApply(h -> new Pair<>(champ, h));
                            }));
        } else {
            // no value
            Champ champ = copyAndInsertValue(bitpos, key, MaybeMultihash.of(value));
            return storage.put(writer, champ.serialize()).thenApply(h -> new Pair<>(champ, h));
        }
    }

    private CompletableFuture<Pair<Champ, Multihash>> mergeTwoKeyValPairs(SigningPrivateKeyAndPublicHash writer,
                                                                          ByteArrayWrapper key0, MaybeMultihash val0,
                                                                          ByteArrayWrapper key1, MaybeMultihash val1,
                                                                          final int depth,
                                                                          ContentAddressedStorage storage) {
        assert !(key0.equals(key1));

        if (depth >= HASH_CODE_LENGTH) {
             throw new IllegalStateException("Hash collision!");
        }

        final int mask0 = mask(key0, depth);
        final int mask1 = mask(key1, depth);

        if (mask0 != mask1) {
            // both nodes fit on same level
            final BitSet dataMap = new BitSet();
            dataMap.set(mask0);
            dataMap.set(mask1);

            if (mask0 < mask1) {
                Champ champ = new Champ(dataMap, new BitSet(), new KeyElement[]{new KeyElement(key0, val0), new KeyElement(key1, val1)});
                return storage.put(writer, champ.serialize()).thenApply(h -> new Pair<>(champ, h));
            } else {
                Champ champ = new Champ(dataMap, new BitSet(), new KeyElement[]{new KeyElement(key1, val1), new KeyElement(key0, val0)});
                return storage.put(writer, champ.serialize()).thenApply(h -> new Pair<>(champ, h));
            }
        } else {
            // values fit on a deeper level
            return mergeTwoKeyValPairs(writer, key0, val0, key1, val1,depth + 1, storage)
                    .thenCompose(p -> {
                        final BitSet nodeMap = new BitSet();
                        nodeMap.set(mask0);
                        Champ champ = new Champ(new BitSet(), nodeMap, new KeyElement[]{new KeyElement(null, MaybeMultihash.of(p.right))});
                        return storage.put(writer, champ.serialize()).thenApply(h -> new Pair<>(champ, h));
                    });
        }
    }

    private Champ copyAndSetValue(final int bitpos, final MaybeMultihash val) {
        final int setIndex = index(dataMap, bitpos);

        final KeyElement[] src = this.contents;
        final KeyElement[] dst = new KeyElement[src.length];

        System.arraycopy(src, 0, dst, 0, src.length);
        dst[setIndex] = new KeyElement(contents[setIndex].key, val);

        return new Champ(dataMap, nodeMap, dst);
    }

    private Champ copyAndInsertValue(final int bitpos, final ByteArrayWrapper key, final MaybeMultihash val) {
        final int insertIndex = index(dataMap, bitpos);

        final KeyElement[] src = this.contents;
        final KeyElement[] result = new KeyElement[src.length + 1];

        System.arraycopy(src, 0, result, 0, insertIndex);
        result[insertIndex] = new KeyElement(key, val);
        System.arraycopy(src, insertIndex, result, insertIndex + 1, src.length - insertIndex);

        BitSet newDataMap = BitSet.valueOf(dataMap.toByteArray());
        newDataMap.set(bitpos);
        return new Champ(newDataMap, nodeMap, result);
    }

    private Champ copyAndMigrateFromInlineToNode(final int bitpos, final Pair<Champ, Multihash> node) {

        final int oldIndex = index(dataMap, bitpos);
        final int newIndex = this.contents.length - 1 - index(nodeMap, bitpos);

        final KeyElement[] src = this.contents;
        final KeyElement[] dst = new KeyElement[src.length];

        // copy 'src' and remove 1 element at position oldIndex and insert 1 element at position newIndex
        assert oldIndex <= newIndex;
        System.arraycopy(src, 0, dst, 0, oldIndex);
        System.arraycopy(src, oldIndex + 1, dst, oldIndex, newIndex - oldIndex);
        dst[newIndex] = new KeyElement(null, MaybeMultihash.of(node.right));
        System.arraycopy(src, newIndex + 1, dst, newIndex + 1, src.length - newIndex - 1);

        BitSet newNodeMap = BitSet.valueOf(nodeMap.toByteArray());
        newNodeMap.set(bitpos);
        BitSet newDataMap = BitSet.valueOf(dataMap.toByteArray());
        newDataMap.set(bitpos, false);
        return new Champ(newDataMap, newNodeMap, dst);
    }

    private Champ copyAndSetNode(final int bitpos, final Pair<Champ, Multihash> node) {

        final int setIndex = this.contents.length - 1 - index(nodeMap, bitpos);

        final KeyElement[] src = this.contents;
        final KeyElement[] dst = new KeyElement[src.length];

        System.arraycopy(src, 0, dst, 0, src.length);
        dst[setIndex] = new KeyElement(null, MaybeMultihash.of(node.right));

        return new Champ(dataMap, nodeMap, dst);
    }

    public CompletableFuture<Pair<Champ, Multihash>> remove(SigningPrivateKeyAndPublicHash writer,
                                                            ByteArrayWrapper key,
                                                            int depth,
                                                            ContentAddressedStorage storage,
                                                            Multihash ourHash) {
        int bitpos = mask(key, depth);

        if (dataMap.get(bitpos)) { // in place value
            final int dataIndex = index(dataMap, bitpos);

            if (Objects.equals(contents[dataIndex].key, key)) {
                if (this.keyCount() == 2 && this.nodeCount() == 0) {
                    /*
						 * Create new node with remaining pair. The new node
						 * will a) either become the new root returned, or b)
						 * unwrapped and inlined during returning.
						 */
                    final BitSet newDataMap = (depth == 0) ? BitSet.valueOf(dataMap.toByteArray()) : new BitSet();
                    if (depth == 0)
                        newDataMap.clear(bitpos);
                    else
                        newDataMap.set(mask(key, 0));

                    Champ champ = dataIndex == 0 ?
                            new Champ(newDataMap, new BitSet(), new KeyElement[] {contents[1]}) :
                            new Champ(newDataMap, new BitSet(), new KeyElement[] {contents[0]});
                    return storage.put(writer, champ.serialize()).thenApply(h -> new Pair<>(champ, h));
                } else {
                    Champ champ = copyAndRemoveValue(bitpos);
                    return storage.put(writer, champ.serialize()).thenApply(h -> new Pair<>(champ, h));
                }
            } else {
                return CompletableFuture.completedFuture(new Pair<>(this, ourHash));
            }
        } else if (nodeMap.get(bitpos)) { // node (not value)
            return getChild(key, depth, storage)
                    .thenCompose(child -> child.right.get().remove(writer, key, depth + 1, storage, child.left)
                            .thenCompose(newChild -> {
                                if (child.left.equals(newChild.right))
                                    return CompletableFuture.completedFuture(new Pair<>(this, ourHash));

                                if (newChild.left.contents.length == 0) {
                                    throw new IllegalStateException("Sub-node must have at least one element.");
                                } else if (newChild.left.nodeCount() == 0 && newChild.left.keyCount() == 1) {
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
                                    Champ champ = copyAndSetNode(bitpos, newChild);
                                    return storage.put(writer, champ.serialize()).thenApply(h -> new Pair<>(champ, h));
                                }
                            }));
        }

        return CompletableFuture.completedFuture(new Pair<>(this, ourHash));
    }

    private Champ copyAndMigrateFromNodeToInline(final int bitpos, final Champ node) {

        final int oldIndex = this.contents.length - 1 - index(nodeMap, bitpos);
        final int newIndex = index(dataMap, bitpos);

        final KeyElement[] src = this.contents;
        final KeyElement[] dst = new KeyElement[src.length];

        // copy src and remove element at position oldIndex and insert element at position newIndex
        assert oldIndex >= newIndex;
        System.arraycopy(src, 0, dst, 0, newIndex);
        dst[newIndex] = node.contents[0];
        System.arraycopy(src, newIndex, dst, newIndex + 1, oldIndex - newIndex);
        System.arraycopy(src, oldIndex + 1, dst, oldIndex + 1, src.length - oldIndex - 1);

        BitSet newNodeMap = BitSet.valueOf(nodeMap.toByteArray());
        newNodeMap.set(bitpos, false);
        BitSet newDataMap = BitSet.valueOf(dataMap.toByteArray());
        newDataMap.set(bitpos, true);
        return new Champ(newDataMap, newNodeMap, dst);
    }

    private Champ copyAndRemoveValue(final int bitpos) {
        final int index = index(dataMap, bitpos);

        final KeyElement[] src = this.contents;
        final KeyElement[] dst = new KeyElement[src.length - 1];

        // copy src and remove element at position index
        System.arraycopy(src, 0, dst, 0, index);
        System.arraycopy(src, index + 1, dst, index, src.length - index - 1);

        BitSet newDataMap = BitSet.valueOf(dataMap.toByteArray());
        newDataMap.clear(bitpos);
        return new Champ(newDataMap, nodeMap, dst);
    }

    @Override
    public CborObject toCbor() {
        return new CborObject.CborList(Arrays.asList(
                new CborObject.CborByteArray(dataMap.toByteArray()),
                new CborObject.CborByteArray(nodeMap.toByteArray()),
                new CborObject.CborList(Arrays.stream(contents).map(Cborable::toCbor).collect(Collectors.toList()))
        ));
    }

    public static Champ fromCbor(Cborable cbor) {
        if (! (cbor instanceof CborObject.CborList))
            throw new IllegalStateException("Invalid cbor for CHAMP! " + cbor);
        List<? extends Cborable> list = ((CborObject.CborList) cbor).value;

        BitSet dataMap = BitSet.valueOf(((CborObject.CborByteArray)list.get(0)).value);
        BitSet nodeMap = BitSet.valueOf(((CborObject.CborByteArray)list.get(1)).value);
        List<KeyElement> contents = ((CborObject.CborList)list.get(2)).value.stream()
                .map(KeyElement::fromCbor)
                .collect(Collectors.toList());
        return new Champ(dataMap, nodeMap, contents.toArray(new KeyElement[contents.size()]));
    }
}
