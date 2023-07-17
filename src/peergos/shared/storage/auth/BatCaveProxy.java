package peergos.shared.storage.auth;

import peergos.shared.io.ipfs.Multihash;

import java.util.*;
import java.util.concurrent.*;

/** This is used to store a mirror bat (or two during rotations) for each user.
 *
 */
public interface BatCaveProxy {

    CompletableFuture<List<BatWithId>> getUserBats(Multihash targetServerId, String username, byte[] auth);

    CompletableFuture<Boolean> addBat(Multihash targetServerId, String username, BatId id, Bat bat, byte[] auth);
}
