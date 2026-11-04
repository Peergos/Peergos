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
    // how often each writer's reservations were dropped whole: a reservation made before the last drop has nothing
    // left to give back, and what the writer has reserved now is other writes'
    private final Map<PublicKeyHash, Long> drops = new HashMap<>();

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
        clearPending(writer);
        totalBytes += usageDelta;
        errored = false;
    }

    /** The committed total read again. The bytes of writes still in flight are kept, except those of a writer
     *  that hasn't reserved since staleBefore: a write that never landed (refused, cancelled, its commit lost)
     *  would otherwise hold its bytes for good. A refusal's tolerance ends here, as with any fresh read. */
    public synchronized void reloadTotal(long totalBytes, long staleBefore) {
        this.totalBytes = totalBytes;
        errored = false;
        pendingSince.entrySet().removeIf(e -> {
            if (e.getValue() >= staleBefore)
                return false;
            pending.remove(e.getKey());
            drops.merge(e.getKey(), 1L, Long::sum);
            return true;
        });
    }

    /** Reserve the bytes of a write if it fits. The check and the reservation are one step, so two writes
     *  arriving together can't both fit where only one does. A write that doesn't fit reserves nothing, and
     *  the writer's other writes in flight keep what they reserved: each gives its own back if it fails.
     *
     * @return what gives the reservation back, or empty if it doesn't fit: the usage is marked errored
     */
    public synchronized Optional<Runnable> reserve(PublicKeyHash writer, long size, long quota, long tolerance) {
        long allowed = errored ? quota + tolerance : quota;
        if (expectedUsage() + size > allowed) {
            errored = true;
            return Optional.empty();
        }
        addPending(writer, size);
        long dropsBefore = drops.getOrDefault(writer, 0L);
        return Optional.of(() -> release(writer, size, dropsBefore));
    }

    public synchronized void addPending(PublicKeyHash writer, long usageDelta) {
        pending.put(writer, pending.getOrDefault(writer, 0L) + usageDelta);
        pendingSince.put(writer, System.currentTimeMillis());
    }

    /** Give back a reservation, unless the writer's were dropped since it was made, by a processed commit or a reload */
    private synchronized void release(PublicKeyHash writer, long size, long dropsBefore) {
        if (drops.getOrDefault(writer, 0L) != dropsBefore)
            return;
        long left = pending.getOrDefault(writer, 0L) - size;
        if (left > 0)
            pending.put(writer, left);
        else {
            pending.remove(writer);
            pendingSince.remove(writer);
        }
    }

    protected synchronized void clearPending(PublicKeyHash writer) {
        pendingSince.remove(writer);
        // only a writer with bytes reserved has a release to stop: every cap is told of each writer whose edges change
        if (pending.remove(writer) != null)
            drops.merge(writer, 1L, Long::sum);
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
