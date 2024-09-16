package peergos.shared.mutable;

import peergos.shared.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;

public class OfflinePointerCache implements MutablePointers {

    private final MutablePointers target;
    private final PointerCache cache;
    private final OnlineState online;

    public OfflinePointerCache(MutablePointers target, PointerCache cache, OnlineState online) {
        this.target = target;
        this.cache = cache;
        this.online = online;
    }

    @Override
    public CompletableFuture<Boolean> setPointer(PublicKeyHash owner, PublicKeyHash writer, byte[] writerSignedBtreeRootHash) {
        return target.setPointer(owner, writer, writerSignedBtreeRootHash).thenApply(res -> {
            if (res)
                cache.put(owner, writer, writerSignedBtreeRootHash);
            return res;
        });
    }

    @Override
    public CompletableFuture<Optional<byte[]>> getPointer(PublicKeyHash owner, PublicKeyHash writer) {
        return Futures.asyncExceptionally(() -> {
                    if (online.isOnline()) {
                        CompletableFuture<Optional<byte[]>> res = new CompletableFuture<>();
                        // race the cache with the server
                        target.getPointer(owner, writer)
                                .thenAccept(pointer -> {
                                    pointer.ifPresent(p -> cache.put(owner, writer, p));
                                    res.complete(pointer);
                                }).exceptionally(t -> {
                                    res.completeExceptionally(t);
                                    return null;
                                });
                        cache.get(owner, writer).thenAccept(cached -> {
                            if (cached.isPresent())
                                res.complete(cached);
                        });
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
