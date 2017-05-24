package peergos.shared.storage;

import peergos.shared.cbor.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.io.ipfs.multiaddr.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;

public class CachingStorage implements ContentAddressedStorage {
    private final ContentAddressedStorage target;
    private final LRUCache<Multihash, byte[]> cache;
    private final LRUCache<Multihash, CompletableFuture<Optional<CborObject>>> pending;
    private final int maxValueSize;

    public CachingStorage(ContentAddressedStorage target, int cacheSize, int maxValueSize) {
        this.target = target;
        this.cache = new LRUCache<>(cacheSize);
        this.maxValueSize = maxValueSize;
        this.pending = new LRUCache<>(100);
    }

    @Override
    public CompletableFuture<List<Multihash>> put(PublicSigningKey writer, List<byte[]> blocks) {
        return target.put(writer, blocks);
    }

    @Override
    public CompletableFuture<Optional<CborObject>> get(Multihash key) {
        if (cache.containsKey(key))
            return CompletableFuture.completedFuture(Optional.of(CborObject.fromByteArray(cache.get(key))));

        if (pending.containsKey(key))
            return pending.get(key);

        CompletableFuture<Optional<CborObject>> pipe = new CompletableFuture<>();
        pending.put(key, pipe);
        return target.get(key).thenApply(cborOpt -> {
            if (cborOpt.isPresent()) {
                byte[] value = cborOpt.get().toByteArray();
                if (value.length > 0 && value.length < maxValueSize)
                    cache.put(key, value);
            }
            pending.remove(key);
            pipe.complete(cborOpt);
            return cborOpt;
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

    @Override
    public CompletableFuture<List<MultiAddress>> pinUpdate(Multihash existing, Multihash updated) {
        return target.pinUpdate(existing, updated);
    }

    @Override
    public CompletableFuture<List<Multihash>> getLinks(Multihash root) {
        return target.getLinks(root);
    }

    @Override
    public CompletableFuture<Optional<Integer>> getSize(Multihash block) {
        return target.getSize(block);
    }
}
