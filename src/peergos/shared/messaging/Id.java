package peergos.shared.messaging;

import java.util.*;
import java.util.stream.*;

/** Ids in a chat form a tree. The creator is the root, and each member is a child node of the member that invited them.
 *  They are fully concurrent - anyone can invite anyone at any time without any synchronization.
 *
 *  In the simple case of a fixed group know at creation time these are the same as the indices in a vector clock.
 */
public final class Id implements Comparable<Id> {

    public final int[] id;

    public Id(int[] id) {
        this.id = id;
    }

    public Id(int counter) {
        this(new int[]{counter});
    }

    public static Id creator() {
        return new Id(0);
    }

    public Id fork(int counter) {
        int[] descendant = new int[id.length + 1];
        System.arraycopy(id, 0, descendant, 0, id.length);
        descendant[id.length] = counter;
        return new Id(descendant);
    }

    public Id parent() {
        return new Id(Arrays.copyOfRange(id, 0, id.length - 1));
    }

    @Override
    public int compareTo(Id other) {
        return compare(id, other.id);
    }

    @Override
    public String toString() {
        return "[" + Arrays.stream(id).boxed().map(x -> x.toString()).collect(Collectors.joining(",")) + "]";
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(id);
    }

    @Override
    public boolean equals(Object obj) {
        if (! (obj instanceof Id))
            return false;
        return Arrays.equals(id, ((Id) obj).id);
    }

    private static int compare(int[] a, int[] b) {
        if (a == b) {
            return 0;
        } else if (a != null && b != null) {
            int i = mismatch(a, b, Math.min(a.length, b.length));
            return i >= 0 ? Integer.compare(a[i], b[i]) : a.length - b.length;
        } else {
            return a == null ? -1 : 1;
        }
    }

    private static int mismatch(int[] a, int[] b, int length) {
        int i=0;
        while (i < length) {
            if (a[i] != b[i]) {
                return i;
            }

            ++i;
        }

        return -1;
    }
}
