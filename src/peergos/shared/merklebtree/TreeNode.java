package peergos.shared.merklebtree;

import peergos.shared.cbor.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.io.ipfs.multiaddr.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.storage.ContentAddressedStorage;
import peergos.shared.util.*;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.stream.*;

public class TreeNode implements Cborable {
    public final MaybeMultihash hash;
    public final SortedSet<KeyElement> keys;

    private TreeNode(MaybeMultihash leftChildHash, SortedSet<KeyElement> keys, MaybeMultihash ourHash) {
        this.keys = new TreeSet<>();
        this.keys.addAll(keys);
        KeyElement zero = new KeyElement(new ByteArrayWrapper(new byte[0]), MaybeMultihash.EMPTY(), leftChildHash);
        if (!keys.contains(zero))
            this.keys.add(zero);
        this.hash = ourHash;
    }

    private TreeNode(MaybeMultihash leftChildHash, SortedSet<KeyElement> keys) {
        this(leftChildHash, keys, MaybeMultihash.EMPTY());
    }

    private TreeNode(Multihash leftChildHash, SortedSet<KeyElement> keys) {
        this(MaybeMultihash.of(leftChildHash), keys, MaybeMultihash.EMPTY());
    }

    public TreeNode(SortedSet<KeyElement> keys) {
        this(MaybeMultihash.EMPTY(), keys, MaybeMultihash.EMPTY());
    }

    public TreeNode(SortedSet<KeyElement> keys, MaybeMultihash ourHash) {
        this(MaybeMultihash.EMPTY(), keys, ourHash);
    }

    public TreeNode(SortedSet<KeyElement> keys, Multihash ourHash) {
        this(MaybeMultihash.EMPTY(), keys, MaybeMultihash.of(ourHash));
    }

    private TreeNode(TreeNode node, MaybeMultihash hash) {
        this(MaybeMultihash.EMPTY(), node.keys, hash);
    }

    private TreeNode(TreeNode node, Multihash hash) {
        this(node, MaybeMultihash.of(hash));
    }
    private TreeNode withHash(MaybeMultihash hash) {
        return new TreeNode(keys, hash);
    }


    public CompletableFuture<MaybeMultihash> get(ByteArrayWrapper key, ContentAddressedStorage storage) {
        KeyElement dummy = KeyElement.dummy(key);
        SortedSet<KeyElement> tailSet = keys.tailSet(dummy);
        KeyElement nextSmallest;
        if (tailSet.size() == 0) {
            nextSmallest = keys.last();
        } else {
            nextSmallest = tailSet.first();
            if (!nextSmallest.key.equals(key))
                nextSmallest = keys.headSet(dummy).last();
        }
        if (nextSmallest.key.equals(key))
            return CompletableFuture.completedFuture(nextSmallest.valueHash);
        if (! nextSmallest.targetHash.isPresent())
            return CompletableFuture.completedFuture(MaybeMultihash.EMPTY());

        Multihash nextSmallestHash = nextSmallest.targetHash.get();
        return storage.get(nextSmallestHash)
                .thenCompose(rawOpt -> TreeNode.fromCbor(rawOpt.orElseThrow(() -> new IllegalStateException("Hash not present! " + nextSmallestHash)))
                        .get(key, storage));
    }

    public CompletableFuture<TreeNode> put(PublicSigningKey writer, ByteArrayWrapper key, Multihash value, ContentAddressedStorage storage, int maxChildren) {
        KeyElement dummy = KeyElement.dummy(key);
        SortedSet<KeyElement> tailSet = keys.tailSet(dummy);
        KeyElement nextSmallest;
        if (tailSet.size() == 0) {
            nextSmallest = keys.last();
        } else {
            nextSmallest = tailSet.first();
            if (!nextSmallest.key.equals(key)) {
                SortedSet<KeyElement> headSet = keys.headSet(dummy);
                nextSmallest = headSet.last();
            }
        }
        if (nextSmallest.key.equals(key)) {
            KeyElement modified = new KeyElement(key, MaybeMultihash.of(value), nextSmallest.targetHash);
            keys.remove(nextSmallest);
            keys.add(modified);
            // commit this node to storage
            return storage.put(writer, this.serialize())
                    .thenApply(multihash -> new TreeNode(this.keys, multihash));
        }
        if (! nextSmallest.targetHash.isPresent()) {
            if (keys.size() < maxChildren) {
                keys.add(new KeyElement(key,  MaybeMultihash.of(value), MaybeMultihash.EMPTY()));
                // commit this node to storage
                return storage.put(writer, this.serialize())
                        .thenApply(multihash -> new TreeNode(this.keys, MaybeMultihash.of(multihash)));
            }
            // split into two and make new parent
            keys.add(new KeyElement(key, MaybeMultihash.of(value), MaybeMultihash.EMPTY()));
            KeyElement[] tmp = new KeyElement[keys.size()];
            KeyElement median = keys.toArray(tmp)[keys.size()/2];
            // commit left child
            SortedSet<KeyElement> left = keys.headSet(median);
            TreeNode leftChild = new TreeNode(left);
            return storage.put(writer, leftChild.serialize()).thenCompose(leftChildHash -> {

                // commit right child
                SortedSet<KeyElement> right = keys.tailSet(median);
                right.remove(right.first());
                TreeNode rightChild = new TreeNode(median.targetHash, right);
                return storage.put(writer, rightChild.serialize()).thenApply(rightChildHash -> {

                    // now add median to parent
                    TreeSet holder = new TreeSet<>();
                    KeyElement newParent = new KeyElement(median.key, median.valueHash, MaybeMultihash.of(rightChildHash));
                    holder.add(newParent);
                    return new TreeNode(MaybeMultihash.of(leftChildHash), holder);
                });
            });
        }

        final KeyElement finalNextSmallest = nextSmallest;
        Multihash nextSmallestHash = nextSmallest.targetHash.get();
        return storage.get(nextSmallestHash)
                .thenApply(rawOpt -> TreeNode.fromCbor(rawOpt.orElseThrow(() -> new IllegalStateException("Hash not present! " + nextSmallestHash))))
                .thenCompose(modifiedChild -> modifiedChild.withHash(finalNextSmallest.targetHash).put(writer, key, value, storage, maxChildren))
                .thenCompose(modifiedChild -> {
                    if (!modifiedChild.hash.isPresent()) {
                        // we split a child and need to add the median to our keys
                        if (keys.size() < maxChildren) {
                            KeyElement replacementNextSmallest = new KeyElement(finalNextSmallest.key, finalNextSmallest.valueHash, modifiedChild.keys.first().targetHash);
                            keys.remove(finalNextSmallest);
                            keys.add(replacementNextSmallest);
                            keys.add(modifiedChild.keys.last());
                            return storage.put(writer, this.serialize())
                                    .thenApply(multihash -> new TreeNode(this.keys, MaybeMultihash.of(multihash)));
                        }
                        // we need to split as well, merge in new key and two pointers first
                        KeyElement nonZero = modifiedChild.keys.last();
                        keys.add(nonZero);
                        KeyElement updated = new KeyElement(finalNextSmallest.key, finalNextSmallest.valueHash, modifiedChild.keys.first().targetHash);
                        keys.remove(finalNextSmallest);
                        keys.add(updated);

                        // now split
                        KeyElement[] tmp = new KeyElement[keys.size()];
                        KeyElement median = keys.toArray(tmp)[keys.size() / 2];
                        // commit left child
                        SortedSet<KeyElement> left = keys.headSet(median);
                        TreeNode leftChild = new TreeNode(left);
                        return storage.put(writer, leftChild.serialize()).thenCompose(leftChildHash -> {

                            // commit right child
                            SortedSet<KeyElement> right = keys.tailSet(median);
                            right.remove(right.first());
                            TreeNode rightChild = new TreeNode(median.targetHash, right);
                            return storage.put(writer, rightChild.serialize()).thenApply(rightChildHash -> {
                                // now add median to parent
                                TreeSet holder = new TreeSet<>();
                                KeyElement newParent = new KeyElement(median.key, median.valueHash, MaybeMultihash.of(rightChildHash));
                                holder.add(newParent);
                                return new TreeNode(leftChildHash, holder);
                            });
                        });
                    }
                    // update pointer to child (child element wasn't split)
                    KeyElement updated = new KeyElement(finalNextSmallest.key, finalNextSmallest.valueHash, modifiedChild.hash.get());
                    keys.remove(finalNextSmallest);
                    keys.add(updated);
                    return storage.put(writer, this.serialize()).thenApply(multihash -> new TreeNode(this, multihash));
                });
    }

    public CompletableFuture<Integer> size(ContentAddressedStorage storage) {
        return Futures.reduceAll(keys, keys.size() - 1, (total, key) -> key.targetHash.isPresent() ?
                storage.get(key.targetHash.get())
                        .thenCompose(rawOpt -> TreeNode.fromCbor(rawOpt.orElseThrow(() -> new IllegalStateException("Hash not present! " + key.targetHash.get())))
                                .size(storage)).thenApply(subTreeTotal -> subTreeTotal + total) :
                CompletableFuture.completedFuture(total), (a, b) -> a + b);
    }

    private KeyElement smallestNonZeroKey() {
        return keys.tailSet(new KeyElement(new ByteArrayWrapper(new byte[]{0}), MaybeMultihash.EMPTY(), MaybeMultihash.EMPTY())).first();
    }

    public CompletableFuture<ByteArrayWrapper> smallestKey(ContentAddressedStorage storage) {
        MaybeMultihash targetHash = keys.first().targetHash;
        if (! targetHash.isPresent())
            return CompletableFuture.completedFuture(keys.toArray(new KeyElement[keys.size()])[1].key);
        return storage.get(targetHash.get())
                .thenCompose(rawOpt -> TreeNode.fromCbor(rawOpt.orElseThrow(() -> new IllegalStateException("Hash not present! " + targetHash.get())))
                        .smallestKey(storage));
    }

    public CompletableFuture<TreeNode> delete(PublicSigningKey writer, ByteArrayWrapper key, ContentAddressedStorage storage, int maxChildren) {
        KeyElement dummy = KeyElement.dummy(key);
        SortedSet<KeyElement> tailSet = keys.tailSet(dummy);
        KeyElement nextSmallest;
        if (tailSet.size() == 0) {
            nextSmallest = keys.last();
        } else {
            nextSmallest = tailSet.first();
            if (!nextSmallest.key.equals(key))
                nextSmallest = keys.headSet(dummy).last();
        }
        if (nextSmallest.key.equals(key)) {
            if (! nextSmallest.targetHash.isPresent()) {
                // we are a leaf
                keys.remove(nextSmallest);
                if (keys.size() >= maxChildren/2) {
                    return storage.put(writer, this.serialize())
                            .thenApply(multihash -> new TreeNode(this.keys, multihash));
                }
                return CompletableFuture.completedFuture(new TreeNode(this.keys));
            } else {
                Multihash multihash = nextSmallest.targetHash.get();
                final KeyElement finalNextSmallest = nextSmallest;
                return storage.get(multihash)
                        .thenApply(rawOpt -> TreeNode.fromCbor(rawOpt.orElseThrow(() -> new IllegalStateException("Hash not present! " + multihash)))
                                .withHash(finalNextSmallest.targetHash))
                        .thenCompose(child -> {
                            // take the subtree's smallest value (in a leaf) delete it and promote it to the separator here
                            return child.smallestKey(storage).thenCompose(smallestKey -> child.get(smallestKey, storage)
                                    .thenCompose(value -> child.delete(writer, smallestKey, storage, maxChildren)
                                                    .thenCompose(newChild -> storage.put(writer, newChild.serialize()).thenCompose(childHash -> {
                                                                keys.remove(finalNextSmallest);
                                                                KeyElement replacement = new KeyElement(smallestKey, value, childHash);
                                                                keys.add(replacement);
                                                                if (newChild.keys.size() >= maxChildren / 2) {
                                                                    return storage.put(writer, this.serialize())
                                                                            .thenApply(multihash1 -> new TreeNode(this, multihash));
                                                                } else {
                                                                    // re-balance
                                                                    return rebalance(writer, this, newChild, childHash, storage, maxChildren);
                                                                }
                                                            })
                                                    )
                                    )
                            );
                        });
            }
        }
        if (! nextSmallest.targetHash.isPresent())
            return CompletableFuture.completedFuture(new TreeNode(this.keys));
        final KeyElement finalNextSmallest = nextSmallest;
        final Multihash nextSmallestHash = nextSmallest.targetHash.get();
        return storage.get(nextSmallestHash)
                .thenCompose(rawOpt -> TreeNode.fromCbor(rawOpt.orElseThrow(() -> new IllegalStateException("Hash not present! " + nextSmallestHash)))
                        .withHash(finalNextSmallest.targetHash).delete(writer, key, storage, maxChildren))
                .thenCompose(child -> {
                    // update pointer
                    if (child.hash.isPresent()) {
                        keys.remove(finalNextSmallest);
                        keys.add(new KeyElement(finalNextSmallest.key, finalNextSmallest.valueHash, child.hash.get()));
                    }
                    if (child.keys.size() < maxChildren / 2) {
                        // re-balance
                        return rebalance(writer, this, child, nextSmallestHash, storage, maxChildren);
                    }
                    return storage.put(writer, this.serialize())
                            .thenApply(multihash -> new TreeNode(this, multihash));
                });
    }

    private static CompletableFuture<TreeNode> rebalance(PublicSigningKey writer, TreeNode parent, TreeNode child, Multihash originalChildHash,
                                                         ContentAddressedStorage storage, int maxChildren) {
        // child has too few children
        Multihash childHash = originalChildHash;
        KeyElement[] parentKeys = parent.keys.toArray(new KeyElement[parent.keys.size()]);
        int i = 0;
        while (i < parentKeys.length && !parentKeys[i].targetHash.get().equals(childHash))
            i++;

        KeyElement centerKey = parentKeys[i];
        Optional<KeyElement> leftKey = i > 0 ? Optional.of(parentKeys[i-1]) : Optional.empty();
        Optional<KeyElement> rightKey = i + 1 < parentKeys.length ? Optional.of(parentKeys[i+1]) : Optional.empty();

        Function<Optional<KeyElement>, CompletableFuture<Optional<TreeNode>>> keyToNode = key -> {
            if (! key.isPresent())
                return CompletableFuture.completedFuture(Optional.empty());
            return storage.get(key.get().targetHash.get())
                    .thenApply(rawOpt -> rawOpt.map(raw -> TreeNode.fromCbor(raw)));
        };

        CompletableFuture<Optional<TreeNode>> leftSiblingFut = keyToNode.apply(leftKey);
        CompletableFuture<Optional<TreeNode>> rightSiblingFut = keyToNode.apply(rightKey);

        return leftSiblingFut.thenCompose(leftSibling -> rightSiblingFut.thenCompose(rightSibling -> {
            if (rightSibling.isPresent() && rightSibling.get().keys.size() > maxChildren / 2) {
                // rotate left
                final TreeNode right = rightSibling.get();
                KeyElement newSeparator = right.smallestNonZeroKey();
                parent.keys.remove(centerKey);

                child.keys.add(new KeyElement(rightKey.get().key, rightKey.get().valueHash, right.keys.first().targetHash));
                return storage.put(writer, child.serialize()).thenCompose(newChildHash -> {
                    right.keys.remove(newSeparator);
                    right.keys.remove(KeyElement.dummy(new ByteArrayWrapper(new byte[0])));
                    TreeNode newRight = new TreeNode(newSeparator.targetHash, right.keys);
                    return storage.put(writer, newRight.serialize()).thenCompose(newRightHash -> {
                        parent.keys.remove(rightKey.get());
                        parent.keys.add(new KeyElement(centerKey.key, centerKey.valueHash, newChildHash));
                        parent.keys.add(new KeyElement(newSeparator.key, newSeparator.valueHash, newRightHash));
                        return storage.put(writer, parent.serialize())
                                .thenApply(multihash -> new TreeNode(parent, multihash));
                    });
                });
            } else if (leftSibling.isPresent() && leftSibling.get().keys.size() > maxChildren / 2) {
                // rotate right
                TreeNode left = leftSibling.get();
                KeyElement newSeparator = left.keys.last();
                parent.keys.remove(centerKey);

                left.keys.remove(newSeparator);
                return storage.put(writer, left.serialize()).thenCompose(newLeftHash -> {
                    child.keys.add(new KeyElement(centerKey.key, centerKey.valueHash, child.keys.first().targetHash));
                    child.keys.remove(KeyElement.dummy(new ByteArrayWrapper(new byte[0])));
                    child.keys.add(new KeyElement(new ByteArrayWrapper(new byte[0]), MaybeMultihash.EMPTY(), newSeparator.targetHash));
                    return storage.put(writer, child.serialize()).thenCompose(newChildHash -> {
                        parent.keys.remove(leftKey.get());
                        parent.keys.add(new KeyElement(leftKey.get().key, leftKey.get().valueHash, newLeftHash));
                        parent.keys.add(new KeyElement(newSeparator.key, newSeparator.valueHash, newChildHash));
                        return storage.put(writer, parent.serialize())
                                .thenApply(multihash -> new TreeNode(parent, multihash));
                    });
                });
            } else {
                if (rightSibling.isPresent()) {
                    // merge with right sibling and separator
                    SortedSet<KeyElement> combinedKeys = new TreeSet<>();
                    combinedKeys.addAll(child.keys);
                    combinedKeys.addAll(rightSibling.get().keys);
                    combinedKeys.add(new KeyElement(rightKey.get().key, rightKey.get().valueHash, rightSibling.get().keys.first().targetHash));
                    TreeNode combined = new TreeNode(combinedKeys);
                    return storage.put(writer, combined.serialize()).thenCompose(combinedHash -> {
                        parent.keys.remove(rightKey.get());
                        parent.keys.remove(centerKey);
                        parent.keys.add(new KeyElement(centerKey.key, centerKey.valueHash, combinedHash));
                        if (parent.keys.size() >= maxChildren / 2) {
                            return storage.put(writer, parent.serialize())
                                    .thenApply(multihash -> new TreeNode(parent, multihash));
                        }
                        return CompletableFuture.completedFuture(new TreeNode(parent.keys));
                    });
                } else {
                    // merge with left sibling and separator
                    SortedSet<KeyElement> combinedKeys = new TreeSet<>();
                    combinedKeys.addAll(child.keys);
                    combinedKeys.addAll(leftSibling.get().keys);
                    combinedKeys.add(new KeyElement(centerKey.key, centerKey.valueHash, child.keys.first().targetHash));
                    TreeNode combined = new TreeNode(combinedKeys);
                    return storage.put(writer, combined.serialize()).thenCompose(combinedHash -> {
                        parent.keys.remove(leftKey.get());
                        parent.keys.remove(centerKey);
                        parent.keys.add(new KeyElement(leftKey.get().key, leftKey.get().valueHash, combinedHash));
                        if (parent.keys.size() >= maxChildren / 2) {
                            return storage.put(writer, parent.serialize())
                                    .thenApply(multihash -> new TreeNode(parent, multihash));
                        }
                        return CompletableFuture.completedFuture(new TreeNode(parent.keys));
                    });
                }
            }
        }));
    }

    /**
     *  Print a representation of this btree to the print stream. This method is synchronous.
     * @param w
     * @param depth
     * @param storage
     * @throws Exception
     */
    public void print(PrintStream w, int depth, ContentAddressedStorage storage) throws Exception {
        int index = 0;
        for (KeyElement e: keys) {
            String tab = "";
            for (int i=0; i < depth; i++)
                tab += "   ";
            w.print(StringUtils.format(tab + "[%d/%d] %s : %s\n", index++, keys.size(), e.key.toString(), new ByteArrayWrapper(e.valueHash.toBytes()).toString()));
            if (e.targetHash.isPresent())
                TreeNode.fromCbor(storage.get(e.targetHash.get()).get().get()).print(w, depth + 1, storage);
        }
    }

    public CborObject toCbor() {
        return new CborObject.CborList(keys.stream()
                .map(Cborable::toCbor)
                .collect(Collectors.toList())
        );
    }

    public static TreeNode fromCbor(CborObject cbor) {
        if (! (cbor instanceof CborObject.CborList))
            throw new IllegalStateException("Incorrect serialization for tree node! " + cbor);
        TreeSet<KeyElement> sortedKeys = new TreeSet<>(((CborObject.CborList) cbor).value
                .stream()
                .map(KeyElement::fromCbor)
                .collect(Collectors.toSet()));
        return new TreeNode(sortedKeys);
    }

    private static class KeyElement implements Cborable, Comparable<KeyElement> {
        public final ByteArrayWrapper key;
        public final MaybeMultihash valueHash, targetHash;

        public KeyElement(ByteArrayWrapper key, MaybeMultihash valueHash, MaybeMultihash targetHash) {
            this.key = key;
            this.valueHash = valueHash;
            this.targetHash = targetHash;
        }

        public KeyElement(ByteArrayWrapper key, Multihash valueHash, MaybeMultihash targetHash) {
            this(key, MaybeMultihash.of(valueHash), targetHash);
        }
        public KeyElement(ByteArrayWrapper key, MaybeMultihash valueHash, Multihash targetHash) {
            this(key, valueHash, MaybeMultihash.of(targetHash));
        }
        public KeyElement(ByteArrayWrapper key, Multihash valueHash, Multihash targetHash) {
            this(key, MaybeMultihash.of(valueHash), MaybeMultihash.of(targetHash));
        }

        @Override
        public int compareTo(KeyElement that) {
            return key.compareTo(that.key);
        }

        @Override
        public String toString() {
            return key.toString() + " -> " + valueHash.toString() +" : "+targetHash.toString();
        }

        @Override
        public CborObject toCbor() {
            Map<String, CborObject> cbor = new TreeMap<>();
            cbor.put("k", new CborObject.CborByteArray(key.data));
            if (valueHash.isPresent())
                cbor.put("v", new CborObject.CborMerkleLink(valueHash.get()));
            if (targetHash.isPresent())
                cbor.put("t", new CborObject.CborMerkleLink(targetHash.get()));

            return CborObject.CborMap.build(cbor);
        }

        public static KeyElement fromCbor(CborObject cbor) {
            if (! (cbor instanceof CborObject.CborMap))
                throw new IllegalStateException("Incorrect cbor for TreeNode$KeyElement: " + cbor);

            SortedMap<CborObject, CborObject> values = ((CborObject.CborMap) cbor).values;

            ByteArrayWrapper key = new ByteArrayWrapper(getOrDefault(values, "k", c -> ((CborObject.CborByteArray)c).value, () -> new byte[0]));
            MaybeMultihash value = getOrDefault(values, "v",
                    c -> MaybeMultihash.of(((CborObject.CborMerkleLink)c).target),
                    MaybeMultihash::EMPTY);
            MaybeMultihash target = getOrDefault(values, "t",
                    c -> MaybeMultihash.of(((CborObject.CborMerkleLink)c).target),
                    MaybeMultihash::EMPTY);
            return new KeyElement(key, value, target);
        }

        private static <T> T getOrDefault(SortedMap<CborObject, CborObject> values, String skey, Function<CborObject, T> converter, Supplier<T> def) {
            CborObject.CborString key = new CborObject.CborString(skey);
            if (! values.containsKey(key))
                return def.get();
            return converter.apply(values.get(key));
        }

        static KeyElement dummy(ByteArrayWrapper key) {
            return new KeyElement(key, MaybeMultihash.EMPTY(), MaybeMultihash.EMPTY());
        }
    }
}
