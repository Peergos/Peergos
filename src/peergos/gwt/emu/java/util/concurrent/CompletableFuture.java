package java.util.concurrent;

import com.google.gwt.user.client.*;
import jsinterop.annotations.*;

import java.util.function.*;

@JsType(isNative=true, name = "Promise", namespace = JsPackage.GLOBAL)
public class CompletableFuture<T> implements CompletionStage<T> {

    @JsMethod(name = "then")
    public native <U> CompletableFuture<U> thenApply(Function<? super T,? extends U> fn);

    @JsMethod(name = "then")
    public native CompletableFuture<Void> thenAccept(Consumer<? super T> action);

    @JsMethod(name = "then")
    public native <U> CompletableFuture<U> thenCompose(Function<? super T, ? extends CompletionStage<U>> fn);

    @JsMethod(name = "resolve")
    public static native <U> CompletableFuture<U> completedFuture(U value);

    @JsMethod(name = "reject")
    public native boolean completeExceptionally(Throwable ex);

    @JsMethod(name = "resolve")
    public native boolean complete(T value);

    public T get() throws InterruptedException, ExecutionException {
        Window.alert("Calling synchronous get() on CompletableFuture is not possibile in Javascript!");
        throw new IllegalStateException("Unimplemented!");
    }
}
