package peergos.shared.merklebtree;

import peergos.shared.crypto.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.storage.ContentAddressedStorage;
import peergos.shared.util.*;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;

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

    public static CompletableFuture<MerkleBTree> create(PublicKeyHash writer, Multihash rootHash, ContentAddressedStorage dht) {
        return dht.get(rootHash).thenApply(rawOpt -> {
            if (! rawOpt.isPresent())
                throw new IllegalStateException("Null byte[] returned by DHT for hash: " + rootHash);
            return new MerkleBTree(TreeNode.fromCbor(rawOpt.get()), rootHash, dht, MAX_NODE_CHILDREN);
        });
    }

    public static CompletableFuture<MerkleBTree> create(SigningPrivateKeyAndPublicHash writer, ContentAddressedStorage dht) {
        TreeNode newRoot = new TreeNode(new TreeSet<>());
        byte[] raw = newRoot.serialize();
        return dht.put(writer.publicKeyHash, writer.secret.signatureOnly(raw), raw)
                .thenApply(put -> new MerkleBTree(newRoot, put, dht, MAX_NODE_CHILDREN));
    }

    /**
     *
     * @param rawKey
     * @return value stored under rawKey
     * @throws IOException
     */
    public CompletableFuture<MaybeMultihash> get(byte[] rawKey) {
        return root.get(new ByteArrayWrapper(rawKey), storage);
    }

    /**
     *
     * @param rawKey
     * @param value
     * @return hash of new tree root
     * @throws IOException
     */
    public CompletableFuture<Multihash> put(SigningPrivateKeyAndPublicHash writer, byte[] rawKey, MaybeMultihash existing, Multihash value) {
        return root.put(writer, new ByteArrayWrapper(rawKey), existing, value, storage, maxChildren)
                .thenCompose(newRoot -> commit(writer, newRoot));
    }

    /**
     *
     * @param rawKey
     * @return hash of new tree root
     * @throws IOException
     */
    public CompletableFuture<Multihash> delete(SigningPrivateKeyAndPublicHash writer, byte[] rawKey, MaybeMultihash existing) {
        return root.delete(writer, new ByteArrayWrapper(rawKey), existing, storage, maxChildren)
                .thenCompose(newRoot -> commit(writer, newRoot));
    }

    private CompletableFuture<Multihash> commit(SigningPrivateKeyAndPublicHash writer, TreeNode newRoot) {
        if (newRoot.hash.isPresent()) {
            root = newRoot;
            return CompletableFuture.completedFuture(newRoot.hash.get());
        }
        byte[] raw = newRoot.serialize();
        return storage.put(writer.publicKeyHash, writer.secret.signatureOnly(raw), raw).thenApply(newRootHash -> {
            root = new TreeNode(newRoot.keys, newRootHash);
            return newRootHash;
        });
    }

    /**
     *
     * @return number of keys stored in tree
     * @throws IOException
     */
    public CompletableFuture<Integer> size() {
        return root.size(storage);
    }

    public void print(PrintStream w) throws Exception {
        root.print(w, 0, storage);
    }
}
