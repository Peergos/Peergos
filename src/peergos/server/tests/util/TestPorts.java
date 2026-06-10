package peergos.server.tests.util;

import java.io.*;
import java.net.*;
import java.util.*;

/** Hands out a port the OS has confirmed is free. Each call binds port 0
 *  (which makes the OS pick an unused ephemeral port), records the chosen
 *  number, then closes the socket so the caller can bind it. The OS rotates
 *  its ephemeral-port assignment, so the same port won't come back within a
 *  short window — making collisions between parallel JVMs much rarer than
 *  the previous random-counter scheme.
 *
 *  Within one JVM we additionally remember every port we've handed out and
 *  retry if the OS happens to re-issue one (e.g. after wrap-around). */
public class TestPorts {

    private static final Set<Integer> handedOut = new HashSet<>();

    public static synchronized int getPort() {
        IOException last = null;
        for (int attempt = 0; attempt < 50; attempt++) {
            try (ServerSocket s = new ServerSocket()) {
                s.setReuseAddress(true);
                s.bind(new InetSocketAddress((InetAddress) null, 0));
                int port = s.getLocalPort();
                if (handedOut.add(port))
                    return port;
            } catch (IOException e) {
                last = e;
            }
        }
        throw new RuntimeException("Couldn't allocate a unique free port", last);
    }
}
