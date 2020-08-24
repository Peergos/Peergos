package peergos.server.util;

import peergos.shared.crypto.*;

public class DifficultyGenerator {

    private final RateMonitor queryRate;
    private int difficulty = ProofOfWork.MIN_DIFFICULTY;
    private long timeOfLastUpdateMillis;
    private final double[] maxPerBucket;

    public DifficultyGenerator(long startTimeMillis, int maxPerDay) {
        this.timeOfLastUpdateMillis = startTimeMillis;
        // This covers a days worth of queries if the time unit is 0.1 seconds (2^20 > 864000)
        int nBuckets = 20;
        this.queryRate = new RateMonitor(nBuckets);
        double maxPerTimeStep = ((double)maxPerDay) / 864000;
        this.maxPerBucket = new double[nBuckets];
        for (int i=0; i < nBuckets; i++)
            maxPerBucket[i] = maxPerTimeStep * ((1L << (i + 1)) - (1L << i));
    }

    private static long millisToTimeSteps(long millis) {
        return millis / 100; // assumes 0.1s time step
    }

    public synchronized int currentDifficulty() {
        return difficulty;
    }

    public synchronized void updateTime(long epochMillis) {
        // update rate monitor
        long timeStepsSinceLastUpdate = millisToTimeSteps(epochMillis - timeOfLastUpdateMillis);
        if (timeStepsSinceLastUpdate > 0) {
            queryRate.timeSteps(timeStepsSinceLastUpdate);
            timeOfLastUpdateMillis = epochMillis;
        }
        updateDifficulty();
    }

    public synchronized void addEvent() {
        queryRate.addEvent();
    }

    private synchronized void updateDifficulty() {
        difficulty = calculateDifficulty(queryRate.getRates(), maxPerBucket);
    }

    private static int calculateDifficulty(long[] rates, double[] maxPerBucket) {
        double newDifficulty = ProofOfWork.MIN_DIFFICULTY;
        for (int i=0; i < rates.length; i++) {
            double maxForIndex = maxPerBucket[i];
            if (rates[i] > maxForIndex) {
                newDifficulty += Math.min(6.0, rates[i]/maxForIndex);
            }
        }
        return (int) newDifficulty;
    }
}
