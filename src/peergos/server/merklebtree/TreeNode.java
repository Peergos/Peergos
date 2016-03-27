package peergos.server.merklebtree;

import org.ipfs.api.Multihash;
import org.ipfs.api.NamedStreamable;
import peergos.server.storage.ContentAddressedStorage;
import peergos.util.*;

import java.io.*;
import java.util.*;
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


    public MaybeMultihash get(ByteArrayWrapper key, ContentAddressedStorage storage) throws IOException {
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
            return nextSmallest.valueHash;
        if (! nextSmallest.targetHash.isPresent())
            return MaybeMultihash.EMPTY();

        byte[] raw = storage.get(nextSmallest.targetHash.get());
        return TreeNode.deserialize(raw).get(key, storage);
    }

    public TreeNode put(ByteArrayWrapper key, Multihash value, ContentAddressedStorage storage, int maxChildren) throws IOException {
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
            Multihash multiHash = storage.put(this.toMerkleNode());
            if (! multiHash.equals(this.hash.get()))
                storage.remove(this.hash.get());
            return new TreeNode(this.keys, multiHash);
        }
        if (! nextSmallest.targetHash.isPresent()) {
            if (keys.size() < maxChildren) {
                keys.add(new KeyElement(key,  MaybeMultihash.of(value), MaybeMultihash.EMPTY()));
                // commit this node to storage
                Multihash hash = storage.put(this.toMerkleNode());
                if (this.hash.isPresent())
                    storage.remove(this.hash.get());
                return new TreeNode(this.keys, MaybeMultihash.of(hash));
            }
            // split into two and make new parent
            keys.add(new KeyElement(key, MaybeMultihash.of(value), MaybeMultihash.EMPTY()));
            KeyElement[] tmp = new KeyElement[keys.size()];
            KeyElement median = keys.toArray(tmp)[keys.size()/2];
            // commit left child
            SortedSet<KeyElement> left = keys.headSet(median);
            TreeNode leftChild = new TreeNode(left);
            Multihash leftChildHash = storage.put(leftChild.toMerkleNode());

            // commit right child
            SortedSet<KeyElement> right = keys.tailSet(median);
            right.remove(right.first());
            TreeNode rightChild = new TreeNode(median.targetHash, right);
            Multihash rightChildHash = storage.put(rightChild.toMerkleNode());

            // now add median to parent
            TreeSet holder = new TreeSet<>();
            KeyElement newParent = new KeyElement(median.key, median.valueHash, MaybeMultihash.of(rightChildHash));
            holder.add(newParent);
            storage.remove(this.hash.get());
            return new TreeNode(MaybeMultihash.of(leftChildHash), holder);
        }

        TreeNode modifiedChild = TreeNode.deserialize(storage.get(nextSmallest.targetHash.get())).withHash(nextSmallest.targetHash).put(key, value, storage, maxChildren);
        if (!modifiedChild.hash.isPresent() || ! modifiedChild.hash.equals(nextSmallest.targetHash))
            storage.remove(nextSmallest.targetHash.get());
        if (!modifiedChild.hash.isPresent()) {
            // we split a child and need to add the median to our keys
            if (keys.size() < maxChildren) {
                KeyElement replacementNextSmallest = new KeyElement(nextSmallest.key, nextSmallest.valueHash, modifiedChild.keys.first().targetHash);
                keys.remove(nextSmallest);
                keys.add(replacementNextSmallest);
                keys.add(modifiedChild.keys.last());
                Multihash hash = storage.put(this.toMerkleNode());
                storage.remove(this.hash.get());
                return new TreeNode(this.keys, MaybeMultihash.of(hash));
            }
            // we need to split as well, merge in new key and two pointers first
            KeyElement nonZero = modifiedChild.keys.last();
            keys.add(nonZero);
            KeyElement updated = new KeyElement(nextSmallest.key, nextSmallest.valueHash, modifiedChild.keys.first().targetHash);
            keys.remove(nextSmallest);
            keys.add(updated);

            // now split
            KeyElement[] tmp = new KeyElement[keys.size()];
            KeyElement median = keys.toArray(tmp)[keys.size()/2];
            // commit left child
            SortedSet<KeyElement> left = keys.headSet(median);
            TreeNode leftChild = new TreeNode(left);
            Multihash leftChildHash = storage.put(leftChild.toMerkleNode());

            // commit right child
            SortedSet<KeyElement> right = keys.tailSet(median);
            right.remove(right.first());
            TreeNode rightChild = new TreeNode(median.targetHash, right);
            Multihash rightChildHash = storage.put(rightChild.toMerkleNode());

            // now add median to parent
            TreeSet holder = new TreeSet<>();
            KeyElement newParent = new KeyElement(median.key, median.valueHash, MaybeMultihash.of(rightChildHash));
            holder.add(newParent);
            storage.remove(this.hash.get());
            return new TreeNode(leftChildHash, holder);
        }
        // update pointer to child (child element wasn't split)
        KeyElement updated = new KeyElement(nextSmallest.key, nextSmallest.valueHash, modifiedChild.hash.get());
        keys.remove(nextSmallest);
        keys.add(updated);
        Multihash hash = storage.put(this.toMerkleNode());
        if (! hash.equals(this.hash.get()))
            storage.remove(this.hash.get());
        return new TreeNode(this, hash);
    }

    public int size(ContentAddressedStorage storage) throws IOException {
        int total = 0;
        for (KeyElement e : keys)
            if (e.targetHash.isPresent())
                total += TreeNode.deserialize(storage.get(e.targetHash.get())).size(storage);
        total += keys.size() - 1;
        return total;
    }

    private KeyElement smallestNonZeroKey() {
        return keys.tailSet(new KeyElement(new ByteArrayWrapper(new byte[]{0}), MaybeMultihash.EMPTY(), MaybeMultihash.EMPTY())).first();
    }

    public ByteArrayWrapper smallestKey(ContentAddressedStorage storage) throws IOException {
        MaybeMultihash targetHash = keys.first().targetHash;
        if (! targetHash.isPresent())
            return keys.toArray(new KeyElement[keys.size()])[1].key;
        return TreeNode.deserialize(storage.get(targetHash.get())).smallestKey(storage);
    }

    public TreeNode delete(ByteArrayWrapper key, ContentAddressedStorage storage, int maxChildren) throws IOException {
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
                storage.remove(this.hash.get());
                if (keys.size() >= maxChildren/2) {
                    Multihash hash = storage.put(this.toMerkleNode());
                    return new TreeNode(this.keys, hash);
                }
                return new TreeNode(this.keys);
            } else {
                Multihash multihash = nextSmallest.targetHash.get();
                TreeNode child = TreeNode.deserialize(storage.get(multihash)).withHash(nextSmallest.targetHash);
                // take the subtree's smallest value (in a leaf) delete it and promote it to the separator here
                ByteArrayWrapper smallestKey = child.smallestKey(storage);
                MaybeMultihash value = child.get(smallestKey, storage);
                TreeNode newChild = child.delete(smallestKey, storage, maxChildren);

                Multihash childHash = storage.put(newChild.toMerkleNode());
                keys.remove(nextSmallest);
                storage.remove(multihash);
                KeyElement replacement = new KeyElement(smallestKey, value, childHash);
                keys.add(replacement);
                storage.remove(this.hash.get());
                if (newChild.keys.size() >= maxChildren/2) {
                    Multihash hash = storage.put(this.toMerkleNode());
                    return new TreeNode(this, hash);
                } else {
                    // re-balance
                    return rebalance(this, newChild, childHash, storage, maxChildren);
                }
            }
        }
        if (! nextSmallest.targetHash.isPresent())
            return new TreeNode(this.keys);
        TreeNode child = TreeNode.deserialize(storage.get(nextSmallest.targetHash.get())).withHash(nextSmallest.targetHash).delete(key, storage, maxChildren);
        if (!child.hash.isPresent() || !  child.hash.equals(nextSmallest.targetHash))
            storage.remove(nextSmallest.targetHash.get());
        // update pointer
        if (child.hash.isPresent()) {
            keys.remove(nextSmallest);
            keys.add(new KeyElement(nextSmallest.key, nextSmallest.valueHash, child.hash.get()));
        }
        if (child.keys.size() < maxChildren / 2) {
            // re-balance
            return rebalance(this, child, nextSmallest.targetHash.get(), storage, maxChildren);
        }
        Multihash hash = storage.put(this.toMerkleNode());
        storage.remove(this.hash.get());
        return new TreeNode(this, hash);
    }

    private static TreeNode rebalance(TreeNode parent, TreeNode child, Multihash originalChildHash, ContentAddressedStorage storage, int maxChildren) throws IOException {
        // child has too few children
        Multihash childHash = originalChildHash;
        KeyElement[] parentKeys = parent.keys.toArray(new KeyElement[parent.keys.size()]);
        int i = 0;
        while (i < parentKeys.length && !parentKeys[i].targetHash.get().equals(childHash))
            i++;

        KeyElement centerKey = parentKeys[i];
        Optional<KeyElement> leftKey = i > 0 ? Optional.of(parentKeys[i-1]) : Optional.empty();
        Optional<KeyElement> rightKey = i + 1 < parentKeys.length ? Optional.of(parentKeys[i+1]) : Optional.empty();
        Optional<TreeNode> leftSibling = leftKey.isPresent() ? Optional.of(TreeNode.deserialize(storage.get(leftKey.get().targetHash.get()))) : Optional.empty();
        Optional<TreeNode> rightSibling = rightKey.isPresent() ? Optional.of(TreeNode.deserialize(storage.get(rightKey.get().targetHash.get()))) : Optional.empty();
        if (rightSibling.isPresent() && rightSibling.get().keys.size() > maxChildren/2) {
            // rotate left
            TreeNode right = rightSibling.get();
            KeyElement newSeparator = right.smallestNonZeroKey();
            parent.keys.remove(centerKey);

            child.keys.add(new KeyElement(rightKey.get().key, rightKey.get().valueHash, right.keys.first().targetHash));
            Multihash newChildHash = storage.put(child.toMerkleNode());

            right.keys.remove(newSeparator);
            right.keys.remove(KeyElement.dummy(new ByteArrayWrapper(new byte[0])));
            right = new TreeNode(newSeparator.targetHash, right.keys);
            Multihash newRightHash = storage.put(right.toMerkleNode());

            parent.keys.remove(rightKey.get());
            parent.keys.add(new KeyElement(centerKey.key, centerKey.valueHash, newChildHash));
            parent.keys.add(new KeyElement(newSeparator.key, newSeparator.valueHash, newRightHash));
            Multihash hash = storage.put(parent.toMerkleNode());
            if (child.hash.isPresent())
                storage.remove(child.hash.get());
            storage.remove(parent.hash.get());
            return new TreeNode(parent, hash);
        } else if (leftSibling.isPresent() && leftSibling.get().keys.size() > maxChildren/2) {
            // rotate right
            TreeNode left = leftSibling.get();
            KeyElement newSeparator = left.keys.last();
            parent.keys.remove(centerKey);

            left.keys.remove(newSeparator);
            Multihash newLeftHash = storage.put(left.toMerkleNode());

            child.keys.add(new KeyElement(centerKey.key, centerKey.valueHash, child.keys.first().targetHash));
            child.keys.remove(KeyElement.dummy(new ByteArrayWrapper(new byte[0])));
            child.keys.add(new KeyElement(new ByteArrayWrapper(new byte[0]), MaybeMultihash.EMPTY(), newSeparator.targetHash));
            Multihash newChildHash = storage.put(child.toMerkleNode());

            parent.keys.remove(leftKey.get());
            parent.keys.add(new KeyElement(leftKey.get().key, leftKey.get().valueHash, newLeftHash));
            parent.keys.add(new KeyElement(newSeparator.key, newSeparator.valueHash, newChildHash));
            Multihash hash = storage.put(parent.toMerkleNode());
            if (child.hash.isPresent())
                storage.remove(child.hash.get());
            storage.remove(parent.hash.get());
            return new TreeNode(parent, hash);
        } else {
            if (rightSibling.isPresent()) {
                // merge with right sibling and separator
                SortedSet<KeyElement> combinedKeys = new TreeSet<>();
                combinedKeys.addAll(child.keys);
                combinedKeys.addAll(rightSibling.get().keys);
                combinedKeys.add(new KeyElement(rightKey.get().key, rightKey.get().valueHash, rightSibling.get().keys.first().targetHash));
                TreeNode combined = new TreeNode(combinedKeys);
                Multihash combinedHash = storage.put(combined.toMerkleNode());

                parent.keys.remove(rightKey.get());
                parent.keys.remove(centerKey);
                parent.keys.add(new KeyElement(centerKey.key, centerKey.valueHash, combinedHash));
                if (child.hash.isPresent())
                    storage.remove(child.hash.get());
                storage.remove(parent.hash.get());
                if (parent.keys.size() >= maxChildren/2) {
                    Multihash hash = storage.put(parent.toMerkleNode());
                    return new TreeNode(parent, hash);
                }
                return new TreeNode(parent.keys);
            } else {
                // merge with left sibling and separator
                SortedSet<KeyElement> combinedKeys = new TreeSet<>();
                combinedKeys.addAll(child.keys);
                combinedKeys.addAll(leftSibling.get().keys);
                combinedKeys.add(new KeyElement(centerKey.key, centerKey.valueHash, child.keys.first().targetHash));
                TreeNode combined = new TreeNode(combinedKeys);
                Multihash combinedHash = storage.put(combined.toMerkleNode());

                parent.keys.remove(leftKey.get());
                parent.keys.remove(centerKey);
                parent.keys.add(new KeyElement(leftKey.get().key, leftKey.get().valueHash, combinedHash));
                if (child.hash.isPresent())
                    storage.remove(child.hash.get());
                storage.remove(parent.hash.get());
                if (parent.keys.size() >= maxChildren/2) {
                    Multihash hash = storage.put(parent.toMerkleNode());
                    return new TreeNode(parent, hash);
                }
                return new TreeNode(parent.keys);
            }
        }
    }

    public void print(PrintStream w, int depth, ContentAddressedStorage storage) throws IOException {
        int index = 0;
        for (KeyElement e: keys) {
            String tab = "";
            for (int i=0; i < depth; i++)
                tab += "   ";
            w.print(String.format(tab + "[%d/%d] %s : %s\n", index++, keys.size(), e.key.toString(), new ByteArrayWrapper(e.valueHash.toBytes()).toString()));
            if (e.targetHash.isPresent())
                TreeNode.deserialize(storage.get(e.targetHash.get())).print(w, depth + 1, storage);
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
        Map<String, Multihash> links = Stream.concat(
                keys.stream()
                        .filter(k -> k.targetHash.isPresent())
                        .map(k -> k.targetHash.get()),
                keys.stream()
                        .filter(k -> k.valueHash.isPresent())
                        .map(k -> k.valueHash.get()))
                .collect(Collectors.toMap(h -> h.toString(), h -> h));
        return new MerkleNode(serialize(), links);
    }

    public static TreeNode fromMerkleNode(MerkleNode node) throws IOException {
        return deserialize(node.data);
    }

    public static TreeNode deserialize(byte[] raw) throws IOException {
        if (raw == null)
            throw new IllegalArgumentException("Null byte[]!");
        DataInputStream din = new DataInputStream(new ByteArrayInputStream(raw));
        int n = din.readInt();
        SortedSet<KeyElement> keys = new TreeSet<>();
        for (int i=0; i < n; i++) {
            byte[] key = new byte[din.readInt()];
            din.readFully(key);
            MaybeMultihash valueHash = MaybeMultihash.deserialize(din);
            MaybeMultihash targetHash = MaybeMultihash.deserialize(din);;
            keys.add(new KeyElement(new ByteArrayWrapper(key), valueHash, targetHash));
        }
        return new TreeNode(keys);
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
