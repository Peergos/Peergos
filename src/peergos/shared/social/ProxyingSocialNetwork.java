package peergos.shared.social;

import peergos.shared.corenode.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.mutable.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

public class ProxyingSocialNetwork implements SocialNetwork {

    private final Multihash serverId;
    private final CoreNode core;
    private final SocialNetwork local;
    private final SocialNetworkProxy p2p;

    public ProxyingSocialNetwork(Multihash serverId, CoreNode core, SocialNetwork local, SocialNetworkProxy p2p) {
        this.serverId = serverId;
        this.core = core;
        this.local = local;
        this.p2p = p2p;
    }

    @Override
    public CompletableFuture<Boolean> sendFollowRequest(PublicKeyHash targetUser, byte[] encryptedPermission) {
        return redirectCall(targetUser,
                () -> local.sendFollowRequest(targetUser, encryptedPermission),
                targetServer -> p2p.sendFollowRequest(targetServer, targetUser, encryptedPermission));
    }

    @Override
    public CompletableFuture<byte[]> getFollowRequests(PublicKeyHash owner) {
        return redirectCall(owner,
                () -> local.getFollowRequests(owner),
                targetServer -> p2p.getFollowRequests(targetServer, owner));
    }

    @Override
    public CompletableFuture<Boolean> removeFollowRequest(PublicKeyHash owner, byte[] signedRequest) {
        return redirectCall(owner,
                () -> local.removeFollowRequest(owner, signedRequest),
                targetServer -> p2p.removeFollowRequest(targetServer, owner, signedRequest));
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
