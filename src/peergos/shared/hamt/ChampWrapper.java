package peergos.shared.hamt;

import peergos.shared.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.storage.*;
import peergos.shared.user.*;
import peergos.shared.util.*;

import java.io.*;
import java.util.concurrent.*;
import java.util.function.*;

public class ChampWrapper implements ImmutableTree
{
    public static final int BIT_WIDTH = 3;
    public static final int MAX_HASH_COLLISIONS_PER_LEVEL = 4;

    public final ContentAddressedStorage storage;
    public final int bitWidth;
    private final Function<ByteArrayWrapper, byte[]> hasher;
    private Pair<Champ, Multihash> root;

    public ChampWrapper(Champ root, Multihash rootHash, Function<ByteArrayWrapper, byte[]> hasher, ContentAddressedStorage storage, int bitWidth) {
        this.storage = storage;
        this.hasher = hasher;
        this.root = new Pair<>(root, rootHash);
        this.bitWidth = bitWidth;
    }

    public static CompletableFuture<ChampWrapper> create(Multihash rootHash,
                                                         Function<ByteArrayWrapper, byte[]> hasher,
                                                         ContentAddressedStorage dht) {
        return dht.get(rootHash).thenApply(rawOpt -> {
            if (! rawOpt.isPresent())
                throw new IllegalStateException("Champ root not present: " + rootHash);
            return new ChampWrapper(Champ.fromCbor(rawOpt.get()), rootHash, hasher, dht, BIT_WIDTH);
        });
    }

    public static CompletableFuture<ChampWrapper> create(PublicKeyHash owner,
                                                         SigningPrivateKeyAndPublicHash writer,
                                                         Function<ByteArrayWrapper, byte[]> hasher,
                                                         TransactionId tid,
                                                         ContentAddressedStorage dht) {
        Champ newRoot = Champ.empty();
        byte[] raw = newRoot.serialize();
        return dht.put(owner, writer.publicKeyHash, writer.secret.signatureOnly(raw), raw, tid)
                .thenApply(put -> new ChampWrapper(newRoot, put, hasher, dht, BIT_WIDTH));
    }

    /**
     *
     * @param rawKey
     * @return value stored under rawKey
     * @throws IOException
     */
    @Override
    public CompletableFuture<MaybeMultihash> get(byte[] rawKey) {
        ByteArrayWrapper key = new ByteArrayWrapper(rawKey);
        return root.left.get(key, hasher.apply(key), 0, BIT_WIDTH, storage);
    }

    /**
     *
     * @param rawKey
     * @param value
     * @return hash of new tree root
     * @throws IOException
     */
    @Override
    public CompletableFuture<Multihash> put(PublicKeyHash owner,
                                            SigningPrivateKeyAndPublicHash writer,
                                            byte[] rawKey,
                                            MaybeMultihash existing,
                                            Multihash value,
                                            TransactionId tid) {
        ByteArrayWrapper key = new ByteArrayWrapper(rawKey);
        return root.left.put(owner, writer, key, hasher.apply(key), 0, existing, MaybeMultihash.of(value),
                BIT_WIDTH, MAX_HASH_COLLISIONS_PER_LEVEL, hasher, tid, storage, root.right)
                .thenCompose(newRoot -> commit(writer, newRoot));
    }

    /**
     *
     * @param rawKey
     * @return hash of new tree root
     * @throws IOException
     */
    @Override
    public CompletableFuture<Multihash> remove(PublicKeyHash owner,
                                               SigningPrivateKeyAndPublicHash writer,
                                               byte[] rawKey,
                                               MaybeMultihash existing,
                                               TransactionId tid) {
        ByteArrayWrapper key = new ByteArrayWrapper(rawKey);
        return root.left.put(owner, writer, key, hasher.apply(key), 0, existing, MaybeMultihash.empty(),
                BIT_WIDTH, MAX_HASH_COLLISIONS_PER_LEVEL, hasher, tid, storage, root.right)
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

    /**
     *
     * @return true
     * @throws IOException
     */
    public <T> CompletableFuture<T> applyToAllMappings(T identity,
                                                       BiFunction<T, Pair<ByteArrayWrapper, MaybeMultihash>, CompletableFuture<T>> consumer) {
        return root.left.applyToAllMappings(identity, consumer, storage);
    }
}
