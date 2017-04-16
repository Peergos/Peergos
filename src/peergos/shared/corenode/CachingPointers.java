package peergos.shared.corenode;

import peergos.shared.crypto.asymmetric.*;
import peergos.shared.merklebtree.*;
import peergos.shared.mutable.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;

/*
 * A CoreNode that caches previous metadata blob reads for a certain time
 */
public class CachingPointers implements MutablePointers {

    private final MutablePointers target;
    private final int cacheTTL;
    private final Map<PublicSigningKey, Pair<MaybeMultihash, Long>> cache = new HashMap<>();

    public CachingPointers(MutablePointers target, int cacheTTL) {
        this.target = target;
        this.cacheTTL = cacheTTL;
    }

    @Override
    public CompletableFuture<MaybeMultihash> getPointer(PublicSigningKey writer) {
        synchronized (cache) {
            Pair<MaybeMultihash, Long> cached = cache.get(writer);
            if (cached != null && System.currentTimeMillis() - cached.right < cacheTTL)
                return CompletableFuture.completedFuture(cached.left);
        }
        return target.getPointer(writer).thenApply(m -> {
            synchronized (cache) {
                cache.put(writer, new Pair<>(m, System.currentTimeMillis()));
            }
            return m;
        });
    }

    @Override
    public CompletableFuture<Boolean> setPointer(PublicSigningKey ownerPublicKey, PublicSigningKey writer, byte[] writerSignedBtreeRootHash) {
        synchronized (cache) {
            cache.remove(writer);
        }
        return target.setPointer(ownerPublicKey, writer, writerSignedBtreeRootHash);
    }
}
