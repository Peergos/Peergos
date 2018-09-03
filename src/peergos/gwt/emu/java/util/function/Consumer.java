package java.util.function;
import jsinterop.annotations.JsType;

import java.util.Objects;

@FunctionalInterface
@JsType
public interface Consumer<T> {

    void accept(T t);

    default java.util.function.Consumer<T> andThen(java.util.function.Consumer<? super T> after) {
        Objects.requireNonNull(after);
        return (T t) -> { accept(t); after.accept(t); };
    }
}