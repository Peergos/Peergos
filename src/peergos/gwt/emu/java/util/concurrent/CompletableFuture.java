package java.util.concurrent;

import jsinterop.annotations.*;

import java.util.*;
import java.util.function.*;

/** Emulation of CompletableFuture
 *
 */
public class CompletableFuture<T> implements Future<T>, CompletionStage<T> {

    public static <T> CompletableFuture<T> completedFuture(T value) {
        return new CompletableFuture<T>(value);
    }

    // holders for all the possible consumers of this future
    private final List<Consumer<? super T>> consumers = new ArrayList<>();
    private final List<CompletableFuture<Void>> consumeFutures = new ArrayList<>();
    private final List<Function<? super T, ? extends Object>> applies = new ArrayList<>();
    private final List<CompletableFuture> applyFutures = new ArrayList<>();
    private final List<Function<? super T, ? extends CompletionStage<? extends Object>>> composers = new ArrayList<>();
    private final List<CompletableFuture<? extends Object>> composeFutures = new ArrayList<>();
    private final List<Function<? super Throwable, ? extends T>> errors = new ArrayList<>();
    private final List<CompletableFuture<T>> errorFutures = new ArrayList<>();
    private T value;
    private Throwable reason;
    private boolean isDone;

    private CompletableFuture(T value, Throwable err, boolean isDone) {
        this.value = value;
        this.reason = err;
        this.isDone = isDone;
    }

    public CompletableFuture() {
        this(null, null, false);
    }

    private CompletableFuture(T value) {
        this(value, null, true);
    }

    private CompletableFuture(Throwable err) {
        this(null, err, true);
    }

    public T join() {
        throw new IllegalStateException("Illegal synchronous call!");
    }

    @Override
    @JsMethod
    public <U> CompletableFuture<U> thenApply(Function<? super T, ? extends U> fn) {
        CompletableFuture<U> fut = new CompletableFuture<>();
        if (isDone()) {
            if (reason != null) {
                fut.completeExceptionally(reason);
            } else {
                try {
                    fut.complete(fn.apply(value));
                } catch(Throwable t) {
                    fut.completeExceptionally(t);
                }
            }
        } else {
            applyFutures.add(fut);
            applies.add(fn);
        }
        return fut;
    }

    @Override
    public CompletableFuture<Void> thenAccept(Consumer<? super T> action) {
        CompletableFuture<Void> fut = new CompletableFuture<>();
        if (isDone()) {
            if (reason != null) {
                fut.completeExceptionally(reason);
            } else {
                try {
                    action.accept(value);
                    fut.complete(null);
                } catch(Throwable t) {
                    fut.completeExceptionally(t);
                }
            }
        } else {
            consumeFutures.add(fut);
            consumers.add(action);
        }
        return fut;
    }

    @Override
    public <U, V> CompletableFuture<V> thenCombine(CompletionStage<? extends U> other, BiFunction<? super T, ? super U, ? extends V> fn) {
        throw new IllegalStateException("Unimplemented!");
    }

    @Override
    @JsMethod
    public <U> CompletableFuture<U> thenCompose(Function<? super T, ? extends CompletionStage<U>> fn) {
        CompletableFuture<U> fut = new CompletableFuture<>();
        if (isDone()) {
            if (reason != null) {
                fut.completeExceptionally(reason);
            } else {
                try {
                    fn.apply(value)
                            .thenAccept(fut::complete)
                            .exceptionally(t -> {
                                fut.completeExceptionally(t);
                                return null;
                            });
                } catch(Throwable t) {
                    fut.completeExceptionally(t);
                }
            }
        } else {
            composeFutures.add(fut);
            composers.add(fn);
        }
        return fut;
    }

    @JsMethod
    public boolean complete(T value) {
        this.value = value;
        this.isDone = true;
        for (int i = 0; i < applies.size(); i++) {
            Function<? super T, ? extends Object> function = applies.get(i);
            CompletableFuture future = applyFutures.get(i);
            try {
                future.complete(function.apply(value));
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        }
        for (int i = 0; i < consumers.size(); i++) {
            Consumer<? super T> function = consumers.get(i);
            CompletableFuture<Void> future = consumeFutures.get(i);
            try {
                function.accept(value);
                future.complete(null);
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        }
        for (int i = 0; i < composers.size(); i++) {
            Function<? super T, ? extends CompletionStage> function = composers.get(i);
            CompletableFuture future = composeFutures.get(i);
            try {
                function.apply(value)
                        .thenAccept(val -> future.complete(val))
                        .exceptionally(t -> {
                            future.completeExceptionally((Throwable) t);
                            return null;
                        });
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        }
        for (int i = 0; i < errors.size(); i++) {
            CompletableFuture<T> future = errorFutures.get(i);
            Function<? super Throwable, ? extends T> function = errors.get(i);
            try {
                future.complete(value);
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        }
        errors.clear();
        errorFutures.clear();
        composers.clear();
        composeFutures.clear();
        consumers.clear();
        consumeFutures.clear();
        applies.clear();
        applyFutures.clear();
        return true;
    }

    @JsMethod
    public boolean completeExceptionally(Throwable err) {
        this.reason = err;
        this.isDone = true;
        ForkJoinPool.commonPool().execute(() -> {
            for (int i = 0; i < applies.size(); i++) {
                CompletableFuture future = applyFutures.get(i);
                try {
                    future.completeExceptionally(err);
                } catch (Throwable t) {
                    future.completeExceptionally(t);
                }
            }
            for (int i = 0; i < consumers.size(); i++) {
                CompletableFuture<Void> future = consumeFutures.get(i);
                try {
                    future.completeExceptionally(err);
                } catch (Throwable t) {
                    future.completeExceptionally(t);
                }
            }
            for (int i = 0; i < composers.size(); i++) {
                CompletableFuture future = composeFutures.get(i);
                try {
                    future.completeExceptionally(err);
                } catch (Throwable t) {
                    future.completeExceptionally(t);
                }
            }
            for (int i = 0; i < errors.size(); i++) {
                CompletableFuture<T> future = errorFutures.get(i);
                Function<? super Throwable, ? extends T> function = errors.get(i);
                try {
                    future.complete(function.apply(err));
                } catch (Throwable t) {
                    future.completeExceptionally(t);
                }
            }
            errors.clear();
            errorFutures.clear();
            composers.clear();
            composeFutures.clear();
            consumers.clear();
            consumeFutures.clear();
            applies.clear();
            applyFutures.clear();
        });
        return true;
    }

    @Override
    public boolean isDone() {
        return isDone;
    }

    @Override
    public boolean isCancelled() {
        throw new IllegalStateException("Unimplemented!");
    }

    @Override
    public boolean cancel(boolean cancel) {
        throw new IllegalStateException("Unimplemented!");
    }

    @Override
    public CompletableFuture<T> toCompletableFuture() {
        return this;
    }

    @Override
    @JsMethod
    public CompletableFuture<T> exceptionally(Function<Throwable, ? extends T> handler) {
        if (isDone()) {
            if (isCompletedExceptionally()) {
                try {
                    return CompletableFuture.completedFuture(handler.apply(reason));
                } catch (Throwable t) {
                    CompletableFuture<T> fut = new CompletableFuture<>();
                    fut.completeExceptionally(t);
                    return fut;
                }
            } else {
                // no exception occured just return the already completed future
                return this;
            }
        } else {
            CompletableFuture<T> fut = new CompletableFuture<>();
            errorFutures.add(fut);
            errors.add(handler);
            return fut;
        }
    }

    public static <U> java.util.concurrent.CompletableFuture<U> supplyAsync(Supplier<U> var0, Executor var1) {
        throw new IllegalStateException("Not implemented!");
    }

    @Override
    public T get(long t, TimeUnit unit) {
        throw new IllegalStateException("Not possible to call synchronous get() in JS!");
    }

    @Override
    public T get() throws InterruptedException, ExecutionException {
        throw new IllegalStateException("Not possible to call synchronous get() in JS!");
    }

    public <U> CompletionStage<U> thenApplyAsync(Function<? super T,? extends U> fn) {     throw new IllegalStateException("Unimplemented!");   }

    public <U> CompletionStage<U> thenApplyAsync(Function<? super T,? extends U> fn, Executor executor) {     throw new IllegalStateException("Unimplemented!");   }

    public CompletionStage<Void> thenAcceptAsync(Consumer<? super T> action) {     throw new IllegalStateException("Unimplemented!");   }

    public CompletionStage<Void> thenAcceptAsync(Consumer<? super T> action, Executor executor) {     throw new IllegalStateException("Unimplemented!");   }

    public CompletionStage<Void> thenRun(Runnable action) {     throw new IllegalStateException("Unimplemented!");   }

    public CompletionStage<Void> thenRunAsync(Runnable action) {     throw new IllegalStateException("Unimplemented!");   }

    public CompletionStage<Void> thenRunAsync(Runnable action, Executor executor) {     throw new IllegalStateException("Unimplemented!");   }

    public <U,V> CompletionStage<V> thenCombineAsync(CompletionStage<? extends U> other, BiFunction<? super T,? super U,? extends V> fn) {     throw new IllegalStateException("Unimplemented!");   }

    public <U,V> CompletionStage<V> thenCombineAsync(CompletionStage<? extends U> other, BiFunction<? super T,? super U,? extends V> fn, Executor executor) {     throw new IllegalStateException("Unimplemented!");   }

    public <U> CompletionStage<Void> thenAcceptBoth(CompletionStage<? extends U> other, BiConsumer<? super T, ? super U> action) {     throw new IllegalStateException("Unimplemented!");   }

    public <U> CompletionStage<Void> thenAcceptBothAsync(CompletionStage<? extends U> other, BiConsumer<? super T, ? super U> action) {     throw new IllegalStateException("Unimplemented!");   }

    public <U> CompletionStage<Void> thenAcceptBothAsync(CompletionStage<? extends U> other, BiConsumer<? super T, ? super U> action, Executor executor) {     throw new IllegalStateException("Unimplemented!");   }

    public CompletionStage<Void> runAfterBoth(CompletionStage<?> other, Runnable action) {     throw new IllegalStateException("Unimplemented!");   }

    public CompletionStage<Void> runAfterBothAsync(CompletionStage<?> other, Runnable action) {     throw new IllegalStateException("Unimplemented!");   }

    public CompletionStage<Void> runAfterBothAsync(CompletionStage<?> other, Runnable action, Executor executor) {     throw new IllegalStateException("Unimplemented!");   }

    public <U> CompletionStage<U> applyToEither(CompletionStage<? extends T> other, Function<? super T, U> fn) {     throw new IllegalStateException("Unimplemented!");   }

    public <U> CompletionStage<U> applyToEitherAsync(CompletionStage<? extends T> other, Function<? super T, U> fn) {     throw new IllegalStateException("Unimplemented!");   }

    public <U> CompletionStage<U> applyToEitherAsync(CompletionStage<? extends T> other, Function<? super T, U> fn, Executor executor) {     throw new IllegalStateException("Unimplemented!");   }

    public CompletionStage<Void> acceptEither(CompletionStage<? extends T> other, Consumer<? super T> action) {     throw new IllegalStateException("Unimplemented!");   }

    public CompletionStage<Void> acceptEitherAsync(CompletionStage<? extends T> other, Consumer<? super T> action) {     throw new IllegalStateException("Unimplemented!");   }

    public CompletionStage<Void> acceptEitherAsync(CompletionStage<? extends T> other, Consumer<? super T> action, Executor executor) {     throw new IllegalStateException("Unimplemented!");   }

    public CompletionStage<Void> runAfterEither(CompletionStage<?> other, Runnable action) {     throw new IllegalStateException("Unimplemented!");   }
    public CompletionStage<Void> runAfterEitherAsync(CompletionStage<?> other, Runnable action) {     throw new IllegalStateException("Unimplemented!");   }

    public CompletionStage<Void> runAfterEitherAsync(CompletionStage<?> other, Runnable action, Executor executor) {     throw new IllegalStateException("Unimplemented!");   }

    public <U> CompletionStage<U> thenComposeAsync(Function<? super T, ? extends CompletionStage<U>> fn) {     throw new IllegalStateException("Unimplemented!");   }

    public <U> CompletionStage<U> thenComposeAsync(Function<? super T, ? extends CompletionStage<U>> fn, Executor executor) {     throw new IllegalStateException("Unimplemented!");   }

    public CompletionStage<T> whenComplete(BiConsumer<? super T, ? super Throwable> action) {     throw new IllegalStateException("Unimplemented!");   }

    public CompletionStage<T> whenCompleteAsync(BiConsumer<? super T, ? super Throwable> action) {     throw new IllegalStateException("Unimplemented!");   }

    public CompletionStage<T> whenCompleteAsync(BiConsumer<? super T, ? super Throwable> action, Executor executor) {     throw new IllegalStateException("Unimplemented!");   }

    public <U> CompletionStage<U> handle(BiFunction<? super T, Throwable, ? extends U> fn) {     throw new IllegalStateException("Unimplemented!");   }

    public <U> CompletionStage<U> handleAsync(BiFunction<? super T, Throwable, ? extends U> fn) {     throw new IllegalStateException("Unimplemented!");   }

    public <U> CompletionStage<U> handleAsync(BiFunction<? super T, Throwable, ? extends U> fn, Executor executor) {     throw new IllegalStateException("Unimplemented!");   }

    public boolean isCompletedExceptionally() {
        return isDone() && reason != null;
    }
}