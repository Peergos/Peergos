package peergos.shared.storage;

import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.Multihash;

import java.util.*;
import java.util.concurrent.*;

public interface SpaceUsageProxy extends SpaceUsage {

    CompletableFuture<PaymentProperties> getPaymentProperties(Multihash targetServerId,
                                                              PublicKeyHash owner,
                                                              boolean newClientSecret,
                                                              byte[] signedTime);

    CompletableFuture<Long> getUsage(Multihash targetServerId, PublicKeyHash owner, byte[] signedTime);

    CompletableFuture<Long> getQuota(Multihash targetServerId, PublicKeyHash owner, byte[] signedTime);

    CompletableFuture<PaymentProperties> requestSpace(Multihash targetServerId, PublicKeyHash owner, byte[] signedRequest);

    CompletableFuture<Boolean> setWriterQuota(Multihash targetServerId, PublicKeyHash owner, byte[] signedRequest);

    CompletableFuture<List<WriterUsageInfo>> getWriterQuotas(Multihash targetServerId, PublicKeyHash owner, byte[] signedRequest);

    CompletableFuture<WriterUsageInfo> getWriterUsage(Multihash targetServerId, PublicKeyHash owner, PublicKeyHash writer, byte[] signedRequest);
}
