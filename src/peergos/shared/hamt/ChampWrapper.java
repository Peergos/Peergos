package peergos.shared.hamt;

import peergos.shared.crypto.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.merklebtree.*;
import peergos.shared.storage.*;
import peergos.shared.user.*;
import peergos.shared.util.*;

import java.io.*;
import java.util.concurrent.*;

public class ChampWrapper implements ImmutableTree
{
    private static final int BIT_WIDTH = 5;
    private static final int MAX_HASH_COLLISIONS_PER_LEVEL = 3;

    public final ContentAddressedStorage storage;
    public final int bitWidth;
    private Pair<Champ, Multihash> root;

    public ChampWrapper(Champ root, Multihash rootHash, ContentAddressedStorage storage, int bitWidth) {
        this.storage = storage;
        this.root = new Pair<>(root, rootHash);
        this.bitWidth = bitWidth;
    }

    public static CompletableFuture<ChampWrapper> create(PublicKeyHash writer, Multihash rootHash, ContentAddressedStorage dht) {
        return dht.get(rootHash).thenApply(rawOpt -> {
            if (! rawOpt.isPresent())
                throw new IllegalStateException("Null byte[] returned by DHT for hash: " + rootHash);
            return new ChampWrapper(Champ.fromCbor(rawOpt.get()), rootHash, dht, 6);
        });
    }

    public static CompletableFuture<ChampWrapper> create(SigningPrivateKeyAndPublicHash writer, ContentAddressedStorage dht) {
        Champ newRoot = Champ.empty();
        byte[] raw = newRoot.serialize();
        return dht.put(writer.publicKeyHash, writer.secret.signatureOnly(raw), raw)
                .thenApply(put -> new ChampWrapper(newRoot, put, dht, 6));
    }

    /**
     *
     * @param rawKey
     * @return value stored under rawKey
     * @throws IOException
     */
    public CompletableFuture<MaybeMultihash> get(byte[] rawKey) {
        return root.left.get(new ByteArrayWrapper(rawKey), 0, BIT_WIDTH, storage);
    }

    /**
     *
     * @param rawKey
     * @param value
     * @return hash of new tree root
     * @throws IOException
     */
    public CompletableFuture<Multihash> put(SigningPrivateKeyAndPublicHash writer, byte[] rawKey, MaybeMultihash existing, Multihash value) {
        return root.left.put(writer, new ByteArrayWrapper(rawKey), 0, existing, MaybeMultihash.of(value), BIT_WIDTH, MAX_HASH_COLLISIONS_PER_LEVEL, storage, root.right)
                .thenCompose(newRoot -> commit(writer, newRoot));
    }

    /**
     *
     * @param rawKey
     * @return hash of new tree root
     * @throws IOException
     */
    public CompletableFuture<Multihash> remove(SigningPrivateKeyAndPublicHash writer, byte[] rawKey, MaybeMultihash existing) {
        return root.left.put(writer, new ByteArrayWrapper(rawKey), 0, existing, MaybeMultihash.empty(), BIT_WIDTH, MAX_HASH_COLLISIONS_PER_LEVEL, storage, root.right)
                .thenCompose(newRoot -> commit(writer, newRoot));
    }

    private CompletableFuture<Multihash> commit(SigningPrivateKeyAndPublicHash writer, Pair<Champ, Multihash> newRoot) {
        root = newRoot;
        return CompletableFuture.completedFuture(newRoot.right);
    }

    /**
     *
     * @return number of keys stored in tree
     * @throws IOException
     */
    public CompletableFuture<Long> size() {
        return root.left.size(0, storage);
    }
}
