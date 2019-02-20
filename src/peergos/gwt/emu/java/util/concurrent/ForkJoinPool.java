package java.util.concurrent;

import jsinterop.annotations.*;

public class ForkJoinPool {
    private static final ForkJoinPool instance = new ForkJoinPool(new JSForkJoinPool());

    private final JSForkJoinPool pool;

    public ForkJoinPool(JSForkJoinPool pool) {
        this.pool = pool;
    }

    public static ForkJoinPool commonPool() {
        return instance;
    }

    public void execute(Runnable task) {
        pool.execute(task);
    }

    @JsType(namespace = "ForkJoinJS", isNative = true)
    private static class JSForkJoinPool {
        public native void execute(Runnable task);
    }
}