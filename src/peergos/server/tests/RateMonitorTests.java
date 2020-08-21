package peergos.server.tests;

import org.junit.*;
import peergos.server.util.*;

public class RateMonitorTests {

    @Test
    public void linear() {
        RateMonitor rates = new RateMonitor(10);
        for (int i=0; i < 1L << 20; i++) {
            rates.addEvent();
            rates.timeStep();
        }
        long[] linear = rates.getRates();
        Assert.assertTrue("Powers of two", linear[0] == 0);
        for (int i=1; i < 10; i++)
            Assert.assertTrue("Powers of two", linear[i] == 1L << i - 1);
    }
}
