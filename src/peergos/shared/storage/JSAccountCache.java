package peergos.shared.storage;

import peergos.shared.cbor.CborObject;
import peergos.shared.crypto.asymmetric.PublicSigningKey;
import peergos.shared.io.ipfs.multibase.Multibase;
import peergos.shared.login.LoginCache;
import peergos.shared.user.LoginData;
import peergos.shared.user.NativeJSAccountCache;
import peergos.shared.user.UserStaticData;

import java.util.concurrent.CompletableFuture;

public class JSAccountCache implements LoginCache {

    private final NativeJSAccountCache cache = new NativeJSAccountCache();

    public JSAccountCache() {
        cache.init();
    }
    @Override
    public CompletableFuture<Boolean> setLoginData(LoginData login) {
        byte[] entrySerialized = login.entryPoints.toCbor().serialize();
        byte[] readerSerialized = login.authorisedReader.toCbor().serialize();
        String key = login.username + Multibase.encode(Multibase.Base.Base58BTC, readerSerialized);
        return cache.setLoginData(key, entrySerialized);
    }

    @Override
    public CompletableFuture<UserStaticData> getEntryData(String username, PublicSigningKey authorisedReader) {
        String key = username + Multibase.encode(Multibase.Base.Base58BTC, authorisedReader.toCbor().serialize());
        return cache.getEntryData(key).thenApply(entryPoints -> {
            if (entryPoints == null) {
                throw new RuntimeException("Client Offline!");
            }
            return UserStaticData.fromCbor(CborObject.fromByteArray(entryPoints));
        });
    }
}
