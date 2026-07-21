package peergos.server.tests.util;

import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;

/** Hands out ports the OS has confirmed are free. {@link #getPort()} is the
 *  default — TCP only, sufficient for any port a test will bind only over TCP.
 *  {@link #getTcpAndUdpPort()} is for libp2p swarm ports, where the tcp
 *  transport and QUIC share the same number and the OS treats the two port
 *  spaces separately (TCP-only probing missed UDP collisions, which is what
 *  caused the macOS BindException under parallel CI).
 *
 *  Within one JVM we additionally remember every port we've handed out and
 *  retry if the OS happens to re-issue one (e.g. after wrap-around).
 *
 *  On Windows only, we also coordinate <em>across</em> JVMs: CI runs three test
 *  classes in parallel, each in its own forked JVM, and Windows' small, busy
 *  dynamic-port range makes the probe-then-bind-later race collide often (two
 *  forks handed the same not-yet-bound port). Each fork records the ports it
 *  hands out in a shared file, guarded by a file lock, so a concurrent fork
 *  won't be handed a port another fork has claimed but not yet bound. Claims
 *  carry a timestamp and expire after {@link #CLAIM_TTL_MS} — by then the
 *  server has bound the port, so the OS bind-probe catches it — which also
 *  drops entries left behind by a previous run, so the file never needs
 *  explicit clearing. This is skipped on other platforms, where TCP/UDP
 *  probing alone has proven sufficient. */
public class TestPorts {

    private static final Set<Integer> handedOut = new HashSet<>();

    private static final boolean IS_WINDOWS =
            System.getProperty("os.name", "").toLowerCase().contains("win");

    private static final Path CLAIMS_FILE =
            Paths.get(System.getProperty("java.io.tmpdir"), "peergos-test-ports.claims");
    private static final long CLAIM_TTL_MS = 120_000;

    public static synchronized int getPort() {
        return acquire(false);
    }

    public static synchronized int getTcpAndUdpPort() {
        return acquire(true);
    }

    private static int acquire(boolean needUdp) {
        IOException last = null;
        for (int attempt = 0; attempt < 50; attempt++) {
            try {
                Integer port = IS_WINDOWS ? acquireCoordinated(needUdp) : acquireLocal(needUdp);
                if (port != null)
                    return port;
            } catch (IOException e) {
                last = e;
            }
        }
        throw new RuntimeException(needUdp
                ? "Couldn't allocate a port free on both TCP and UDP"
                : "Couldn't allocate a free TCP port", last);
    }

    /** Per-JVM allocation. Returns null if the probed port has already been
     *  handed out by this JVM and the caller should retry. */
    private static Integer acquireLocal(boolean needUdp) throws IOException {
        int port = needUdp ? probeTcpAndUdp() : probeTcp();
        return handedOut.add(port) ? port : null;
    }

    /** Windows cross-JVM allocation. Holds a lock on the shared claims file
     *  while probing so no two forks can pick the same port concurrently.
     *  Returns null if the probed port is already claimed (by this or another
     *  fork) and the caller should retry. */
    private static Integer acquireCoordinated(boolean needUdp) throws IOException {
        try (FileChannel channel = FileChannel.open(CLAIMS_FILE,
                StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
             FileLock lock = channel.lock()) {
            long now = System.currentTimeMillis();
            Map<Integer, Long> fresh = readFreshClaims(channel, now);
            int port = needUdp ? probeTcpAndUdp() : probeTcp();
            if (fresh.containsKey(port) || !handedOut.add(port))
                return null;
            fresh.put(port, now);
            writeClaims(channel, fresh);
            return port;
        }
    }

    private static int probeTcp() throws IOException {
        try (ServerSocket tcp = new ServerSocket()) {
            tcp.setReuseAddress(true);
            tcp.bind(new InetSocketAddress((InetAddress) null, 0));
            return tcp.getLocalPort();
        }
    }

    private static int probeTcpAndUdp() throws IOException {
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
        }
        // Verify TCP on the same number. SO_REUSEADDR matches what netty/libp2p
        // sets when it eventually binds, so TIME_WAIT and recent-close states
        // aren't reported as taken.
        try (ServerSocket tcp = new ServerSocket()) {
            tcp.setReuseAddress(true);
            tcp.bind(new InetSocketAddress((InetAddress) null, port));
        }
        return port;
    }

    /** Reads the claims file, keeping only entries newer than the TTL. */
    private static Map<Integer, Long> readFreshClaims(FileChannel channel, long now) throws IOException {
        Map<Integer, Long> fresh = new LinkedHashMap<>();
        int size = (int) channel.size();
        if (size == 0)
            return fresh;
        ByteBuffer buf = ByteBuffer.allocate(size);
        channel.position(0);
        while (buf.hasRemaining() && channel.read(buf) >= 0)
            ;
        String content = new String(buf.array(), 0, buf.position(), StandardCharsets.UTF_8);
        for (String line : content.split("\n")) {
            line = line.trim();
            if (line.isEmpty())
                continue;
            int sp = line.indexOf(' ');
            if (sp < 0)
                continue;
            try {
                int port = Integer.parseInt(line.substring(0, sp));
                long ts = Long.parseLong(line.substring(sp + 1).trim());
                if (now - ts < CLAIM_TTL_MS)
                    fresh.put(port, ts);
            } catch (NumberFormatException e) {
                // ignore malformed line
            }
        }
        return fresh;
    }

    /** Rewrites the claims file with exactly the given (fresh) entries, so
     *  stale entries are dropped and the file stays small. */
    private static void writeClaims(FileChannel channel, Map<Integer, Long> claims) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Integer, Long> e : claims.entrySet())
            sb.append(e.getKey()).append(' ').append(e.getValue()).append('\n');
        ByteBuffer buf = ByteBuffer.wrap(sb.toString().getBytes(StandardCharsets.UTF_8));
        channel.truncate(0);
        channel.position(0);
        while (buf.hasRemaining())
            channel.write(buf);
        channel.force(true);
    }
}
