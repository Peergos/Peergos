package peergos.shared.mutable;

import peergos.shared.crypto.hash.*;
import peergos.shared.storage.CasException;

import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

public class RetryMutablePointers implements MutablePointers {

    private final Random random = new Random(1);
    private final MutablePointers target;
    private final ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1);
    private final int maxAttempts = 3;

    public RetryMutablePointers(MutablePointers target) {
        this.target = target;
    }

    private <V> void retryAfter(Supplier<CompletableFuture<V>> method, int milliseconds) {
        executor.schedule(method::get, milliseconds, TimeUnit.MILLISECONDS);
    }

    private int jitter(int minMilliseconds, int rangeMilliseconds) {
        return minMilliseconds + random.nextInt(rangeMilliseconds);
    }

    private <V> CompletableFuture<V> runWithRetry(Supplier<CompletableFuture<V>> f) {
        return recurse(maxAttempts, f);
    }

    private <V> CompletableFuture<V> recurse(int retriesLeft, Supplier<CompletableFuture<V>> f) {
        CompletableFuture<V> res = new CompletableFuture<>();
        try {
            f.get()
                    .thenAccept(res::complete)
                    .exceptionally(e -> {
                        if (retriesLeft == 1) {
                            res.completeExceptionally(e);
                        } else if (e instanceof ConnectException) {
                            res.completeExceptionally(e);
                        } else if (e instanceof CasException) {
                            res.completeExceptionally(e);
                        } else {
                            retryAfter(() -> recurse(retriesLeft - 1, f)
                                            .thenAccept(res::complete)
                                            .exceptionally(t -> {
                                                res.completeExceptionally(t);
                                                return null;
                                            }),
                                    jitter((maxAttempts + 1 - retriesLeft) * 1000, 500));
                        }
                        return null;
                    });
        } catch (Throwable t) {
            res.completeExceptionally(t);
        }
        return res;
    }

    @Override
    public CompletableFuture<Boolean> setPointer(PublicKeyHash owner, PublicKeyHash writer, byte[] writerSignedBtreeRootHash) {
        return runWithRetry(() -> target.setPointer(owner, writer, writerSignedBtreeRootHash));
    }

    @Override
    public CompletableFuture<Optional<byte[]>> getPointer(PublicKeyHash owner, PublicKeyHash writer) {
        return runWithRetry(() -> target.getPointer(owner, writer));
    }

    @Override
    public MutablePointers clearCache() {
        return new RetryMutablePointers(target.clearCache());
    }
}
