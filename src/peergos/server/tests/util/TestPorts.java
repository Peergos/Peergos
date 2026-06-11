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
            // Probe UDP first. Windows' dynamic-port range is shared between TCP
            // and UDP, but UDP is much busier (mDNS, SSDP, NLA, AV agents, Hyper-V
            // excluded ranges) — letting the OS pick from the UDP-free space and
            // then verifying TCP on the same number succeeds far more often than
            // the other way around. Without this, every TCP-free ephemeral port we
            // got back was already in use by some UDP socket and the retry loop
            // ran out without finding a candidate.
            try (DatagramSocket udp = new DatagramSocket(null)) {
                udp.setReuseAddress(true);
                udp.bind(new InetSocketAddress((InetAddress) null, 0));
                port = udp.getLocalPort();
            } catch (IOException e) {
                last = e;
                continue;
            }
            if (!handedOut.add(port))
                continue;
            // Verify TCP on the same number. SO_REUSEADDR matches what netty/libp2p
            // sets when it eventually binds, so TIME_WAIT and recent-close states
            // aren't reported as taken.
            try (ServerSocket tcp = new ServerSocket()) {
                tcp.setReuseAddress(true);
                tcp.bind(new InetSocketAddress((InetAddress) null, port));
            } catch (IOException e) {
                last = e;
                continue;
            }
            return port;
        }
        throw new RuntimeException("Couldn't allocate a port free on both TCP and UDP", last);
    }
}
