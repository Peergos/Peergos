package peergos.shared.corenode;

import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.multihash.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

public class Proxy {

    public static final <V> CompletableFuture<V> redirectCall(CoreNode core,
                                                              Multihash serverId,
                                                              PublicKeyHash ownerKey,
                                                              Supplier<CompletableFuture<V>> direct,
                                                              Function<Multihash, CompletableFuture<V>> proxied) {
        return core.getUsername(ownerKey)
                .thenCompose(owner -> core.getChain(owner)
                        .thenCompose(chain -> {
                            if (chain.isEmpty()) {
                                throw new IllegalStateException("Attempt to redirect call for non existent user!");
                            }
                            List<Multihash> storageIds = chain.get(chain.size() - 1).claim.storageProviders;
                            Multihash target = storageIds.get(0);
                            if (target.equals(serverId)) { // don't proxy
                                return direct.get();
                            } else {
                                return proxied.apply(target);
                            }
                        }));

    }
}
