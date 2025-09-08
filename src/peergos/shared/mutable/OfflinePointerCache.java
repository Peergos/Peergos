package peergos.shared.mutable;

import peergos.shared.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;

public class OfflinePointerCache implements MutablePointers {

    private static final ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1);

    private final MutablePointers target;
    private final PointerCache cache;
    private final OnlineState online;
    private final LRUCache<PublicKeyHash, byte[]> ramCache = new LRUCache<>(10);

    public OfflinePointerCache(MutablePointers target, PointerCache cache, OnlineState online) {
        this.target = target;
        this.cache = cache;
        this.online = online;
    }

    @Override
    public CompletableFuture<Boolean> setPointer(PublicKeyHash owner, PublicKeyHash writer, byte[] writerSignedBtreeRootHash) {
        return target.setPointer(owner, writer, writerSignedBtreeRootHash).thenApply(res -> {
            if (res) {
                ramCache.put(writer, writerSignedBtreeRootHash);
                cache.put(owner, writer, writerSignedBtreeRootHash);
            }
            return res;
        });
    }

    @Override
    public CompletableFuture<Optional<byte[]>> getPointer(PublicKeyHash owner, PublicKeyHash writer) {
        return Futures.asyncExceptionally(() -> {
                    if (online.isOnline()) {
                        CompletableFuture<Optional<byte[]>> res = new CompletableFuture<>();
                        // race the cache with the server after 1s
                        target.getPointer(owner, writer)
                                .thenAccept(pointer -> {
                                    pointer.ifPresent(p -> {
                                        ramCache.put(writer, p);
                                        cache.put(owner, writer, p);
                                    });
                                    res.complete(pointer);
                                }).exceptionally(t -> {
                                    res.completeExceptionally(t);
                                    return null;
                                });
                        executor.schedule(() -> {
                            byte[] fromRam = ramCache.get(writer);
                            if (fromRam != null) {
                                if (!res.isDone())
                                    res.complete(Optional.of(fromRam));
                            } else
                                cache.get(owner, writer).thenAccept(cached -> {
                                    if (cached.isPresent()) {
                                        if (!res.isDone())
                                            res.complete(cached);
                                    }
                                });
                            return true;
                        }, 1_000, TimeUnit.MILLISECONDS);
                        return res;
                    }
                    online.updateAsync();
                    return cache.get(owner, writer);
                },
                t -> {
                    if (online.isOfflineException(t))
                        return cache.get(owner, writer);
                    return Futures.errored(t);
                });
    }

    @Override
    public MutablePointers clearCache() {
        return new OfflinePointerCache(target.clearCache(), cache, online);
    }
}
