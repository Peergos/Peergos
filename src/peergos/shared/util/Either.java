package peergos.shared.util;

import jsinterop.annotations.JsType;

import java.util.*;
import java.util.function.*;

@JsType
public class Either<A, B> {
    private final A a;
    private final B b;

    private Either(A a, B b) {
        this.a = a;
        this.b = b;
    }

    public <V> V map(Function<A, V> aMap, Function<B, V> bmap) {
        if (isA())
            return aMap.apply(a);
        return bmap.apply(b);
    }

    public boolean isA() {
        return a != null;
    }

    public boolean isB() {
        return b != null;
    }

    public A a() {
        if (a == null)
            throw new IllegalStateException("Absent value!");
        return a;
    }

    public B b() {
        if (b == null)
            throw new IllegalStateException("Absent value!");
        return b;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Either<?, ?> either = (Either<?, ?>) o;
        return Objects.equals(a, either.a) &&
                Objects.equals(b, either.b);
    }

    @Override
    public int hashCode() {
        return Objects.hash(a, b);
    }

    public static <A, B> Either<A, B> a(A a) {
        return new Either<>(a, null);
    }

    public static <A, B> Either<A, B> b(B b) {
        return new Either<>(null, b);
    }

    @Override
    public String toString() {
        return isA() ? a.toString() : b.toString();
    }
}
