package peergos.shared.storage.auth;

import peergos.shared.crypto.symmetric.*;

import java.util.*;
import java.util.concurrent.*;

public interface EncryptedBatCache {

    CompletableFuture<List<BatWithId>> getUserBats(String username, SymmetricKey loginRoot);

    CompletableFuture<Boolean> setUserBats(String username, List<BatWithId> bats, SymmetricKey loginRoot);
}
