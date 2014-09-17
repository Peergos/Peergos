package peergos.util;

import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;

public class CompletableFuture<V> extends FutureTask<V> {

    public CompletableFuture(Callable<V> c) {
        super(c);
    }

    public void addResult(V v) {
        set(v);
    }
}
