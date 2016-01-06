package peergos.storage.merklebtree;

import org.ipfs.api.Multihash;
import peergos.util.*;

import java.io.*;
import java.util.*;
import java.util.stream.Collectors;

public class TreeNode {
    public final Optional<byte[]> hash;
    public final SortedSet<KeyElement> keys;

    public TreeNode(byte[] leftChildHash, SortedSet<KeyElement> keys, Optional<byte[]> ourHash) {
        this.keys = new TreeSet<>();
        this.keys.addAll(keys);
        KeyElement zero = new KeyElement(new ByteArrayWrapper(new byte[0]), new byte[0], leftChildHash);
        if (!keys.contains(zero))
            this.keys.add(zero);
        this.hash = ourHash;
    }

    public TreeNode(byte[] leftChildHash, SortedSet<KeyElement> keys) {
        this(leftChildHash, keys, Optional.empty());
    }

    public TreeNode(SortedSet<KeyElement> keys) {
        this(new byte[0], keys, Optional.empty());
    }

    public TreeNode(SortedSet<KeyElement> keys, byte[] ourHash) {
        this(new byte[0], keys, Optional.of(ourHash));
    }

    public TreeNode(TreeNode node, byte[] hash) {
        this(new byte[0], node.keys, Optional.of(hash));
    }

    private TreeNode withHash(byte[] hash) {
        return new TreeNode(keys, hash);
    }

    public byte[] get(ByteArrayWrapper key, ContentAddressedStorage storage) throws IOException {
        KeyElement dummy = new KeyElement(key, new byte[0], new byte[0]);
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
        if (nextSmallest.targetHash.length == 0)
            return null;
        return TreeNode.deserialize(storage.get(nextSmallest.targetHash)).get(key, storage);
    }

    public TreeNode put(ByteArrayWrapper key, byte[] value, ContentAddressedStorage storage, int maxChildren) throws IOException {
        KeyElement dummy = new KeyElement(key, null, null);
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
            KeyElement modified = new KeyElement(key, value, nextSmallest.targetHash);
            keys.remove(nextSmallest);
            keys.add(modified);
            // commit this node to storage
            byte[] hash = storage.put(this.toMerkleNode()).toBytes();
            if (!Arrays.equals(hash, this.hash.get()))
                storage.remove(this.hash.get());
            return new TreeNode(this.keys, hash);
        }
        if (nextSmallest.targetHash.length == 0) {
            if (keys.size() < maxChildren) {
                keys.add(new KeyElement(key, value, new byte[0]));
                // commit this node to storage
                byte[] hash = storage.put(this.toMerkleNode()).toBytes();
                if (this.hash.isPresent())
                    storage.remove(this.hash.get());
                return new TreeNode(this.keys, hash);
            }
            // split into two and make new parent
            keys.add(new KeyElement(key, value, new byte[0]));
            KeyElement[] tmp = new KeyElement[keys.size()];
            KeyElement median = keys.toArray(tmp)[keys.size()/2];
            // commit left child
            SortedSet<KeyElement> left = keys.headSet(median);
            TreeNode leftChild = new TreeNode(left);
            byte[] leftChildHash = storage.put(leftChild.toMerkleNode()).toBytes();

            // commit right child
            SortedSet<KeyElement> right = keys.tailSet(median);
            right.remove(right.first());
            TreeNode rightChild = new TreeNode(median.targetHash, right);
            byte[] rightChildHash = storage.put(rightChild.toMerkleNode()).toBytes();

            // now add median to parent
            TreeSet holder = new TreeSet<>();
            KeyElement newParent = new KeyElement(median.key, median.valueHash, rightChildHash);
            holder.add(newParent);
            storage.remove(this.hash.get());
            return new TreeNode(leftChildHash, holder);
        }

        TreeNode modifiedChild = TreeNode.deserialize(storage.get(nextSmallest.targetHash)).withHash(nextSmallest.targetHash).put(key, value, storage, maxChildren);
        if (!modifiedChild.hash.isPresent() || !Arrays.equals(modifiedChild.hash.get(), nextSmallest.targetHash))
            storage.remove(nextSmallest.targetHash);
        if (!modifiedChild.hash.isPresent()) {
            // we split a child and need to add the median to our keys
            if (keys.size() < maxChildren) {
                KeyElement replacementNextSmallest = new KeyElement(nextSmallest.key, nextSmallest.valueHash, modifiedChild.keys.first().targetHash);
                keys.remove(nextSmallest);
                keys.add(replacementNextSmallest);
                keys.add(modifiedChild.keys.last());
                byte[] hash = storage.put(this.toMerkleNode()).toBytes();
                storage.remove(this.hash.get());
                return new TreeNode(this.keys, hash);
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
            byte[] leftChildHash = storage.put(leftChild.toMerkleNode()).toBytes();

            // commit right child
            SortedSet<KeyElement> right = keys.tailSet(median);
            right.remove(right.first());
            TreeNode rightChild = new TreeNode(median.targetHash, right);
            byte[] rightChildHash = storage.put(rightChild.toMerkleNode()).toBytes();

            // now add median to parent
            TreeSet holder = new TreeSet<>();
            KeyElement newParent = new KeyElement(median.key, median.valueHash, rightChildHash);
            holder.add(newParent);
            storage.remove(this.hash.get());
            return new TreeNode(leftChildHash, holder);
        }
        // update pointer to child (child element wasn't split)
        KeyElement updated = new KeyElement(nextSmallest.key, nextSmallest.valueHash, modifiedChild.hash.get());
        keys.remove(nextSmallest);
        keys.add(updated);
        byte[] hash = storage.put(this.toMerkleNode()).toBytes();
        if (!Arrays.equals(hash, this.hash.get()))
            storage.remove(this.hash.get());
        return new TreeNode(this, hash);
    }

    public int size(ContentAddressedStorage storage) throws IOException {
        int total = 0;
        for (KeyElement e : keys)
            if (e.targetHash.length > 0)
                total += TreeNode.deserialize(storage.get(e.targetHash)).size(storage);
        total += keys.size() - 1;
        return total;
    }

    private KeyElement smallestNonZeroKey() {
        return keys.tailSet(new KeyElement(new ByteArrayWrapper(new byte[]{0}), new byte[0], new byte[0])).first();
    }

    public ByteArrayWrapper smallestKey(ContentAddressedStorage storage) throws IOException {
        if (keys.first().targetHash.length == 0)
            return keys.toArray(new KeyElement[keys.size()])[1].key;
        return TreeNode.deserialize(storage.get(keys.first().targetHash)).smallestKey(storage);
    }

    public TreeNode delete(ByteArrayWrapper key, ContentAddressedStorage storage, int maxChildren) throws IOException {
        KeyElement dummy = new KeyElement(key, new byte[0], new byte[0]);
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
            if (nextSmallest.targetHash.length == 0) {
                // we are a leaf
                keys.remove(nextSmallest);
                storage.remove(this.hash.get());
                if (keys.size() >= maxChildren/2) {
                    byte[] hash = storage.put(this.toMerkleNode()).toBytes();
                    return new TreeNode(this.keys, hash);
                }
                return new TreeNode(this.keys);
            } else {
                TreeNode child = TreeNode.deserialize(storage.get(nextSmallest.targetHash)).withHash(nextSmallest.targetHash);
                // take the subtree's smallest value (in a leaf) delete it and promote it to the separator here
                ByteArrayWrapper smallestKey = child.smallestKey(storage);
                byte[] value = child.get(smallestKey, storage);
                TreeNode newChild = child.delete(smallestKey, storage, maxChildren);

                byte[] childHash = storage.put(newChild.toMerkleNode()).toBytes();
                keys.remove(nextSmallest);
                storage.remove(nextSmallest.targetHash);
                KeyElement replacement = new KeyElement(smallestKey, value, childHash);
                keys.add(replacement);
                storage.remove(this.hash.get());
                if (newChild.keys.size() >= maxChildren/2) {
                    byte[] hash = storage.put(this.toMerkleNode()).toBytes();
                    return new TreeNode(this, hash);
                } else {
                    // re-balance
                    return rebalance(this, newChild, childHash, storage, maxChildren);
                }
            }
        }
        if (nextSmallest.targetHash.length == 0)
            return new TreeNode(this.keys);
        TreeNode child = TreeNode.deserialize(storage.get(nextSmallest.targetHash)).withHash(nextSmallest.targetHash).delete(key, storage, maxChildren);
        if (!child.hash.isPresent() || !Arrays.equals(child.hash.get(), nextSmallest.targetHash))
            storage.remove(nextSmallest.targetHash);
        // update pointer
        if (child.hash.isPresent()) {
            keys.remove(nextSmallest);
            keys.add(new KeyElement(nextSmallest.key, nextSmallest.valueHash, child.hash.get()));
        }
        if (child.keys.size() < maxChildren / 2) {
            // re-balance
            return rebalance(this, child, nextSmallest.targetHash, storage, maxChildren);
        }
        byte[] hash = storage.put(this.toMerkleNode()).toBytes();
        storage.remove(this.hash.get());
        return new TreeNode(this, hash);
    }

    private static TreeNode rebalance(TreeNode parent, TreeNode child, byte[] originalChildHash, ContentAddressedStorage storage, int maxChildren) throws IOException {
        // child has too few children
        ByteArrayWrapper childHash = new ByteArrayWrapper(originalChildHash);
        KeyElement[] parentKeys = parent.keys.toArray(new KeyElement[parent.keys.size()]);
        int i = 0;
        while (i < parentKeys.length && !(new ByteArrayWrapper(parentKeys[i].targetHash)).equals(childHash))
            i++;

        KeyElement centerKey = parentKeys[i];
        Optional<KeyElement> leftKey = i > 0 ? Optional.of(parentKeys[i-1]) : Optional.empty();
        Optional<KeyElement> rightKey = i + 1 < parentKeys.length ? Optional.of(parentKeys[i+1]) : Optional.empty();
        Optional<TreeNode> leftSibling = leftKey.isPresent() ? Optional.of(TreeNode.deserialize(storage.get(leftKey.get().targetHash))) : Optional.empty();
        Optional<TreeNode> rightSibling = rightKey.isPresent() ? Optional.of(TreeNode.deserialize(storage.get(rightKey.get().targetHash))) : Optional.empty();
        if (rightSibling.isPresent() && rightSibling.get().keys.size() > maxChildren/2) {
            // rotate left
            TreeNode right = rightSibling.get();
            KeyElement newSeparator = right.smallestNonZeroKey();
            parent.keys.remove(centerKey);

            child.keys.add(new KeyElement(rightKey.get().key, rightKey.get().valueHash, right.keys.first().targetHash));
            byte[] newChildHash = storage.put(child.toMerkleNode()).toBytes();

            right.keys.remove(newSeparator);
            right.keys.remove(new KeyElement(new ByteArrayWrapper(new byte[0]), new byte[0], new byte[0]));
            right = new TreeNode(newSeparator.targetHash, right.keys);
            byte[] newRightHash = storage.put(right.toMerkleNode()).toBytes();

            parent.keys.remove(rightKey.get());
            parent.keys.add(new KeyElement(centerKey.key, centerKey.valueHash, newChildHash));
            parent.keys.add(new KeyElement(newSeparator.key, newSeparator.valueHash, newRightHash));
            byte[] hash = storage.put(parent.toMerkleNode()).toBytes();
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
            byte[] newLeftHash = storage.put(left.toMerkleNode()).toBytes();

            child.keys.add(new KeyElement(centerKey.key, centerKey.valueHash, child.keys.first().targetHash));
            child.keys.remove(new KeyElement(new ByteArrayWrapper(new byte[0]), new byte[0], new byte[0]));
            child.keys.add(new KeyElement(new ByteArrayWrapper(new byte[0]), new byte[0], newSeparator.targetHash));
            byte[] newChildHash = storage.put(child.toMerkleNode()).toBytes();

            parent.keys.remove(leftKey.get());
            parent.keys.add(new KeyElement(leftKey.get().key, leftKey.get().valueHash, newLeftHash));
            parent.keys.add(new KeyElement(newSeparator.key, newSeparator.valueHash, newChildHash));
            byte[] hash = storage.put(parent.toMerkleNode()).toBytes();
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
                byte[] combinedHash = storage.put(combined.toMerkleNode()).toBytes();

                parent.keys.remove(rightKey.get());
                parent.keys.remove(centerKey);
                parent.keys.add(new KeyElement(centerKey.key, centerKey.valueHash, combinedHash));
                if (child.hash.isPresent())
                    storage.remove(child.hash.get());
                storage.remove(parent.hash.get());
                if (parent.keys.size() >= maxChildren/2) {
                    byte[] hash = storage.put(parent.toMerkleNode()).toBytes();
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
                byte[] combinedHash = storage.put(combined.toMerkleNode()).toBytes();

                parent.keys.remove(leftKey.get());
                parent.keys.remove(centerKey);
                parent.keys.add(new KeyElement(leftKey.get().key, leftKey.get().valueHash, combinedHash));
                if (child.hash.isPresent())
                    storage.remove(child.hash.get());
                storage.remove(parent.hash.get());
                if (parent.keys.size() >= maxChildren/2) {
                    byte[] hash = storage.put(parent.toMerkleNode()).toBytes();
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
            w.print(String.format(tab + "[%d/%d] %s : %s\n", index++, keys.size(), e.key.toString(), new ByteArrayWrapper(e.valueHash).toString()));
            if (e.targetHash.length > 0)
                TreeNode.deserialize(storage.get(e.targetHash)).print(w, depth + 1, storage);
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
                dout.writeInt(e.valueHash.length);
                dout.write(e.valueHash);
                dout.writeInt(e.targetHash.length);
                dout.write(e.targetHash);
            }
            return bout.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public MerkleNode toMerkleNode() {
        Map<String, Multihash> links = keys.stream().filter(k -> k.targetHash.length > 0).collect(Collectors.toMap(k -> k.key.data.length > 0 ? k.key.toString() : "0", k -> new Multihash(k.targetHash)));
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
            byte[] valueHash = new byte[din.readInt()];
            din.readFully(valueHash);
            byte[] targetHash = new byte[din.readInt()];
            din.readFully(targetHash);
            keys.add(new KeyElement(new ByteArrayWrapper(key), valueHash, targetHash));
        }
        return new TreeNode(keys);
    }

    public static class KeyElement implements Comparable<KeyElement> {
        public final ByteArrayWrapper key;
        public final byte[] valueHash;
        public final byte[] targetHash;

        public KeyElement(ByteArrayWrapper key, byte[] valueHash, byte[] targetHash) {
            this.key = key;
            this.valueHash = valueHash;
            this.targetHash = targetHash;
        }

        @Override
        public int compareTo(KeyElement that) {
            return key.compareTo(that.key);
        }

        @Override
        public String toString() {
            return key.toString() + " -> " + new ByteArrayWrapper(valueHash) +" : "+new ByteArrayWrapper(targetHash);
        }
    }
}
