package peergos.server.tests;

import org.junit.*;
import peergos.server.util.*;
import peergos.shared.crypto.*;

import java.util.*;

public class DifficultyGeneratorTests {

    @Test
    public void rateLimited() {
        int maxPerDay = 1000;
        long startTime = System.currentTimeMillis();
        long currentTime = startTime;
        DifficultyGenerator gen = new DifficultyGenerator(currentTime, maxPerDay);
        int events = 100;
        for (int i = 0; i < events; i++) {
            gen.addEvent();
            int diff = gen.currentDifficulty();
            System.out.println("Difficulty " + diff);
            long toSleep = 1L << diff;
            currentTime += toSleep;
            gen.updateTime(currentTime);
        }
        long minDuration = maxPerDay * 86400_000L / events;
        long duration = currentTime - startTime;
        Assert.assertTrue("Won't exceed daily limit", duration > minDuration);

        // Check recovery after a delay
        gen.updateTime(currentTime + 86400_000L * events / maxPerDay);
        int afterDelay = gen.currentDifficulty();
        Assert.assertTrue("Reduce difficulty after period of no queries", afterDelay == ProofOfWork.MIN_DIFFICULTY);
    }

    /** Simulate different hardware which gives a different constant factor in proof of work slow down
     *
     */
    @Test
    public void differentHardware() {
        for (int i: List.of(10_000, 1_000, 1_000_000))
            differentHardware(i);
    }

    public void differentHardware(int hardwareSpeedup) {
        int maxPerDay = 1000;
        long startTime = System.currentTimeMillis();
        long currentTime = startTime;
        DifficultyGenerator gen = new DifficultyGenerator(currentTime, maxPerDay);
        int events = 1000;
        for (int i = 0; i < events; i++) {
            gen.addEvent();
            int diff = gen.currentDifficulty();
            System.out.println("Difficulty " + diff);
            long toSleep = (1L << diff) / hardwareSpeedup;
            currentTime += toSleep;
            gen.updateTime(currentTime);
        }
        long minDuration = maxPerDay * 86400_000L / events;
        long duration = currentTime - startTime;
        Assert.assertTrue("Won't exceed daily limit", duration > minDuration);
    }
}
