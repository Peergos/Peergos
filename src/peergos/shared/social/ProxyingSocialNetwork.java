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
        return Proxy.redirectCall(core,
                serverId,
                targetUser,
                () -> local.sendFollowRequest(targetUser, encryptedPermission),
                targetServer -> p2p.sendFollowRequest(targetServer, targetUser, encryptedPermission));
    }

    @Override
    public CompletableFuture<byte[]> getFollowRequests(PublicKeyHash owner, byte[] signedTime) {
        return Proxy.redirectCall(core,
                serverId,
                owner,
                () -> local.getFollowRequests(owner, signedTime),
                targetServer -> p2p.getFollowRequests(targetServer, owner, signedTime));
    }

    @Override
    public CompletableFuture<Boolean> removeFollowRequest(PublicKeyHash owner, byte[] signedRequest) {
        return Proxy.redirectCall(core,
                serverId,
                owner,
                () -> local.removeFollowRequest(owner, signedRequest),
                targetServer -> p2p.removeFollowRequest(targetServer, owner, signedRequest));
    }
}
