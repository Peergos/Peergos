package peergos.shared.storage;

import peergos.shared.cbor.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.symmetric.*;
import peergos.shared.storage.auth.*;
import peergos.shared.user.NativeJSBatCache;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public class JSBatCache implements EncryptedBatCache {

    private final NativeJSBatCache cache = new NativeJSBatCache();

    public JSBatCache() {
        cache.init();
    }

    @Override
    public CompletableFuture<List<BatWithId>> getUserBats(String username, SymmetricKey loginRoot) {
        return cache.getUserBats(username).thenApply(encryptedBats -> {
            if (encryptedBats == null) {
                throw new RuntimeException("No BAT cached for user: " + username);
            }
            return CipherText.fromCbor(CborObject.fromByteArray(encryptedBats)).decrypt(loginRoot, BatList::fromCbor).bats;
        });
    }

    @Override
    public CompletableFuture<Boolean> setUserBats(String username, List<BatWithId> bats, SymmetricKey loginRoot) {
        return cache.setUserBats(username, CipherText.build(loginRoot, new BatList(bats)).serialize());
    }
}
