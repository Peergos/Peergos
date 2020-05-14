package java.util.concurrent;

import jsinterop.annotations.*;
import java.util.function.Supplier;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Callable;
import java.util.function.Supplier;

public class ScheduledThreadPoolExecutor {

    private NativeJSScheduler scheduler = new NativeJSScheduler();

    public ScheduledThreadPoolExecutor(int corePoolSize) {
    }

    public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
        if (! unit.equals(TimeUnit.MILLISECONDS)) {
            throw new Error("only milliseconds supported");
        }
        if (delay < 0 || delay > Integer.MAX_VALUE) {
            throw new Error("invalid delay");
        }
        scheduler.callAfterDelay(new CallableWrapper(callable), (int)delay);
        return null;
    }
    private static class CallableWrapper {
        private final Callable callable;
        public CallableWrapper(Callable callable) {
            this.callable = callable;
        }
        @JsMethod
        public void call() throws Exception {
            this.callable.call();
        }
    }
    @JsType(namespace = "callback", isNative = true)
    private static class NativeJSScheduler {
        public native void callAfterDelay(CallableWrapper func, int delayMs);
    }
}