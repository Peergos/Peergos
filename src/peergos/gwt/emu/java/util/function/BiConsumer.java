package java.util.function;

import jsinterop.annotations.*;

import static javaemul.internal.InternalPreconditions.checkCriticalNotNull;

/**
 * A @JsFunction, so JavaScript can pass a plain function where Java takes a BiConsumer, rather than an
 * object with a method under whatever name GWT compiles accept to.
 */
@FunctionalInterface
@JsFunction
public interface BiConsumer<T, U> {

    void accept(T t, U u);

    @JsOverlay
    default BiConsumer<T, U> andThen(BiConsumer<? super T, ? super U> after) {
        checkCriticalNotNull(after);
        return (t, u) -> {
            accept(t, u);
            after.accept(t, u);
        };
    }
}
