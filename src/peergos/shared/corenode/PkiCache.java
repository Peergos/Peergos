package peergos.shared.corenode;

import peergos.shared.crypto.hash.*;

import java.util.*;
import java.util.concurrent.*;

public interface PkiCache {

    CompletableFuture<List<UserPublicKeyLink>> getChain(String username);

    CompletableFuture<Boolean> setChain(String username, List<UserPublicKeyLink> chain);

    CompletableFuture<String> getUsername(PublicKeyHash key);
}
