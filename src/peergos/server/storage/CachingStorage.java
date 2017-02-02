package peergos.server.storage;

import peergos.shared.cbor.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.io.ipfs.multihash.*;
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
    public CompletableFuture<List<Multihash>> put(PublicSigningKey writer, List<byte[]> blocks) {
        return target.put(writer, blocks);
    }

    @Override
    public CompletableFuture<Optional<CborObject>> get(Multihash key) {
        if (cache.containsKey(key))
            return CompletableFuture.completedFuture(Optional.of(CborObject.fromByteArray(cache.get(key))));
        return target.get(key).thenApply(cborOpt -> {
            if (cborOpt.isPresent()) {
                byte[] value = cborOpt.get().toByteArray();
                if (value.length > 0 && value.length < maxValueSize)
                    cache.put(key, value);
            }
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
}
