package peergos.shared.util;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.stream.*;

public class Futures {

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
                        a.thenApply(set -> Stream.concat(set.stream(), Stream.of(opt))
                                .collect(Collectors.toList()))),
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
        CompletableFuture<T> identityFut = CompletableFuture.completedFuture(identity);
        return input.stream().reduce(
                identityFut,
                (a, b) -> a.thenCompose(res -> composer.apply(res, b)),
                (a, b) -> a.thenCompose(x -> b.thenApply(y -> combiner.apply(x, y)))
        );
    }

    public static <T> T logError(Throwable t) {
        t.printStackTrace();
        throw new RuntimeException(t.getMessage(), t);
    }

    public static <T> CompletableFuture<T> errored(Throwable t) {
        CompletableFuture<T> err = new CompletableFuture<>();
        err.completeExceptionally(t);
        return err;
    }

}
