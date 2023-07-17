package peergos.shared.storage;

import peergos.shared.io.ipfs.Cid;
import peergos.shared.io.ipfs.Multihash;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;

public class RamBlockCache implements BlockCache {

    private final LRUCache<Multihash, byte[]> cache;
    private final int maxValueSize, cacheSize;

    public RamBlockCache(int maxValueSize, int cacheSize) {
        this.cache = new LRUCache<>(cacheSize);
        this.maxValueSize = maxValueSize;
        this.cacheSize = cacheSize;
    }

    public Collection<byte[]> getCached() {
        return cache.values();
    }

    @Override
    public CompletableFuture<Boolean> put(Cid hash, byte[] data) {
        if (data.length < maxValueSize && data.length > 0)
            cache.put(hash, data);
        return Futures.of(true);
    }

    @Override
    public CompletableFuture<Optional<byte[]>> get(Cid hash) {
        return Futures.of(Optional.ofNullable(cache.get(hash)));
    }

    @Override
    public boolean hasBlock(Cid hash) {
        return cache.containsKey(hash);
    }

    @Override
    public CompletableFuture<Boolean> clear() {
        cache.clear();
        return Futures.of(true);
    }
}
