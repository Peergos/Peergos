package java.util.function;

/** Can't extend Function, which is a JsFunction here. */
@FunctionalInterface
public interface UnaryOperator<T> {

    static <T> UnaryOperator<T> identity() {
        return t -> t;
    }

    T apply(T t);
}
