package peergos.server.tests.util;

import java.io.*;
import java.net.*;
import java.util.*;

/** Hands out ports the OS has confirmed are free. {@link #getPort()} is the
 *  default — TCP only, sufficient for any port a test will bind only over TCP.
 *  {@link #getTcpAndUdpPort()} is for libp2p swarm ports, where the tcp
 *  transport and QUIC share the same number and the OS treats the two port
 *  spaces separately (TCP-only probing missed UDP collisions, which is what
 *  caused the macOS BindException under parallel CI).
 *
 *  Within one JVM we additionally remember every port we've handed out and
 *  retry if the OS happens to re-issue one (e.g. after wrap-around). */
public class TestPorts {

    private static final Set<Integer> handedOut = new HashSet<>();

    public static synchronized int getPort() {
        IOException last = null;
        for (int attempt = 0; attempt < 50; attempt++) {
            int port;
            try (ServerSocket tcp = new ServerSocket()) {
                tcp.setReuseAddress(true);
                tcp.bind(new InetSocketAddress((InetAddress) null, 0));
                port = tcp.getLocalPort();
            } catch (IOException e) {
                last = e;
                continue;
            }
            if (!handedOut.add(port))
                continue;
            return port;
        }
        throw new RuntimeException("Couldn't allocate a free TCP port", last);
    }

    public static synchronized int getTcpAndUdpPort() {
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
