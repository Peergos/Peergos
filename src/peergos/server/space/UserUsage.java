package peergos.server.space;

import peergos.shared.cbor.*;
import peergos.shared.crypto.hash.*;

import java.util.*;

public class UserUsage implements Cborable {
    private long totalBytes;
    private boolean errored;
    private Map<PublicKeyHash, Long> pending;
    // when each writer last reserved, so reservations for writes that never land can expire
    private final Map<PublicKeyHash, Long> pendingSince = new HashMap<>();

    public UserUsage(long totalBytes) {
        this.totalBytes = totalBytes;
        this.pending = new HashMap<>();
        this.errored = false;
    }

    public UserUsage(long totalBytes, boolean errored, Map<PublicKeyHash, Long> pending) {
        this.totalBytes = totalBytes;
        this.pending = pending;
        this.errored = errored;
        long now = System.currentTimeMillis();
        pending.keySet().forEach(w -> pendingSince.put(w, now));
    }

    public long totalUsage() {
        return totalBytes;
    }

    protected synchronized void confirmUsage(PublicKeyHash writer, long usageDelta) {
        pending.remove(writer);
        pendingSince.remove(writer);
        totalBytes += usageDelta;
        errored = false;
    }

    /** The committed total read again. The bytes of writes still in flight are kept, except those of a writer
     *  that hasn't reserved since staleBefore: a write that never landed (refused, cancelled, its commit lost)
     *  would otherwise hold its bytes for good. */
    public synchronized void reloadTotal(long totalBytes, long staleBefore) {
        this.totalBytes = totalBytes;
        pendingSince.entrySet().removeIf(e -> {
            if (e.getValue() >= staleBefore)
                return false;
            pending.remove(e.getKey());
            return true;
        });
    }

    /** Reserve the bytes of a write if it fits. The check and the reservation are one step, so two writes
     *  arriving together can't both fit where only one does.
     *
     * @return false if it doesn't fit: this writer's pending bytes are dropped and the usage marked errored
     */
    public synchronized boolean reserve(PublicKeyHash writer, long size, long quota, long tolerance) {
        long allowed = errored ? quota + tolerance : quota;
        if (expectedUsage() + size > allowed) {
            pending.remove(writer);
            pendingSince.remove(writer);
            errored = true;
            return false;
        }
        addPending(writer, size);
        return true;
    }

    public synchronized void addPending(PublicKeyHash writer, long usageDelta) {
        pending.put(writer, pending.getOrDefault(writer, 0L) + usageDelta);
        pendingSince.put(writer, System.currentTimeMillis());
    }

    /** Give back a reservation. A refusal in between may already have dropped this writer's bytes. */
    public synchronized void releasePending(PublicKeyHash writer, long size) {
        long left = pending.getOrDefault(writer, 0L) - size;
        if (left > 0)
            pending.put(writer, left);
        else {
            pending.remove(writer);
            pendingSince.remove(writer);
        }
    }

    protected synchronized void clearPending(PublicKeyHash writer) {
        pending.remove(writer);
        pendingSince.remove(writer);
    }

    public synchronized long getPending(PublicKeyHash writer) {
        return pending.getOrDefault(writer, 0L);
    }

    public synchronized long expectedUsage() {
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
