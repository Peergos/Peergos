package peergos.shared.storage;

import peergos.shared.cbor.CborObject;
import peergos.shared.crypto.asymmetric.PublicSigningKey;
import peergos.shared.login.LoginCache;
import peergos.shared.user.LoginData;
import peergos.shared.user.NativeJSAccountCache;
import peergos.shared.user.UserStaticData;
import peergos.shared.util.*;

import java.util.concurrent.CompletableFuture;

public class JSAccountCache implements LoginCache {

    private final NativeJSAccountCache cache = new NativeJSAccountCache();

    public JSAccountCache() {
        cache.init();
    }
    @Override
    public CompletableFuture<Boolean> setLoginData(LoginData login) {
        byte[] entrySerialized = login.entryPoints.toCbor().serialize();
        return cache.setLoginData(login.username, entrySerialized);
    }

    @Override
    public CompletableFuture<Boolean> removeLoginData(String username) {
        cache.remove(username);
        return Futures.of(true);
    }

    @Override
    public CompletableFuture<UserStaticData> getEntryData(String username, PublicSigningKey authorisedReader) {
        return cache.getEntryData(username).thenApply(entryPoints -> {
            if (entryPoints == null) {
                throw new RuntimeException("Client Offline!");
            }
            return UserStaticData.fromCbor(CborObject.fromByteArray(entryPoints));
        });
    }
}
