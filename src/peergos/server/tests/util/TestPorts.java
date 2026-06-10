package peergos.server.tests.util;

import java.io.*;
import java.net.*;
import java.util.*;

/** Hands out a port the OS has confirmed is free on BOTH TCP and UDP. Each
 *  call binds port 0 to let the OS pick an unused ephemeral TCP port, then
 *  probes UDP on the same number — libp2p binds ipfs-swarm-port for both the
 *  tcp transport and QUIC, and the OS treats those port spaces separately, so
 *  a TCP-only probe missed the case where QUIC's UDP bind collided (the macOS
 *  BindException we hit under parallel CI).
 *
 *  Within one JVM we additionally remember every port we've handed out and
 *  retry if the OS happens to re-issue one (e.g. after wrap-around). */
public class TestPorts {

    private static final Set<Integer> handedOut = new HashSet<>();

    public static synchronized int getPort() {
        IOException last = null;
        for (int attempt = 0; attempt < 50; attempt++) {
            int port;
            try (ServerSocket s = new ServerSocket()) {
                s.setReuseAddress(true);
                s.bind(new InetSocketAddress((InetAddress) null, 0));
                port = s.getLocalPort();
            } catch (IOException e) {
                last = e;
                continue;
            }
            if (!handedOut.add(port))
                continue;
            // Probe UDP on the same number. SO_REUSEADDR matches what netty/libp2p
            // sets when it eventually binds, so TIME_WAIT and recent-close states
            // aren't reported as taken.
            try (DatagramSocket udp = new DatagramSocket(null)) {
                udp.setReuseAddress(true);
                udp.bind(new InetSocketAddress((InetAddress) null, port));
            } catch (IOException e) {
                last = e;
                continue;
            }
            return port;
        }
        throw new RuntimeException("Couldn't allocate a port free on both TCP and UDP", last);
    }
}
