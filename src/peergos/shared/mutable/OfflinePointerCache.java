package peergos.shared.mutable;

import peergos.shared.crypto.hash.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;

public class OfflinePointerCache implements MutablePointers {

    private final MutablePointers target;
    private final PointerCache cache;

    public OfflinePointerCache(MutablePointers target, PointerCache cache) {
        this.target = target;
        this.cache = cache;
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
        return Futures.asyncExceptionally(() -> target.getPointer(owner, writer)
                        .thenApply(res -> {
                            res.ifPresent(p -> cache.put(owner, writer, p));
                            return res;
                        }),
                t -> cache.get(owner, writer));
    }
}
