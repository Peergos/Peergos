package peergos.server.space;

import peergos.shared.cbor.*;
import peergos.shared.crypto.hash.*;

import java.util.*;

public class UserUsage implements Cborable {
    private long totalBytes;
    private boolean errored;
    private Map<PublicKeyHash, Long> pending;

    public UserUsage(long totalBytes) {
        this.totalBytes = totalBytes;
        this.pending = new HashMap<>();
        this.errored = false;
    }

    public UserUsage(long totalBytes, boolean errored, Map<PublicKeyHash, Long> pending) {
        this.totalBytes = totalBytes;
        this.pending = pending;
        this.errored = errored;
    }

    public long totalUsage() {
        return totalBytes;
    }

    protected synchronized void confirmUsage(PublicKeyHash writer, long usageDelta) {
        pending.remove(writer);
        totalBytes += usageDelta;
        errored = false;
    }

    protected synchronized void addPending(PublicKeyHash writer, long usageDelta) {
        pending.put(writer, pending.getOrDefault(writer, 0L) + usageDelta);
    }

    protected synchronized void clearPending(PublicKeyHash writer) {
        pending.remove(writer);
    }

    protected synchronized long getPending(PublicKeyHash writer) {
        return pending.getOrDefault(writer, 0L);
    }

    protected synchronized long expectedUsage() {
        return totalBytes + pending.values().stream().mapToLong(x -> x).sum();
    }

    protected synchronized void setErrored(boolean errored) {
        this.errored = errored;
    }

    protected synchronized boolean isErrored() {
        return errored;
    }

    @Override
    public CborObject toCbor() {
        return new CborObject.CborLong(totalBytes);
    }

    public static UserUsage fromCbor(Cborable cborable) {
        long usage = ((CborObject.CborLong) cborable).value;
        return new UserUsage(usage);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        UserUsage usage1 = (UserUsage) o;

        if (totalBytes != usage1.totalBytes) return false;
        return pending != null ? pending.equals(usage1.pending) : usage1.pending == null;
    }

    @Override
    public int hashCode() {
        int result = (int) (totalBytes ^ (totalBytes >>> 32));
        result = 31 * result + (pending != null ? pending.hashCode() : 0);
        return result;
    }
}
