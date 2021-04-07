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
        return Proxy.redirectCall(core,
                serverId,
                targetUser,
                () -> local.getUsage(targetUser),
                targetServer -> p2p.getUsage(targetServer, targetUser));
    }

    @Override
    public CompletableFuture<PaymentProperties> getPaymentProperties(PublicKeyHash owner, boolean newClientSecret, byte[] signedTime) {
        return Proxy.redirectCall(core,
                serverId,
                owner,
                () -> local.getPaymentProperties(owner, newClientSecret, signedTime),
                targetServer -> p2p.getPaymentProperties(targetServer, owner, newClientSecret, signedTime));
    }

    @Override
    public CompletableFuture<Long> getQuota(PublicKeyHash owner, byte[] signedTime) {
        return Proxy.redirectCall(core,
                serverId,
                owner,
                () -> local.getQuota(owner, signedTime),
                targetServer -> p2p.getQuota(targetServer, owner, signedTime));
    }

    @Override
    public CompletableFuture<Boolean> requestQuota(PublicKeyHash owner, byte[] signedRequest) {
        return Proxy.redirectCall(core,
                serverId,
                owner,
                () -> local.requestQuota(owner, signedRequest),
                targetServer -> p2p.requestSpace(targetServer, owner, signedRequest));
    }
}
