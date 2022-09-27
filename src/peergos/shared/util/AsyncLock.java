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

    public synchronized boolean isDone() {
        return queueHead.isDone();
    }

    public synchronized CompletableFuture<T> runWithLock(Function<T, CompletionStage<T>> processor) {
        return runWithLock(processor, () -> queueHead);
    }

    /**
     *
     * @param processor
     * @param updater a method to get a fresh value which is called if updater completes exceptionally
     * @return A future completed with the result from a computation, or exceptionally completed on error
     */
    public synchronized CompletableFuture<T> runWithLock(Function<T, CompletionStage<T>> processor, Supplier<CompletableFuture<T>> updater) {
        CompletableFuture<T> existing = queueHead;
        CompletableFuture<T> newHead = new CompletableFuture<>();
        this.queueHead = newHead;

        CompletableFuture<T> result = new CompletableFuture<>();
        existing.thenCompose(current -> processor.apply(current)
                .thenApply(res -> newHead.complete(res) && result.complete(res))
                .exceptionally(t -> {
                    updater.get()
                            .thenApply(res -> newHead.complete(res) && result.completeExceptionally(t))
                            .exceptionally(e -> newHead.complete(current) && result.completeExceptionally(e));
                    t.printStackTrace();
                    return true;
                }))
                .exceptionally(t -> {
                    // The initial supplier failed
                    result.completeExceptionally(t);
                    newHead.completeExceptionally(t);
                    return true;
                });

        return result;
    }

    public synchronized CompletableFuture<T> getValue() {
        return runWithLock(CompletableFuture::completedFuture);
    }

    @Override
    public String toString() {
        return queueHead.toString();
    }
}
