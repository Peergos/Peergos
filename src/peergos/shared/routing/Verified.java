package peergos.shared.routing;

import peergos.shared.util.*;

import java.util.*;

/** A record whose bounds and signature have been checked, with its hash, and for an establishment the hash of
 *  the key it establishes, which a commitment must equal.
 */
public class Verified<R extends SignedRecord> {
    public final R record;
    public final byte[] hash;
    public final byte[] keyHash;

    public Verified(R record, byte[] hash, byte[] keyHash) {
        this.record = record;
        this.hash = hash;
        this.keyHash = keyHash;
    }

    public static int byHash(Verified<?> a, Verified<?> b) {
        return ArrayOps.compare(a.hash, b.hash);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (! (o instanceof Verified)) return false;
        return Arrays.equals(hash, ((Verified<?>) o).hash);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(hash);
    }
}
