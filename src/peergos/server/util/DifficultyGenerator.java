package peergos.server.util;

import peergos.shared.crypto.*;

/** DifficultyGenerator is used to monitor a particular event and choose a difficulty level for a proof of work to
 *  rate limit it.
 *
 *  It is created with the desired maximum number of events per day to calibrate the difficulty.
 *
 *  The main assumption is that the proof of work scales exponentially with the difficulty.
 */
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
            maxPerBucket[i] = maxPerTimeStep * timeStepsForBucket(i);
        if (maxPerDay == 0)
            difficulty = ProofOfWork.MAX_DIFFICULTY;
    }

    private static long timeStepsForBucket(int bucket) {
        return (1L << (bucket + 1)) - (1L << bucket);
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
        double newDifficulty = 0.0;
        int includedBuckets = 0;
        for (int i=0; i < rates.length; i++) {
            double maxForIndex = maxPerBucket[i];
            if (rates[i] > maxForIndex) {
                double log = Math.log((double) rates[i] * 2 / maxForIndex);
                newDifficulty += log;
                includedBuckets++;
            }
        }
        if (includedBuckets == 0)
            return ProofOfWork.MIN_DIFFICULTY;
        int result = (int) (2 * newDifficulty + ProofOfWork.DEFAULT_DIFFICULTY);
        return Math.min(Math.max(ProofOfWork.MIN_DIFFICULTY, result), ProofOfWork.MAX_DIFFICULTY);
    }
}
