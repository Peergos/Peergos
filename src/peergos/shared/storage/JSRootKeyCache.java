package peergos.shared.storage;

import peergos.shared.crypto.symmetric.SymmetricKey;
import peergos.shared.user.NativeJSRootKeyCache;

import java.util.concurrent.CompletableFuture;

public class JSRootKeyCache {

    private final NativeJSRootKeyCache cache = new NativeJSRootKeyCache();

    public JSRootKeyCache() {
        cache.init();
    }
    /*
    public CompletableFuture<SymmetricKey> getRootKey(String username) {
        return cache.getRootKey(username).thenApply(rkBytes ->
                SymmetricKey.fromByteArray(rkBytes));
    }
    */
    public CompletableFuture<Boolean> setRootKey(String username, SymmetricKey loginRoot) {
        return cache.setRootKey(username, loginRoot.serialize());
    }
}
