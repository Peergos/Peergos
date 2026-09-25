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
    public CompletableFuture<Long> getUsage(PublicKeyHash targetUser, byte[] signedTime, boolean localUsage) {
        if (localUsage)
            return local.getUsage(targetUser, signedTime, localUsage);
        return Proxy.redirectCall(core,
                serverIds,
                targetUser,
                () -> local.getUsage(targetUser, signedTime, localUsage),
                targetServer -> p2p.getUsage(targetServer, targetUser, signedTime));
    }

    @Override
    public CompletableFuture<PaymentProperties> getPaymentProperties(PublicKeyHash owner, boolean newClientSecret, byte[] signedTime) {
        return local.getPaymentProperties(owner, newClientSecret, signedTime);
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
    public CompletableFuture<PaymentProperties> requestQuota(PublicKeyHash owner, byte[] signedRequest, long usage) {
        return local.requestQuota(owner, signedRequest, usage);
    }

    @Override
    public CompletableFuture<Boolean> setWriterQuota(PublicKeyHash owner, byte[] signedRequest) {
        return Proxy.redirectCall(core,
                serverIds,
                owner,
                () -> local.setWriterQuota(owner, signedRequest),
                targetServer -> p2p.setWriterQuota(targetServer, owner, signedRequest));
    }

    @Override
    public CompletableFuture<List<WriterUsageInfo>> getWriterQuotas(PublicKeyHash owner, byte[] signedRequest) {
        return Proxy.redirectCall(core,
                serverIds,
                owner,
                () -> local.getWriterQuotas(owner, signedRequest),
                targetServer -> p2p.getWriterQuotas(targetServer, owner, signedRequest));
    }

    @Override
    public CompletableFuture<WriterUsageInfo> getWriterUsage(PublicKeyHash owner, PublicKeyHash writer, byte[] signedRequest) {
        return Proxy.redirectCall(core,
                serverIds,
                owner,
                () -> local.getWriterUsage(owner, writer, signedRequest),
                targetServer -> p2p.getWriterUsage(targetServer, owner, writer, signedRequest));
    }
}
