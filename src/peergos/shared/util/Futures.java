package peergos.shared.util;

import jsinterop.annotations.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.stream.*;

public class Futures {

    @JsMethod
    public static final <T> CompletableFuture<T> of(T val) {
        return CompletableFuture.completedFuture(val);
    }

    /**
     *
     * @param futures collection of independent futures whose results we want to combine
     * @param <T> result type of each future
     * @return
     */
    public static <T> CompletableFuture<Set<T>> combineAll(Collection<CompletableFuture<T>> futures) {
        CompletableFuture<Set<T>> identity = CompletableFuture.completedFuture(Collections.emptySet());
        return futures.stream().reduce(identity,
                (a, b) -> b.thenCompose(opt ->
                        a.thenApply(set -> Stream.concat(set.stream(), Stream.of(opt))
                                .collect(Collectors.toSet()))),
                (a, b) -> b.thenCompose(setb ->
                        a.thenApply(seta -> Stream.concat(seta.stream(), setb.stream()).collect(Collectors.toSet()))));
    }

    /**
     *
     * @param futures collection of independent futures whose results we want to combine
     * @param <T> result type of each future
     * @return
     */
    public static <T> CompletableFuture<List<T>> combineAllInOrder(Collection<CompletableFuture<T>> futures) {
        CompletableFuture<List<T>> identity = CompletableFuture.completedFuture(Collections.emptyList());
        return futures.stream().reduce(identity,
                (a, b) -> b.thenCompose(opt ->
                        a.thenApply(set -> {
                            ArrayList<T> combined = new ArrayList<>(set.size() + 1);
                            combined.addAll(set);
                            combined.add(opt);
                            return combined;
                        })),
                (a, b) -> b.thenCompose(setb ->
                        a.thenApply(seta -> Stream.concat(seta.stream(), setb.stream()).collect(Collectors.toList()))));
    }

    /*** Reduce a set of input values against an Identity where the composition step is asynchronous
     *
     * @param input the values to reduce
     * @param identity the identity of the target type
     * @param composer composes an input value with a target type value asynchronously
     * @param combiner
     * @param <T> target type
     * @param <V> input type
     * @return
     */
    public static <T, V> CompletableFuture<T> reduceAll(Collection<V> input,
                                                        T identity,
                                                        BiFunction<T, V, CompletableFuture<T>> composer,
                                                        BiFunction<T, T, T> combiner) {
        return reduceAll(input.stream(), identity, composer, combiner);
    }

    /*** Reduce a set of input values against an Identity where the composition step is asynchronous
     *
     * @param input the values to reduce
     * @param identity the identity of the target type
     * @param composer composes an input value with a target type value asynchronously
     * @param combiner
     * @param <T> target type
     * @param <V> input type
     * @return
     */
    public static <T, V> CompletableFuture<T> reduceAll(Stream<V> input,
                                                        T identity,
                                                        BiFunction<T, V, CompletableFuture<T>> composer,
                                                        BiFunction<T, T, T> combiner) {
        CompletableFuture<T> identityFut = CompletableFuture.completedFuture(identity);
        return input.reduce(
                identityFut,
                (a, b) -> a.thenCompose(res -> composer.apply(res, b)),
                (a, b) -> a.thenCompose(x -> b.thenApply(y -> combiner.apply(x, y)))
        );
    }

    /** Efficiently apply an async function to an entire list
     *
     * @param input
     * @param producer
     * @param <X>
     * @param <V>
     * @return
     */
    public static <X, V> CompletableFuture<List<V>> map(List<X> input,
                                                        Function<X, CompletableFuture<V>> producer) {
        return combineAllInOrder(input.stream()
                .parallel()
                .map(producer)
                .collect(Collectors.toList()));
    }

    /*** Asynchronously map a set of input values to output values until one matches a predicate
     *
     * @param input the values to reduce
     * @param producer maps an input value to a completable future of the return type
     * @param <X> input type
     * @param <V> return type
     * @return
     */
    public static <X, V> CompletableFuture<Optional<V>> findFirst(
            Collection<X> input,
            Function<X, CompletableFuture<Optional<V>>> producer) {
        if (input.isEmpty())
            return Futures.of(Optional.empty());
        List<X> inList = new ArrayList<>(input);

        return producer.apply(inList.get(0))
                .thenCompose(optRes -> {
                    if (optRes.isPresent())
                        return Futures.of(optRes);
                    return findFirst(inList.subList(1, inList.size()), producer);
                });
    }

    public static <V> CompletableFuture<V> runAsync(Supplier<CompletableFuture<V>> work) {
        return runAsync(work, ForkJoinPool.commonPool());
    }

    public static <V> CompletableFuture<V> runAsync(Supplier<CompletableFuture<V>> work, ForkJoinPool pool) {
        CompletableFuture<V> res = new CompletableFuture<>();
        pool.execute(() -> {
            try {
                work.get()
                        .thenApply(res::complete)
                        .exceptionally(res::completeExceptionally);
            } catch (Throwable t) {
                res.completeExceptionally(t);
            }
        });
        return res;
    }

    public static <T> CompletableFuture<T> asyncExceptionally(Supplier<CompletableFuture<T>> normal,
                                                              Function<Throwable, CompletableFuture<T>> exceptional) {
        CompletableFuture<T> result = new CompletableFuture<>();
        normal.get()
                .thenApply(result::complete)
                .exceptionally(t -> {
                    exceptional.apply(t)
                            .thenApply(result::complete)
                            .exceptionally(result::completeExceptionally);
                    return true;
                });
        return result;
    }

    public static <T> T logAndReturn(Throwable t, T result) {
        t.printStackTrace();
        return result;
    }

    public static <T> T logAndThrow(Throwable t) {
        return logAndThrow(t, Optional.empty());
    }

    public static <T> T logAndThrow(Throwable t, Optional<String> message) {
        if (message.isPresent())
            System.out.println(message);
        t.printStackTrace();
        throw new RuntimeException(t.getMessage(), t);
    }

    public static <T> CompletableFuture<T> errored(Throwable t) {
        CompletableFuture<T> err = new CompletableFuture<>();
        err.completeExceptionally(t);
        return err;
    }

    @JsMethod
    public static <T>CompletableFuture<T> incomplete() {
        return new CompletableFuture<>();
    }
}
