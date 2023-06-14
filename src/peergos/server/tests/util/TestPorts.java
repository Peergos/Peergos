package peergos.server.tests.util;

import java.util.concurrent.atomic.*;

public class TestPorts {

    private static final AtomicInteger port = new AtomicInteger(0);
    public static int getPort() {
        return 9050 + (port.incrementAndGet() % 50_000);
    }
}
