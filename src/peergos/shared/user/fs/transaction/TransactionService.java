package peergos.shared.user.fs.transaction;

import jsinterop.annotations.JsMethod;
import peergos.shared.crypto.*;
import peergos.shared.user.*;
import peergos.shared.util.Futures;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

public interface TransactionService {

    SigningPrivateKeyAndPublicHash getSigner();

    @JsMethod
    CompletableFuture<Snapshot> open(Snapshot version, Committer committer, Transaction transaction);

    @JsMethod
    CompletableFuture<Snapshot> close(Snapshot version, Committer committer, Transaction transaction);

    /**
     * Remove data associated with a transaction.
     * @param transaction
     * @return
     */
    CompletableFuture<Snapshot> clear(Snapshot version, Committer committer, Transaction transaction);

    CompletableFuture<Set<Transaction>> getOpenTransactions(Snapshot version);

    default CompletableFuture<Snapshot> clearAndClose(Snapshot version, Committer committer, Transaction transaction) {
        return clear(version, committer, transaction)
                .thenCompose(s -> close(s, committer, transaction));
    }

    default CompletableFuture<Snapshot> clearAndClosePendingTransactions(Snapshot version, Committer committer) {

        return getOpenTransactions(version)
                .thenCompose(openTransactions -> {
                    List<Transaction> toClose = openTransactions.stream()
                            .filter(e -> false) // TODO
                            .collect(Collectors.toList());

                    return Futures.reduceAll(toClose, version,
                            (s, t) -> clearAndClose(version, committer, t),
                            (a, b) -> b);
                });
    }
}
