package peergos.server.storage;

import peergos.shared.crypto.*;
import peergos.shared.ipfs.api.Multihash;
import peergos.shared.merklebtree.MerkleNode;
import peergos.shared.storage.ContentAddressedStorage;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;

public class CachingStorage implements ContentAddressedStorage {
    private final ContentAddressedStorage target;
    private final LRUCache<Multihash, byte[]> cache;
    private final int maxValueSize;

    public CachingStorage(ContentAddressedStorage target, int cacheSize, int maxValueSize) {
        this.target = target;
        this.cache = new LRUCache<>(cacheSize);
        this.maxValueSize = maxValueSize;
    }

    @Override
    public CompletableFuture<Multihash> _new(UserPublicKey writer) {
        return put(writer, new MerkleNode(new byte[0]));
    }

    @Override
    public CompletableFuture<Multihash> setData(UserPublicKey writer, Multihash object, byte[] data) {
        return target.setData(writer, object, data);
    }

    @Override
    public CompletableFuture<Multihash> addLink(UserPublicKey writer, Multihash object, String label, Multihash linkTarget) {
        return target.addLink(writer, object, label, linkTarget);
    }

    @Override
    public CompletableFuture<Optional<MerkleNode>> getObject(Multihash object) {
        return target.getObject(object);
    }

    @Override
    public CompletableFuture<Multihash> put(UserPublicKey writer, MerkleNode object) {
        return target.put(writer, object);
    }

    @Override
    public CompletableFuture<Optional<byte[]>> getData(Multihash key) {
        if (cache.containsKey(key))
            return CompletableFuture.completedFuture(Optional.of(cache.get(key)));
        return target.getData(key).thenApply(valueOpt -> {
            if (valueOpt.isPresent() && valueOpt.get().length > 0 && valueOpt.get().length < maxValueSize)
                cache.put(key, valueOpt.get());
            return valueOpt;
        });
    }

    @Override
    public CompletableFuture<List<Multihash>> recursivePin(Multihash h) {
        return target.recursivePin(h);
    }

    @Override
    public CompletableFuture<List<Multihash>> recursiveUnpin(Multihash h) {
        return target.recursiveUnpin(h);
    }
}
