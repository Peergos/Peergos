package peergos.shared.storage;

import peergos.shared.crypto.hash.*;

import java.util.concurrent.*;
import java.util.function.*;

public class IpfsTransaction {

    /** Run a series of operations under a transaction, ensuring that it is closed correctly
     *
     * @param owner
     * @param processor
     * @param ipfs
     * @param <V>
     * @return
     */
    public static <V> CompletableFuture<V> call(PublicKeyHash owner,
                                                Function<TransactionId, CompletableFuture<V>> processor,
                                                ContentAddressedStorage ipfs) {
        CompletableFuture<V> res = new CompletableFuture<>();
        ipfs.startTransaction(owner).thenCompose(tid -> processor.apply(tid)
                .thenCompose(v -> ipfs.closeTransaction(owner, tid)
                        .thenApply(x -> res.complete(v)))
                .exceptionally(t -> {
                    ipfs.closeTransaction(owner, tid)
                            .thenApply(x -> res.completeExceptionally(t))
                            .exceptionally(e -> res.completeExceptionally(e));
                    return false;
                })).exceptionally(e -> res.completeExceptionally(e));
        return res;
    }
}
