package peergos.shared.storage;

import peergos.shared.crypto.hash.PublicKeyHash;
import peergos.shared.mutable.PointerCache;
import peergos.shared.user.NativeJSPointerCache;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public class JSPointerCache implements PointerCache {
    private final NativeJSPointerCache cache = new NativeJSPointerCache();

    public JSPointerCache(int maxItems) {
        cache.init(maxItems);
    }

    @Override
    public CompletableFuture<Boolean> put(PublicKeyHash owner, PublicKeyHash writer, byte[] writerSignedBtreeRootHash) {
        return cache.put(owner, writer, writerSignedBtreeRootHash);
    }

    @Override
    public CompletableFuture<Optional<byte[]>> get(PublicKeyHash owner, PublicKeyHash writer) {
        return cache.get(owner, writer);
    }
}
