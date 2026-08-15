package peergos.shared.util;

import java.util.*;
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
    // the most recent value the write side settled on, which can hold local state that has not been
    // committed anywhere else yet
    private Optional<T> lastValue = Optional.empty();

    public AsyncLock(CompletableFuture<T> initialValue) {
        this.queueHead = initialValue;
        this.readHead = initialValue;
        initialValue.thenAccept(this::record);
    }

    private synchronized void record(T value) {
        lastValue = Optional.of(value);
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
    public synchronized CompletableFuture<T> runWithLock(Function<T, CompletionStage<T>> processor,
                                                         Supplier<CompletableFuture<T>> updater) {
        CompletableFuture<T> existing = queueHead;
        CompletableFuture<T> newHead = new CompletableFuture<>();
        this.queueHead = newHead;

        CompletableFuture<T> result = new CompletableFuture<>();
        existing.thenCompose(current -> processor.apply(current)
                .thenApply(res -> {
                    record(res);
                    publishToReaders(res);
                    newHead.complete(res);
                    return result.complete(res);
                })
                .exceptionally(t -> {
                    updater.get()
                            .thenApply(res -> {
                                record(res);
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
                    updater.get()
                            .thenApply(newHead::complete)
                            .exceptionally(e -> newHead.completeExceptionally(e));
                    return true;
                });

        return result;
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
                    updater.get()
                            .thenApply(newHead::complete)
                            .exceptionally(e -> newHead.completeExceptionally(e));
                    return true;
                });

        return result;
    }

    /** Update the value the next write starts from, if no write is in flight. The merge function is
     *  given the current value and returns the value to keep, so a retrieval that completed after a
     *  write can't move the value backwards.
     */
    public synchronized void updateIfIdle(Function<T, T> merge) {
        if (queueHead.isDone() && ! queueHead.isCompletedExceptionally() && lastValue.isPresent()) {
            T merged = merge.apply(lastValue.get());
            queueHead = CompletableFuture.completedFuture(merged);
            record(merged);
        }
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
