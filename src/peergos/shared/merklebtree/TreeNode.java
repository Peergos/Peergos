package peergos.shared.merklebtree;

import peergos.shared.crypto.*;
import peergos.shared.ipfs.api.Multihash;
import peergos.shared.storage.ContentAddressedStorage;
import peergos.shared.util.*;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.stream.*;

public class TreeNode {
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

        return storage.getData(nextSmallest.targetHash.get())
                .thenCompose(rawOpt -> TreeNode.deserialize(rawOpt.orElseThrow(() -> new IllegalStateException("Hash not present!")))
                        .get(key, storage));
    }

    public CompletableFuture<TreeNode> put(UserPublicKey writer, ByteArrayWrapper key, Multihash value, ContentAddressedStorage storage, int maxChildren) {
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
            return storage.put(writer, this.toMerkleNode())
                    .thenApply(multihash -> new TreeNode(this.keys, multihash));
        }
        if (! nextSmallest.targetHash.isPresent()) {
            if (keys.size() < maxChildren) {
                keys.add(new KeyElement(key,  MaybeMultihash.of(value), MaybeMultihash.EMPTY()));
                // commit this node to storage
                return storage.put(writer, this.toMerkleNode())
                        .thenApply(multihash -> new TreeNode(this.keys, MaybeMultihash.of(multihash)));
            }
            // split into two and make new parent
            keys.add(new KeyElement(key, MaybeMultihash.of(value), MaybeMultihash.EMPTY()));
            KeyElement[] tmp = new KeyElement[keys.size()];
            KeyElement median = keys.toArray(tmp)[keys.size()/2];
            // commit left child
            SortedSet<KeyElement> left = keys.headSet(median);
            TreeNode leftChild = new TreeNode(left);
            return storage.put(writer, leftChild.toMerkleNode()).thenCompose(leftChildHash -> {

                // commit right child
                SortedSet<KeyElement> right = keys.tailSet(median);
                right.remove(right.first());
                TreeNode rightChild = new TreeNode(median.targetHash, right);
                return storage.put(writer, rightChild.toMerkleNode()).thenApply(rightChildHash -> {

                    // now add median to parent
                    TreeSet holder = new TreeSet<>();
                    KeyElement newParent = new KeyElement(median.key, median.valueHash, MaybeMultihash.of(rightChildHash));
                    holder.add(newParent);
                    return new TreeNode(MaybeMultihash.of(leftChildHash), holder);
                });
            });
        }

        final KeyElement finalNextSmallest = nextSmallest;
        return storage.getData(nextSmallest.targetHash.get())
                .thenApply(rawOpt -> TreeNode.deserialize(rawOpt.orElseThrow(() -> new IllegalStateException("Hash not present!"))))
                .thenCompose(modifiedChild -> modifiedChild.withHash(finalNextSmallest.targetHash).put(writer, key, value, storage, maxChildren))
                .thenCompose(modifiedChild -> {
                    if (!modifiedChild.hash.isPresent()) {
                        // we split a child and need to add the median to our keys
                        if (keys.size() < maxChildren) {
                            KeyElement replacementNextSmallest = new KeyElement(finalNextSmallest.key, finalNextSmallest.valueHash, modifiedChild.keys.first().targetHash);
                            keys.remove(finalNextSmallest);
                            keys.add(replacementNextSmallest);
                            keys.add(modifiedChild.keys.last());
                            return storage.put(writer, this.toMerkleNode())
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
                        return storage.put(writer, leftChild.toMerkleNode()).thenCompose(leftChildHash -> {

                            // commit right child
                            SortedSet<KeyElement> right = keys.tailSet(median);
                            right.remove(right.first());
                            TreeNode rightChild = new TreeNode(median.targetHash, right);
                            return storage.put(writer, rightChild.toMerkleNode()).thenApply(rightChildHash -> {
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
                    return storage.put(writer, this.toMerkleNode()).thenApply(multihash -> new TreeNode(this, multihash));
                });
    }

    public CompletableFuture<Integer> size(ContentAddressedStorage storage) {
        return Futures.reduceAll(keys, keys.size() - 1, (total, key) -> key.targetHash.isPresent() ?
                storage.getData(key.targetHash.get())
                        .thenCompose(rawOpt -> TreeNode.deserialize(rawOpt.orElseThrow(() -> new IllegalStateException("Hash not present!")))
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
        return storage.getData(targetHash.get())
                .thenCompose(rawOpt -> TreeNode.deserialize(rawOpt.orElseThrow(() -> new IllegalStateException("Hash not present!")))
                        .smallestKey(storage));
    }

    public CompletableFuture<TreeNode> delete(UserPublicKey writer, ByteArrayWrapper key, ContentAddressedStorage storage, int maxChildren) {
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
                    return storage.put(writer, this.toMerkleNode())
                            .thenApply(multihash -> new TreeNode(this.keys, multihash));
                }
                return CompletableFuture.completedFuture(new TreeNode(this.keys));
            } else {
                Multihash multihash = nextSmallest.targetHash.get();
                final KeyElement finalNextSmallest = nextSmallest;
                return storage.getData(multihash)
                        .thenApply(rawOpt -> TreeNode.deserialize(rawOpt.orElseThrow(() -> new IllegalStateException("Hash not present!")))
                                .withHash(finalNextSmallest.targetHash))
                        .thenCompose(child -> {
                            // take the subtree's smallest value (in a leaf) delete it and promote it to the separator here
                            return child.smallestKey(storage).thenCompose(smallestKey -> child.get(smallestKey, storage)
                                    .thenCompose(value -> child.delete(writer, smallestKey, storage, maxChildren)
                                                    .thenCompose(newChild -> storage.put(writer, newChild.toMerkleNode()).thenCompose(childHash -> {
                                                                keys.remove(finalNextSmallest);
                                                                KeyElement replacement = new KeyElement(smallestKey, value, childHash);
                                                                keys.add(replacement);
                                                                if (newChild.keys.size() >= maxChildren / 2) {
                                                                    return storage.put(writer, this.toMerkleNode())
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
        return storage.getData(nextSmallest.targetHash.get())
                .thenCompose(rawOpt -> TreeNode.deserialize(rawOpt.orElseThrow(() -> new IllegalStateException("Hash not present!")))
                        .withHash(finalNextSmallest.targetHash).delete(writer, key, storage, maxChildren))
                .thenCompose(child -> {
                    // update pointer
                    if (child.hash.isPresent()) {
                        keys.remove(finalNextSmallest);
                        keys.add(new KeyElement(finalNextSmallest.key, finalNextSmallest.valueHash, child.hash.get()));
                    }
                    if (child.keys.size() < maxChildren / 2) {
                        // re-balance
                        return rebalance(writer, this, child, finalNextSmallest.targetHash.get(), storage, maxChildren);
                    }
                    return storage.put(writer, this.toMerkleNode())
                            .thenApply(multihash -> new TreeNode(this, multihash));
                });
    }

    private static CompletableFuture<TreeNode> rebalance(UserPublicKey writer, TreeNode parent, TreeNode child, Multihash originalChildHash,
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
            return storage.getData(key.get().targetHash.get())
                    .thenApply(rawOpt -> rawOpt.map(raw -> TreeNode.deserialize(raw)));
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
                return storage.put(writer, child.toMerkleNode()).thenCompose(newChildHash -> {
                    right.keys.remove(newSeparator);
                    right.keys.remove(KeyElement.dummy(new ByteArrayWrapper(new byte[0])));
                    TreeNode newRight = new TreeNode(newSeparator.targetHash, right.keys);
                    return storage.put(writer, newRight.toMerkleNode()).thenCompose(newRightHash -> {
                        parent.keys.remove(rightKey.get());
                        parent.keys.add(new KeyElement(centerKey.key, centerKey.valueHash, newChildHash));
                        parent.keys.add(new KeyElement(newSeparator.key, newSeparator.valueHash, newRightHash));
                        return storage.put(writer, parent.toMerkleNode())
                                .thenApply(multihash -> new TreeNode(parent, multihash));
                    });
                });
            } else if (leftSibling.isPresent() && leftSibling.get().keys.size() > maxChildren / 2) {
                // rotate right
                TreeNode left = leftSibling.get();
                KeyElement newSeparator = left.keys.last();
                parent.keys.remove(centerKey);

                left.keys.remove(newSeparator);
                return storage.put(writer, left.toMerkleNode()).thenCompose(newLeftHash -> {
                    child.keys.add(new KeyElement(centerKey.key, centerKey.valueHash, child.keys.first().targetHash));
                    child.keys.remove(KeyElement.dummy(new ByteArrayWrapper(new byte[0])));
                    child.keys.add(new KeyElement(new ByteArrayWrapper(new byte[0]), MaybeMultihash.EMPTY(), newSeparator.targetHash));
                    return storage.put(writer, child.toMerkleNode()).thenCompose(newChildHash -> {
                        parent.keys.remove(leftKey.get());
                        parent.keys.add(new KeyElement(leftKey.get().key, leftKey.get().valueHash, newLeftHash));
                        parent.keys.add(new KeyElement(newSeparator.key, newSeparator.valueHash, newChildHash));
                        return storage.put(writer, parent.toMerkleNode())
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
                    return storage.put(writer, combined.toMerkleNode()).thenCompose(combinedHash -> {
                        parent.keys.remove(rightKey.get());
                        parent.keys.remove(centerKey);
                        parent.keys.add(new KeyElement(centerKey.key, centerKey.valueHash, combinedHash));
                        if (parent.keys.size() >= maxChildren / 2) {
                            return storage.put(writer, parent.toMerkleNode())
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
                    return storage.put(writer, combined.toMerkleNode()).thenCompose(combinedHash -> {
                        parent.keys.remove(leftKey.get());
                        parent.keys.remove(centerKey);
                        parent.keys.add(new KeyElement(leftKey.get().key, leftKey.get().valueHash, combinedHash));
                        if (parent.keys.size() >= maxChildren / 2) {
                            return storage.put(writer, parent.toMerkleNode())
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
                TreeNode.deserialize(storage.getData(e.targetHash.get()).get().get()).print(w, depth + 1, storage);
        }
    }

    public byte[] serialize() {
        try {
            ByteArrayOutputStream bout = new ByteArrayOutputStream();
            DataOutputStream dout = new DataOutputStream(bout);
            dout.writeInt(keys.size());
            for (KeyElement e : keys) {
                dout.writeInt(e.key.data.length);
                dout.write(e.key.data);
                e.valueHash.serialize(dout);
                e.targetHash.serialize(dout);
            }
            return bout.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public MerkleNode toMerkleNode() {
        List<MerkleNode.Link> links = Stream.concat(
                keys.stream()
                        .filter(k -> k.targetHash.isPresent())
                        .map(k -> k.targetHash.get()),
                keys.stream()
                        .filter(k -> k.valueHash.isPresent())
                        .map(k -> k.valueHash.get()))
                .map(h -> new MerkleNode.Link(h.toBase58(), h))
                .collect(Collectors.toList());
        return new MerkleNode(serialize(), links);
    }

    public static TreeNode fromMerkleNode(MerkleNode node) throws IOException {
        return deserialize(node.data);
    }

    public static TreeNode deserialize(byte[] raw) {
        if (raw == null)
            throw new IllegalArgumentException("Null byte[]!");
        DataInputStream din = new DataInputStream(new ByteArrayInputStream(raw));
        try {
            int n = din.readInt();
            SortedSet<KeyElement> keys = new TreeSet<>();
            for (int i = 0; i < n; i++) {
                byte[] key = new byte[din.readInt()];
                din.readFully(key);
                MaybeMultihash valueHash = MaybeMultihash.deserialize(din);
                MaybeMultihash targetHash = MaybeMultihash.deserialize(din);
                keys.add(new KeyElement(new ByteArrayWrapper(key), valueHash, targetHash));
            }
            return new TreeNode(keys);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static class KeyElement implements Comparable<KeyElement> {
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

        static KeyElement dummy(ByteArrayWrapper key) {
            return new KeyElement(key, MaybeMultihash.EMPTY(), MaybeMultihash.EMPTY());
        }
    }
}
