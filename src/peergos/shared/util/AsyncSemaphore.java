package peergos.shared.util;

import java.util.*;
import java.util.concurrent.CompletableFuture;

public class AsyncSemaphore {
    private int permits;
    private final List<CompletableFuture<Void>> waiters = new ArrayList<>();

    public AsyncSemaphore(int permits) {
        this.permits = permits;
    }

    public synchronized CompletableFuture<Void> acquire() {
        if (permits > 0) {
            permits--;
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<Void> waiter = new CompletableFuture<>();
        waiters.add(waiter);
        return waiter;
    }

    public void release() {
        CompletableFuture<Void> toComplete = null;
        synchronized (this) {
            if (!waiters.isEmpty())
                toComplete = waiters.remove(0);
            else
                permits++;
        }
        if (toComplete != null)
            toComplete.complete(null);
    }
}
