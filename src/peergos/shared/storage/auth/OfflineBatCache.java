package peergos.shared.storage.auth;

import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;

/** Offline bat cache used by mobile apps
 *
 */
public class OfflineBatCache implements BatCave {

    private final BatCave target;
    private final BatCache cache;

    public OfflineBatCache(BatCave target, BatCache cache) {
        this.target = target;
        this.cache = cache;
    }

    @Override
    public Optional<Bat> getBat(BatId id) {
        return target.getBat(id);
    }

    @Override
    public CompletableFuture<List<BatWithId>> getUserBats(String username, byte[] auth) {
        return Futures.asyncExceptionally(
                () -> target.getUserBats(username, auth).thenApply(bats -> {
                    cache.setUserBats(username, bats);
                    return bats;
                }),
                t -> cache.getUserBats(username));
    }

    @Override
    public CompletableFuture<Boolean> addBat(String username, BatId id, Bat bat, byte[] auth) {
        return target.addBat(username, id, bat, auth).thenApply(res -> {
            getUserBats(username, (byte[])null); // update cache
            return res;
        });
    }
}
