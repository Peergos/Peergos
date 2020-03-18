package peergos.shared.storage;

import peergos.shared.corenode.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.multihash.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** Implements a SpaceUsage that will proxy calls to the owner's Peergos instance
 *
 */
public class ProxyingSpaceUsage implements SpaceUsage {

    private final Multihash serverId;
    private final CoreNode core;
    private final SpaceUsage local;
    private final SpaceUsageProxy p2p;

    public ProxyingSpaceUsage(Multihash serverId, CoreNode core, SpaceUsage local, SpaceUsageProxy p2p) {
        this.serverId = serverId;
        this.core = core;
        this.local = local;
        this.p2p = p2p;
    }

    @Override
    public CompletableFuture<Long> getUsage(PublicKeyHash targetUser) {
        return redirectCall(targetUser,
                () -> local.getUsage(targetUser),
                targetServer -> p2p.getUsage(targetServer, targetUser));
    }

    @Override
    public CompletableFuture<Long> getQuota(PublicKeyHash owner, byte[] signedTime) {
        return redirectCall(owner,
                () -> local.getQuota(owner, signedTime),
                targetServer -> p2p.getQuota(targetServer, owner, signedTime));
    }

    @Override
    public CompletableFuture<Boolean> requestQuota(PublicKeyHash owner, byte[] signedRequest) {
        return redirectCall(owner,
                () -> local.requestQuota(owner, signedRequest),
                targetServer -> p2p.requestSpace(targetServer, owner, signedRequest));
    }

    public <V> CompletableFuture<V> redirectCall(PublicKeyHash writer, Supplier<CompletableFuture<V>> direct, Function<Multihash, CompletableFuture<V>> proxied) {
        return core.getUsername(writer)
                .thenCompose(owner -> core.getChain(owner)
                        .thenCompose(chain -> {
                            if (chain.isEmpty()) {
                                // This happens during sign-up, before we have a chain yet
                                return direct.get();
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
