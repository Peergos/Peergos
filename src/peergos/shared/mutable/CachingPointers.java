package peergos.shared.mutable;

import peergos.shared.crypto.hash.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;

/*
 * A CoreNode that caches previous metadata blob reads for a certain time
 */
public class CachingPointers implements MutablePointers {

    private final MutablePointers target;
    private final int cacheTTL;
    private final Map<PublicKeyHash, Pair<Optional<byte[]>, Long>> cache = new HashMap<>();

    public CachingPointers(MutablePointers target, int cacheTTL) {
        this.target = target;
        this.cacheTTL = cacheTTL;
    }

    @Override
    public CompletableFuture<Optional<byte[]>> getPointer(PublicKeyHash owner, PublicKeyHash writer) {
        synchronized (cache) {
            Pair<Optional<byte[]>, Long> cached = cache.get(writer);
            if (cached != null && System.currentTimeMillis() - cached.right < cacheTTL)
                return CompletableFuture.completedFuture(cached.left);
        }
        return target.getPointer(owner, writer).thenApply(m -> {
            synchronized (cache) {
                cache.put(writer, new Pair<>(m, System.currentTimeMillis()));
            }
            return m;
        });
    }

    @Override
    public CompletableFuture<Boolean> setPointer(PublicKeyHash ownerPublicKey, PublicKeyHash writer, byte[] writerSignedBtreeRootHash) {
        synchronized (cache) {
            cache.remove(writer);
        }
        return target.setPointer(ownerPublicKey, writer, writerSignedBtreeRootHash).thenApply(res -> {
            if (res) {
                synchronized (cache) {
                    cache.put(writer, new Pair<>(Optional.of(writerSignedBtreeRootHash), System.currentTimeMillis()));
                }
            }
            return res;
        });
    }

    @Override
    public MutablePointers clearCache() {
        cache.clear();
        return this;
    }
}
