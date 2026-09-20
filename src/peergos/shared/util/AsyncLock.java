package peergos.shared.util;

import java.util.concurrent.*;
import java.util.function.*;

/** This class implements a lock that can be held across multiple dependent asynchronous tasks which return a new value
 * for the guarded object or to get the value after any pending updaters have completed
 *
 * There are two independent queues: writes, which are serialised with each other, and read only
 * retrievals, which are serialised with each other, but never wait for a write. This means a long
 * running write, like an upload, doesn't block navigating or listing.
 *
 * @param <T>
 */
public class AsyncLock<T> {

    private CompletableFuture<T> queueHead;
    private CompletableFuture<T> readHead;
    public AsyncLock(CompletableFuture<T> initialValue) {
        this.queueHead = initialValue;
        this.readHead = initialValue;
    }

    public synchronized boolean isDone() {
        return queueHead.isDone();
    }

    /** Recovers from a failure by retaining the previous value, so this is only usable where that is
     *  still correct afterwards. Anything backed by the network should supply an updater that
     *  re-retrieves the value instead.
     */
    public synchronized CompletableFuture<T> runWithLock(Function<T, CompletionStage<T>> processor) {
        // capture the previous head: a supplier reading the field would return this call's head, or a
        // later one that depends on it, and a future can't be completed from a callback on itself
        CompletableFuture<T> previous = queueHead;
        return runWithLock(processor, () -> previous);
    }

    /** The head installed here is always completed, however the update fails, because every later
     *  operation on this lock queues behind it.
     *
     * @param processor
     * @param updater a method to get a fresh value, called if the processor fails, or if the operation
     *                this one queued behind did
     * @return A future completed with the result from a computation, or exceptionally completed on error
     */
    public synchronized CompletableFuture<T> runWithLock(Function<T, CompletionStage<T>> processor,
                                                         Supplier<CompletableFuture<T>> updater) {
        CompletableFuture<T> existing = queueHead;
        CompletableFuture<T> newHead = new CompletableFuture<>();
        this.queueHead = newHead;

        CompletableFuture<T> result = new CompletableFuture<>();
        existing.thenCompose(current -> processor.apply(current)
                .thenApply(res -> {
                    publishToReaders(res);
                    newHead.complete(res);
                    return result.complete(res);
                })
                .exceptionally(t -> {
                    fresh(updater)
                            .thenApply(res -> {
                                newHead.complete(res);
                                return result.completeExceptionally(t);
                            })
                            .exceptionally(e -> {
                                newHead.complete(current);
                                return result.completeExceptionally(e);
                            });
                    t.printStackTrace();
                    return true;
                }))
                .exceptionally(t -> {
                    // The previous queueHead failed - use updater to recover
                    // so subsequent operations aren't permanently poisoned
                    result.completeExceptionally(t);
                    fresh(updater)
                            .thenApply(newHead::complete)
                            .exceptionally(e -> newHead.completeExceptionally(e));
                    return true;
                });

        return result;
    }

    /** An updater that throws, or hands back nothing, is a failed recovery rather than a reason to
     *  leave the queue head incomplete.
     */
    private CompletableFuture<T> fresh(Supplier<CompletableFuture<T>> updater) {
        try {
            CompletableFuture<T> value = updater.get();
            return value != null ? value : Futures.errored(new IllegalStateException("No value from updater"));
        } catch (Throwable t) {
            return Futures.errored(t);
        }
    }

    /** Run a read only task which never waits for an in-flight write. Read only tasks are still
     *  serialised with each other, which bounds the concurrent retrievals of the same value.
     *
     * @param processor
     * @param updater a method to get a fresh value which is called if the previous read left no usable value
     * @return A future completed with the result from a computation, or exceptionally completed on error
     */
    public synchronized CompletableFuture<T> runWithReadLock(Function<T, CompletionStage<T>> processor,
                                                             Supplier<CompletableFuture<T>> updater) {
        CompletableFuture<T> existing = readHead;
        CompletableFuture<T> newHead = new CompletableFuture<>();
        this.readHead = newHead;

        CompletableFuture<T> result = new CompletableFuture<>();
        existing.thenCompose(current -> processor.apply(current)
                .thenApply(res -> {
                    newHead.complete(res);
                    return result.complete(res);
                })
                .exceptionally(t -> {
                    // a failed read must not poison the queue, retain the previous value
                    result.completeExceptionally(t);
                    return newHead.complete(current);
                }))
                .exceptionally(t -> {
                    // The previous readHead failed - use updater to recover
                    result.completeExceptionally(t);
                    fresh(updater)
                            .thenApply(newHead::complete)
                            .exceptionally(e -> newHead.completeExceptionally(e));
                    return true;
                });

        return result;
    }

    /** Make the result of a write visible to subsequent reads, unless a read is already in flight,
     *  which will retrieve a current value itself.
     */
    private synchronized void publishToReaders(T value) {
        if (readHead.isDone())
            readHead = CompletableFuture.completedFuture(value);
    }

    public synchronized CompletableFuture<T> getValue() {
        return runWithLock(CompletableFuture::completedFuture);
    }

    @Override
    public String toString() {
        return queueHead.toString();
    }
}
