package peergos.server.util;

import java.util.*;

public class RateMonitor {

    private long timeSteps = 0;
    // ith element is # requests between 2^i and 2^(i+1) time steps ...
    private final long[] buckets;

    public RateMonitor(int nBuckets) {
        this.buckets = new long[nBuckets];
    }

    public synchronized void addEvent() {
        buckets[0]++;
    }

    private void moveBucket(int from, int to) {
        if (to < buckets.length)
            buckets[to] += buckets[from];
        buckets[from] = 0;
    }

    public synchronized void timeStep() {
        timeSteps++;
        for (int i= buckets.length - 1; i >=0; i--) {
            if (timeSteps % (1L << i) == 0) moveBucket(i, i + 1);
        }
    }

    public synchronized void timeSteps(long steps) {
        if (steps > 1L << buckets.length)
            Arrays.fill(buckets, 0);
        else
            for (long i=0; i < steps; i++)
                timeStep();
    }

    public synchronized long[] getRates() {
        return Arrays.copyOfRange(buckets, 0, buckets.length);
    }
}
