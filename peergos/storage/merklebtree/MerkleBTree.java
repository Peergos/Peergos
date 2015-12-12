package peergos.storage.merklebtree;

import peergos.storage.dht.*;
import peergos.util.*;

import java.io.*;
import java.util.*;

public class MerkleBTree
{
    public static final int MAX_NODE_CHILDREN = 16;
    public final ContentAddressedStorage storage;
    public final int maxChildren;
    public TreeNode root;

    public MerkleBTree(TreeNode root, byte[] rootHash, ContentAddressedStorage storage, int maxChildren) {
        this.storage = storage;
        this.root = new TreeNode(root.keys, rootHash);
        this.maxChildren = maxChildren;
    }

    public static MerkleBTree create(byte[] rootHash, ContentAddressedStorage dht) throws IOException {
        if (rootHash.length == 0) {
            TreeNode newRoot = new TreeNode(new TreeSet<>());
            byte[] hash = dht.put(newRoot.serialize());
            return new MerkleBTree(newRoot, hash, dht, MAX_NODE_CHILDREN);
        }
        return new MerkleBTree(TreeNode.deserialize(dht.get(rootHash)), rootHash, dht, MAX_NODE_CHILDREN);
    }

    /**
     *
     * @param rawKey
     * @return value stored under rawKey
     * @throws IOException
     */
    public byte[] get(byte[] rawKey) throws IOException {
        return root.get(new ByteArrayWrapper(rawKey), storage);
    }

    /**
     *
     * @param rawKey
     * @param value
     * @return hash of new tree root
     * @throws IOException
     */
    public byte[] put(byte[] rawKey, byte[] value) throws IOException {
        TreeNode newRoot = root.put(new ByteArrayWrapper(rawKey), value, storage, maxChildren);
        if (root.hash.isPresent())
            storage.remove(root.hash.get());
        if (!newRoot.hash.isPresent()) {
            root = new TreeNode(newRoot.keys, storage.put(newRoot.serialize()));
        } else
            root = newRoot;
        return root.hash.get();
    }

    /**
     *
     * @param rawKey
     * @return hash of new tree root
     * @throws IOException
     */
    public byte[] delete(byte[] rawKey) throws IOException {
        TreeNode newRoot = root.delete(new ByteArrayWrapper(rawKey), storage, maxChildren);
        if (root.hash.isPresent())
            storage.remove(root.hash.get());
        if (!newRoot.hash.isPresent()) {
            root = new TreeNode(newRoot.keys, storage.put(newRoot.serialize()));
        } else
            root = newRoot;
        return root.hash.get();
    }

    /**
     *
     * @return number of keys stored in tree
     * @throws IOException
     */
    public int size() throws IOException {
        return root.size(storage);
    }

    public void print(PrintStream w) throws IOException {
        root.print(w, 0, storage);
    }
}
