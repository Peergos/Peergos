package peergos.server.tests;

import org.junit.Assert;
import org.junit.Test;
import peergos.server.util.SlidingWindowCounter;

public class RateLimitTests {

    @Test
    public void maxRequestsInWindow() {
        Clock clock = new Clock(System.currentTimeMillis());
        SlidingWindowCounter limiter = new SlidingWindowCounter(20, 100, clock::now);
        Assert.assertTrue(limiter.allowRequest(100));
        Assert.assertFalse(limiter.allowRequest(1));
    }

    @Test
    public void previousWindow() {
        Clock clock = new Clock(System.currentTimeMillis());
        int windowSizeInSeconds = 20;
        SlidingWindowCounter limiter = new SlidingWindowCounter(windowSizeInSeconds, 100, clock::now);
        Assert.assertTrue(limiter.allowRequest(100));
        clock.addTime(windowSizeInSeconds * 3 / 2 * 1000);
        Assert.assertTrue(limiter.allowRequest(50));
        Assert.assertFalse(limiter.allowRequest(1));
        clock.addTime(windowSizeInSeconds / 2 * 1000);
        Assert.assertTrue(limiter.allowRequest(50));
        Assert.assertFalse(limiter.allowRequest(1));
    }

    static class Clock {
        private long time;

        public Clock(long time) {
            this.time = time;
        }

        public synchronized long now() {
            return time/1000;
        }

        public synchronized void addTime(long delta) {
            time += delta;
        }
    }
}
