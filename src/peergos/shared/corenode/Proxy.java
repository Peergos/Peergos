package peergos.shared.corenode;

import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

public class Proxy {
    public static final Cid ZERO = Cid.decode("zdvgq1QytJnbSZiAGiduxkd7hnhdnpHqBSfYGiCyG1YQjLEij");

    public static final <V> CompletableFuture<V> redirectCall(CoreNode core,
                                                              List<Cid> serverIds,
                                                              PublicKeyHash ownerKey,
                                                              Supplier<CompletableFuture<V>> direct,
                                                              Function<Multihash, CompletableFuture<V>> proxied) {
        Multihash target = homeServer(core, ownerKey);
        if (isUs(serverIds, target))
            return direct.get();
        return proxyTo(core, target, proxied);
    }

    /** A read for a user we hold a mirror of, which is served from our own copy before their home server.
     *
     *  We only have a full copy of a user's data if we have given them quota, and reads of immutable
     *  blocks can't go stale, so this both saves a round trip and keeps a mirrored user readable while
     *  their home server is unreachable.
     */
    public static final <V> CompletableFuture<V> redirectRead(CoreNode core,
                                                              List<Cid> serverIds,
                                                              PublicKeyHash ownerKey,
                                                              Supplier<CompletableFuture<V>> direct,
                                                              Function<Multihash, CompletableFuture<V>> proxied,
                                                              Function<PublicKeyHash, Boolean> weMirror,
                                                              Function<V, Boolean> isPresent) {
        Multihash target = homeServer(core, ownerKey);
        if (isUs(serverIds, target))
            return direct.get();
        if (! weMirror.apply(ownerKey))
            return proxyTo(core, target, proxied);
        return Futures.asyncExceptionally(() -> direct.get()
                        .thenCompose(res -> isPresent.apply(res) ?
                                Futures.of(res) :
                                proxyTo(core, target, proxied)),
                t -> proxyTo(core, target, proxied));
    }

    /** A read of mutable state which has to come from the user's home server, falling back to our own
     *  mirror of it only when that server can't be reached.
     */
    public static final <V> CompletableFuture<V> redirectCallWithMirrorFallback(CoreNode core,
                                                                                List<Cid> serverIds,
                                                                                PublicKeyHash ownerKey,
                                                                                Supplier<CompletableFuture<V>> direct,
                                                                                Function<Multihash, CompletableFuture<V>> proxied,
                                                                                Function<PublicKeyHash, Boolean> weMirror) {
        Multihash target = homeServer(core, ownerKey);
        if (isUs(serverIds, target))
            return direct.get();
        return Futures.asyncExceptionally(() -> proxyTo(core, target, proxied),
                t -> weMirror.apply(ownerKey) ? direct.get() : Futures.errored(t));
    }

    private static Multihash homeServer(CoreNode core, PublicKeyHash ownerKey) {
        List<Multihash> storageIds = core.getStorageProviders(ownerKey);
        if (storageIds.isEmpty())
            throw new IllegalStateException("Unable to find home server to send request to for " + ownerKey);
        return storageIds.get(0);
    }

    private static boolean isUs(List<Cid> serverIds, Multihash target) {
        return serverIds.stream()
                .map(Cid::bareMultihash)
                .anyMatch(c -> c.equals(target.bareMultihash()))
                || target.equals(ZERO); // signup error assume local user
    }

    private static <V> CompletableFuture<V> proxyTo(CoreNode core,
                                                    Multihash target,
                                                    Function<Multihash, CompletableFuture<V>> proxied) {
        return Futures.asyncExceptionally(() -> proxied.apply(target),
                t -> {
                    // check if the server has rotated their identity
                    Multihash newServerIdentity = core.getNextServerId(target.bareMultihash()).join().get();
                    return proxied.apply(new Cid(1, Cid.Codec.LibP2pKey, newServerIdentity.type, newServerIdentity.getHash()));
                });
    }
}
