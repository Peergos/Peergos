package peergos.shared.storage.auth;

import java.util.*;
import java.util.concurrent.*;

public interface BatCache {

    CompletableFuture<List<BatWithId>> getUserBats(String username);

    CompletableFuture<Boolean> setUserBats(String username, List<BatWithId> bats);
}
