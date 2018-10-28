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
    private final SocialNetworkProxy social;

    public ProxyingSocialNetwork(Multihash serverId, CoreNode core, SocialNetworkProxy social) {
        this.serverId = serverId;
        this.core = core;
        this.social = social;
    }

    @Override
    public CompletableFuture<Boolean> sendFollowRequest(PublicKeyHash targetUser, byte[] encryptedPermission) {
        return redirectCall(targetUser,
                () -> social.sendFollowRequest(targetUser, encryptedPermission),
                targetServer -> social.sendFollowRequest(targetServer, targetUser, encryptedPermission));
    }

    @Override
    public CompletableFuture<byte[]> getFollowRequests(PublicKeyHash owner) {
        return redirectCall(owner,
                () -> social.getFollowRequests(owner),
                targetServer -> social.getFollowRequests(targetServer, owner));
    }

    @Override
    public CompletableFuture<Boolean> removeFollowRequest(PublicKeyHash owner, byte[] signedRequest) {
        return redirectCall(owner,
                () -> social.removeFollowRequest(owner, signedRequest),
                targetServer -> social.removeFollowRequest(targetServer, owner, signedRequest));
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
