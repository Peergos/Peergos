package peergos.shared.storage;

import peergos.shared.storage.auth.BatCache;
import peergos.shared.storage.auth.BatWithId;
import peergos.shared.user.NativeJSBatCache;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class JSBatCache implements BatCache {

    private final NativeJSBatCache cache = new NativeJSBatCache();

    public JSBatCache() {
        cache.init();
    }

    @Override
    public CompletableFuture<List<BatWithId>> getUserBats(String username) {
        return cache.getUserBats(username).thenApply(bats -> {
            if (bats.isEmpty()) {
                throw new RuntimeException("Client Offline!");
            }
            List<BatWithId> batsWithId = new ArrayList<>();
            for(String bat : bats) {
                batsWithId.add(BatWithId.decode(bat));
            }
            return batsWithId;
        });
    }

    @Override
    public CompletableFuture<Boolean> setUserBats(String username, List<BatWithId> bats) {
        String[] serialisedBats = new String[bats.size()];
        for(int i =0; i < bats.size(); i++) {
            serialisedBats[i] = bats.get(i).encode();
        }
        return cache.setUserBats(username, serialisedBats);
    }
}
