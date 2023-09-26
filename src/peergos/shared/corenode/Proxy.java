package peergos.shared.corenode;

import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.Multihash;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

public class Proxy {

    public static final <V> CompletableFuture<V> redirectCall(CoreNode core,
                                                              Multihash serverId,
                                                              PublicKeyHash ownerKey,
                                                              Supplier<CompletableFuture<V>> direct,
                                                              Function<Multihash, CompletableFuture<V>> proxied) {
        List<Multihash> storageIds = core.getStorageProviders(ownerKey);
        Multihash target = storageIds.get(0);
        if (target.equals(serverId)) { // don't proxy
            return direct.get();
        } else {
            return proxied.apply(target);
        }
    }
}
