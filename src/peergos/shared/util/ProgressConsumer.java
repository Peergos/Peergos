package peergos.shared.util;

import jsinterop.annotations.*;

@FunctionalInterface
@JsFunction
public interface ProgressConsumer<T> {

    void accept(T t);
}