package peergos.shared.user.fs.transaction;

import jsinterop.annotations.JsMethod;
import peergos.shared.util.Futures;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

public interface TransactionService {

    @JsMethod
    CompletableFuture<Boolean> open(Transaction transaction);

    @JsMethod
    CompletableFuture<Boolean> close(Transaction transaction);

    /**
     * Remove data associated with a transaction.
     * @param transaction
     * @return
     */
    CompletableFuture<Boolean> clear(Transaction transaction);

    CompletableFuture<Set<Transaction>> getOpenTransactions();

    default CompletableFuture<Boolean> clearAndClose(Transaction transaction) {
        return clear(transaction).thenCompose(e -> close(transaction));
    }

    default CompletableFuture<Boolean> clearAndClosePendingTransactions() {

        return getOpenTransactions().thenCompose(openTransactions -> {
            List<CompletableFuture<Boolean>> futures = openTransactions.stream()
                    .filter(e -> false) // TODO
                    .map(this::clearAndClose)
                    .collect(Collectors.toList());

            return Futures.combineAll(futures).thenApply(e -> true);
        });
    }
}
