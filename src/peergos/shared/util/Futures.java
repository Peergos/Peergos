package peergos.shared.util;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

public class Futures {

    public static <T> CompletableFuture<Set<T>> combineAll(Collection<CompletableFuture<T>> futures) {
        CompletableFuture<Set<T>> identity = CompletableFuture.completedFuture(Collections.emptySet());
        return futures.stream().reduce(identity,
                (a, b) -> b.thenCompose(opt ->
                        a.thenApply(set -> Stream.concat(set.stream(), Stream.of(opt))
                                .collect(Collectors.toSet()))),
                (a, b) -> b.thenCompose(setb ->
                        a.thenApply(seta -> Stream.concat(seta.stream(), setb.stream()).collect(Collectors.toSet()))));
    }

    public static <T> T logError(Throwable t) {
        t.printStackTrace();
        return null;
    }
}
