package peergos.server.util;

import java.util.function.Supplier;

public class SlidingWindowCounter {

    private final long windowSizeInSeconds;
    private final long maxRequestsPerWindow;
    private final Supplier<Long> clock;
    private long currentWindowStart;
    private long previousWindowTotal;
    private long currentWindowTotal;

    public SlidingWindowCounter(long windowSizeInSeconds,
                                long maxRequestsPerWindow,
                                Supplier<Long> clock) {
        this.windowSizeInSeconds = windowSizeInSeconds;
        this.maxRequestsPerWindow = maxRequestsPerWindow;
        this.clock = clock;
        this.currentWindowStart = clock.get();
        this.previousWindowTotal = 0;
        this.currentWindowTotal = 0;
    }

    public SlidingWindowCounter(long windowSizeInSeconds,
                                long maxRequestsPerWindow) {
        this(windowSizeInSeconds, maxRequestsPerWindow, () -> System.nanoTime() / 1_000_000_000);
    }

    public synchronized boolean allowRequest(long increment) {
        long now = clock.get();
        long timePassedInWindow = now - currentWindowStart;

        if (timePassedInWindow >= 2 * windowSizeInSeconds) {
            previousWindowTotal = 0;
            currentWindowTotal = 0;
            currentWindowStart = now;
            timePassedInWindow = 0;
        } else if (timePassedInWindow >= windowSizeInSeconds) {
            previousWindowTotal = currentWindowTotal;
            currentWindowTotal = 0;
            currentWindowStart = currentWindowStart + windowSizeInSeconds;
            timePassedInWindow -= windowSizeInSeconds;
        }

        double weightedCount = currentWindowTotal + previousWindowTotal *
                ((windowSizeInSeconds - timePassedInWindow) / (double) windowSizeInSeconds);

        if (weightedCount < maxRequestsPerWindow) {
            currentWindowTotal += increment;
            return true;
        }
        return false;
    }
}
