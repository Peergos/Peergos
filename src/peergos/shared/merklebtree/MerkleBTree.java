package peergos.shared.merklebtree;

import peergos.shared.ipfs.api.*;
import peergos.shared.storage.ContentAddressedStorage;
import peergos.shared.util.*;

import java.io.*;
import java.util.*;

public class MerkleBTree
{
    public static final int MAX_NODE_CHILDREN = 16;
    public final ContentAddressedStorage storage;
    public final int maxChildren;
    public TreeNode root;

    public MerkleBTree(TreeNode root, MaybeMultihash rootHash, ContentAddressedStorage storage, int maxChildren) {
        this.storage = storage;
        this.root = new TreeNode(root.keys, rootHash);
        this.maxChildren = maxChildren;
    }

    public MerkleBTree(TreeNode root, Multihash rootHash, ContentAddressedStorage storage, int maxChildren) {
        this(root, MaybeMultihash.of(rootHash), storage, maxChildren);
    }

    public static MerkleBTree create(Multihash rootHash, ContentAddressedStorage dht) throws IOException {
        return create(MaybeMultihash.of(rootHash),
                dht);
    }

    public static MerkleBTree create(MaybeMultihash rootHash, ContentAddressedStorage dht) throws IOException {
        if (!  rootHash.isPresent()) {
            TreeNode newRoot = new TreeNode(new TreeSet<>());
            Multihash put = dht.put(newRoot.toMerkleNode());
            return new MerkleBTree(newRoot, put, dht, MAX_NODE_CHILDREN);
        }
        byte[] raw = dht.get(rootHash.get());
        if (raw == null)
            throw new IllegalStateException("Null byte[] returned by DHT for hash: " + rootHash.get());
        return new MerkleBTree(TreeNode.deserialize(raw), rootHash, dht, MAX_NODE_CHILDREN);
    }

    /**
     *
     * @param rawKey
     * @return value stored under rawKey
     * @throws IOException
     */
    public MaybeMultihash get(byte[] rawKey) throws IOException {
        return root.get(new ByteArrayWrapper(rawKey), storage);
    }

    /**
     *
     * @param rawKey
     * @param value
     * @return hash of new tree root
     * @throws IOException
     */
    public Multihash put(byte[] rawKey, Multihash value) throws IOException {
        TreeNode newRoot = root.put(new ByteArrayWrapper(rawKey), value, storage, maxChildren);
        if (root.hash.isPresent() && ! root.hash.equals(newRoot.hash))
            storage.remove(root.hash.get());
        if (!newRoot.hash.isPresent()) {
            root = new TreeNode(newRoot.keys, storage.put(newRoot.toMerkleNode()));
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
    public Multihash delete(byte[] rawKey) throws IOException {
        TreeNode newRoot = root.delete(new ByteArrayWrapper(rawKey), storage, maxChildren);
        if (root.hash.isPresent())
            storage.remove(root.hash.get());
        if (!newRoot.hash.isPresent()) {
            root = new TreeNode(newRoot.keys, storage.put(newRoot.toMerkleNode()));
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
