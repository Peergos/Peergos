package peergos.server.sync;

import peergos.shared.util.ArrayOps;

import java.util.Arrays;
import java.util.Objects;

class Blake3state {
    public final byte[] hash;

    public Blake3state(byte[] hash) {
        this.hash = hash;
    }

    @Override
    public String toString() {
        return ArrayOps.bytesToHex(hash);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Blake3state that = (Blake3state) o;
        return Objects.deepEquals(hash, that.hash);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(hash);
    }
}
