package peergos.shared.util;

import java.util.Objects;
import java.util.function.Function;

@FunctionalInterface
public interface TriFunction<T, U, X, R> {

    R apply(T t, U u, X x);

    default <V> TriFunction<T, U, X, V> andThen(Function<? super R, ? extends V> after) {
        Objects.requireNonNull(after);
        return (T t, U u, X x) -> after.apply(apply(t, u, x));
    }
}
