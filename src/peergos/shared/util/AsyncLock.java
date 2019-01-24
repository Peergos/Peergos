package peergos.shared.util;

import java.util.concurrent.*;
import java.util.function.*;

/** This class implements a lock that can be held across multiple dependent asynchronous tasks which return a new value
 * for the guarded object or to get the value after any pending updaters have completed
 *
 * @param <T>
 */
public class AsyncLock<T> {

    private CompletableFuture<T> queueHead;

    public AsyncLock(CompletableFuture<T> initialValue) {
        this.queueHead = initialValue;
    }

    /**
     *
     * @param updater
     * @return A future completed with the result from updater, or exceptionally completed on error
     */
    public synchronized CompletableFuture<T> runWithLock(Function<T, CompletionStage<T>> updater) {
        CompletableFuture<T> existing = queueHead;
        CompletableFuture<T> newHead = new CompletableFuture<>();
        this.queueHead = newHead;

        CompletableFuture<T> result = new CompletableFuture<>();
        existing.thenCompose(current -> updater.apply(current)
                .thenApply(res -> newHead.complete(res) && result.complete(res))
                .exceptionally(t -> newHead.complete(current) && result.completeExceptionally(t)));

        return result;
    }

    public synchronized CompletableFuture<T> getValue() {
        return runWithLock(v -> CompletableFuture.completedFuture(v));
    }
}
