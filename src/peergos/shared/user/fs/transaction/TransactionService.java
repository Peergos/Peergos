package peergos.shared.user.fs.transaction;

import jsinterop.annotations.JsMethod;
import peergos.shared.*;
import peergos.shared.crypto.*;
import peergos.shared.user.*;
import peergos.shared.util.*;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.*;
import java.util.stream.Collectors;

public interface TransactionService {

    SigningPrivateKeyAndPublicHash getSigner();

    @JsMethod
    /**
     *  Open a transaction or return an existing matching transaction
     */
    CompletableFuture<Either<Snapshot, FileUploadTransaction>> open(Snapshot version,
                                                                    Committer committer,
                                                                    Transaction transaction);

    @JsMethod
    CompletableFuture<Snapshot> close(Snapshot version, Committer committer, Transaction transaction);

    /**
     * Remove data associated with a transaction.
     * @param transaction
     * @return
     */
    CompletableFuture<Snapshot> clear(Snapshot version, Committer committer, Transaction transaction);

    CompletableFuture<Set<Transaction>> getOpenTransactions(Snapshot version);

    TransactionServiceImpl withNetwork(NetworkAccess net);

    default CompletableFuture<Snapshot> clearAndClose(Snapshot version, Committer committer, Transaction transaction) {
        return clear(version, committer, transaction)
                .thenCompose(s -> close(s, committer, transaction));
    }

    default CompletableFuture<Snapshot> clearAndClosePendingTransactions(Snapshot version, Committer committer, Predicate<Transaction> filter) {
        return getOpenTransactions(version)
                .thenCompose(openTransactions -> {
                    List<Transaction> toClose = openTransactions.stream()
                            .filter(filter)
                            .collect(Collectors.toList());
                    System.out.println("Open file upload transactions: " + openTransactions.size());
                    System.out.println("Stale file upload transactions: " + toClose.size());
                    return Futures.reduceAll(toClose, version,
                            (s, t) -> clearAndClose(s, committer, t),
                            (a, b) -> b);
                });
    }
}
