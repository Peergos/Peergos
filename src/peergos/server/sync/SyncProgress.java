package peergos.server.sync;

import java.util.concurrent.atomic.AtomicLong;

public class SyncProgress {

    private final AtomicLong done = new AtomicLong(0);
    private final long total;

    public SyncProgress(long total) {
        this.total = total;
    }

    public void doneFile() {
        done.incrementAndGet();
    }

    public void doneFiles(int count) {
        done.addAndGet(count);
    }

    @Override
    public String toString() {
        return "("+done.get() + "/" + total+") files synced";
    }
}
