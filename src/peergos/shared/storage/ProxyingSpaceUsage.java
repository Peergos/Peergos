package peergos.shared.storage;

import peergos.shared.corenode.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.*;

import java.util.*;
import java.util.concurrent.*;

/** Implements a SpaceUsage that will proxy calls to the owner's Peergos instance
 *
 */
public class ProxyingSpaceUsage implements SpaceUsage {

    private final List<Cid> serverIds;
    private final CoreNode core;
    private final SpaceUsage local;
    private final SpaceUsageProxy p2p;

    public ProxyingSpaceUsage(List<Cid> serverIds, CoreNode core, SpaceUsage local, SpaceUsageProxy p2p) {
        this.serverIds = serverIds;
        this.core = core;
        this.local = local;
        this.p2p = p2p;
    }

    @Override
    public CompletableFuture<Long> getUsage(PublicKeyHash targetUser, byte[] signedTime) {
        return Proxy.redirectCall(core,
                serverIds,
                targetUser,
                () -> local.getUsage(targetUser, signedTime),
                targetServer -> p2p.getUsage(targetServer, targetUser, signedTime));
    }

    @Override
    public CompletableFuture<PaymentProperties> getPaymentProperties(PublicKeyHash owner, boolean newClientSecret, byte[] signedTime) {
        return Proxy.redirectCall(core,
                serverIds,
                owner,
                () -> local.getPaymentProperties(owner, newClientSecret, signedTime),
                targetServer -> p2p.getPaymentProperties(targetServer, owner, newClientSecret, signedTime));
    }

    @Override
    public CompletableFuture<Long> getQuota(PublicKeyHash owner, byte[] signedTime) {
        return Proxy.redirectCall(core,
                serverIds,
                owner,
                () -> local.getQuota(owner, signedTime),
                targetServer -> p2p.getQuota(targetServer, owner, signedTime));
    }

    @Override
    public CompletableFuture<PaymentProperties> requestQuota(PublicKeyHash owner, byte[] signedRequest) {
        return Proxy.redirectCall(core,
                serverIds,
                owner,
                () -> local.requestQuota(owner, signedRequest),
                targetServer -> p2p.requestSpace(targetServer, owner, signedRequest));
    }
}
