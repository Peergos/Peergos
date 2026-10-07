package java.util.function;

import jsinterop.annotations.*;

/**
 * A @JsFunction, so JavaScript can pass a plain function where Java takes a Supplier, rather than an
 * object with a method under whatever name GWT compiles get to.
 */
@FunctionalInterface
@JsFunction
public interface Supplier<T> {

    T get();
}
