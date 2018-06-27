package peergos.shared.util;

import java.util.function.*;

public class Triple<L,M, R> {
    public final L left;
    public final M middle;
    public final R right;

    public Triple(L left, M middle, R right) {
        this.left = left;
        this.middle = middle;
        this.right = right;
    }

    public <B,C,D> Triple<B, C, D> apply(Function<L, B> applyLeft, Function<M, C> applyMiddle, Function<R, D> applyRight) {
        return new Triple<>(
                applyLeft.apply(left),
                applyMiddle.apply(middle),
                applyRight.apply(right));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        Triple<?, ?, ?> triple = (Triple<?, ?, ?>) o;

        if (left != null ? !left.equals(triple.left) : triple.left != null) return false;
        if (middle != null ? !middle.equals(triple.middle) : triple.middle != null) return false;
        return right != null ? right.equals(triple.right) : triple.right == null;
    }

    @Override
    public int hashCode() {
        int result = left != null ? left.hashCode() : 0;
        result = 31 * result + (middle != null ? middle.hashCode() : 0);
        result = 31 * result + (right != null ? right.hashCode() : 0);
        return result;
    }

    @Override
    public String toString() {
        return "(" + left.toString() + ", " + middle.toString() + ", " + right.toString() + ")";
    }
}
