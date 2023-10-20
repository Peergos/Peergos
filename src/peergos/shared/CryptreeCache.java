package peergos.shared;

import peergos.shared.io.ipfs.Multihash;
import peergos.shared.user.fs.cryptree.*;
import peergos.shared.util.*;

import java.util.*;

public class CryptreeCache {

    private final LRUCache<Pair<Multihash, ByteArrayWrapper>, Optional<CryptreeNode>> cache;

    public CryptreeCache() {
        int cacheSize = 1_000;
        this.cache = new LRUCache<>(cacheSize);
    }

    public boolean containsKey(Pair<Multihash, ByteArrayWrapper> cacheKey) {
        return cache.containsKey(cacheKey);
    }

    public Optional<CryptreeNode> get(Pair<Multihash, ByteArrayWrapper> cacheKey) {
        return cache.get(cacheKey);
    }

    public void put(Pair<Multihash, ByteArrayWrapper> cacheKey, Optional<CryptreeNode> val) {
        cache.put(cacheKey, val);
    }

    public void update(Optional<Multihash> priorRoot, Pair<Multihash, ByteArrayWrapper> cacheKey, Optional<CryptreeNode> val) {
        // update other mappings in cache from same root and different map key as they have not changed
        if (priorRoot.isPresent()) {
            new HashMap<>(cache).entrySet().stream()
                    .filter(e -> e.getKey().left.equals(priorRoot.get()))
                    .forEach(e -> cache.put(new Pair<>(cacheKey.left, e.getKey().right), e.getValue()));
        }
        cache.put(cacheKey, val);
    }
}
