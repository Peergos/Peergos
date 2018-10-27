package peergos.shared.mutable;

import peergos.shared.corenode.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.multihash.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

public class ProxyingMutablePointers implements MutablePointers {

    private final Multihash serverId;
    private final CoreNode core;
    private final MutablePointersProxy mutable;

    public ProxyingMutablePointers(Multihash serverId, CoreNode core, MutablePointersProxy mutable) {
        this.serverId = serverId;
        this.core = core;
        this.mutable = mutable;
    }

    @Override
    public CompletableFuture<Boolean> setPointer(PublicKeyHash owner, PublicKeyHash writer, byte[] writerSignedBtreeRootHash) {
        return redirectCall(writer,
                () -> mutable.setPointer(owner, writer, writerSignedBtreeRootHash),
                target -> mutable.setPointer(target, owner, writer, writerSignedBtreeRootHash));
    }

    @Override
    public CompletableFuture<Optional<byte[]>> getPointer(PublicKeyHash writer) {
        return redirectCall(writer,
                () -> mutable.getPointer(writer),
                target -> mutable.getPointer(target, writer));
    }

    public <V> CompletableFuture<V> redirectCall(PublicKeyHash writer, Supplier<CompletableFuture<V>> direct, Function<Multihash, CompletableFuture<V>> proxied) {
        return core.getUsername(writer)
                .thenCompose(owner -> core.getChain(owner)
                        .thenCompose(chain -> {
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
