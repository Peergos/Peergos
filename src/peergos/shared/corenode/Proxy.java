package peergos.shared.corenode;

import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

public class Proxy {

    public static final <V> CompletableFuture<V> redirectCall(CoreNode core,
                                                              List<Cid> serverIds,
                                                              PublicKeyHash ownerKey,
                                                              Supplier<CompletableFuture<V>> direct,
                                                              Function<Multihash, CompletableFuture<V>> proxied) {
        List<Multihash> storageIds = core.getStorageProviders(ownerKey);
        if (storageIds.isEmpty())
            throw new IllegalStateException("Unable to find home server to send request to for " + ownerKey);
        Multihash target = storageIds.get(0);
        if (serverIds.stream()
                .map(Cid::bareMultihash)
                .anyMatch(c -> c.equals(target.bareMultihash()))) { // don't proxy
            return direct.get();
        } else {
            return Futures.asyncExceptionally(() -> proxied.apply(target),
                    t -> {
                        // check if the server has rotated their identity
                        Multihash newServerIdentity = core.getNextServerId(target.bareMultihash()).join().get();
                        return proxied.apply(new Cid(1, Cid.Codec.LibP2pKey, newServerIdentity.type, newServerIdentity.getHash()));
                    });
        }
    }
}
