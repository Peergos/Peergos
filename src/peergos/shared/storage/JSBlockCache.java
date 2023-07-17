package peergos.shared.storage;

import peergos.shared.io.ipfs.Cid;
import peergos.shared.user.NativeJSCache;
import java.util.*;
import java.util.concurrent.CompletableFuture;

public class JSBlockCache implements BlockCache {
    private final NativeJSCache cache = new NativeJSCache();

    public JSBlockCache(int maxSizeMiB) {
        cache.init(maxSizeMiB);
    }

    @Override
    public CompletableFuture<Optional<byte[]>> get(Cid hash) {
        return cache.get(hash);
    }

    @Override
    public boolean hasBlock(Cid hash) {
        return cache.hasBlock(hash);
    }

    @Override
    public CompletableFuture<Boolean> put(Cid hash, byte[] data) {
        return cache.put(hash, data);
    }

    @Override
    public CompletableFuture<Boolean> clear() {
        return cache.clear();
    }
}
