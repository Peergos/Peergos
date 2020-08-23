package peergos.server.tests;

import org.junit.*;
import peergos.server.util.*;
import peergos.shared.crypto.*;

public class DifficultyGeneratorTests {

    @Test
    public void rateLimited() {
        int maxPerDay = 1000;
        long startTime = System.currentTimeMillis();
        long currentTime = startTime;
        DifficultyGenerator gen = new DifficultyGenerator(currentTime, maxPerDay);
        int events = 100;
        int toSleep = 0;
        for (int i = 0; i < events; i++) {
            gen.addEvent();
            int diff = gen.currentDifficulty();
            System.out.println("Difficulty " + diff);
            toSleep = 1 << (diff - 11);
            currentTime += toSleep;
            gen.updateTime(currentTime);
        }
        long minDuration = maxPerDay * 86400_000 / events;
        long duration = currentTime - startTime;
        Assert.assertTrue("Won't exceed daily limit", duration > minDuration);

        // Check recovery after a delay
        gen.updateTime(currentTime + 86400_000L * events / maxPerDay);
        int afterDelay = gen.currentDifficulty();
        Assert.assertTrue("Reduce difficulty after period of no queries", afterDelay == ProofOfWork.MIN_DIFFICULTY);
    }
}
