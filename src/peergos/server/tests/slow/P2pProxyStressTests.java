package peergos.server.tests.slow;

import com.sun.net.httpserver.*;
import org.junit.*;
import org.junit.runners.MethodSorters;
import peergos.server.*;
import peergos.server.storage.*;
import peergos.server.tests.*;
import peergos.server.tests.util.*;
import peergos.server.util.*;
import peergos.shared.*;
import peergos.shared.io.ipfs.Multihash;
import peergos.shared.storage.*;

import java.io.*;
import java.net.*;
import java.net.URLDecoder;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.junit.Assert.*;

/** Stress the libp2p p2p-http proxy path, looking for the production hang where a node stops
 *  answering p2p requests.
 *
 *  The path under test, end to end:
 *    client -> local gateway (nabu HttpProxyHandler, on a JDK HttpServer)
 *           -> HttpProxyService.proxyRequest  [dial().getController().join(), send().join()]
 *           -> libp2p /http stream
 *           -> remote nabu HttpProtocol.Receiver -> HttpProtocol.proxyRequest
 *           -> remote node's local API server (here, a controllable stand-in)
 *
 *  Both joins in HttpProxyService.proxyRequest are unbounded, so anything that stops a dial
 *  completing or a response arriving parks the handling thread forever while holding its inbound
 *  gateway connection open. {@link #t8_jammedOutboundDoesNotBlockInbound} is the scenario that matches
 *  production: a node whose mirror loop keeps polling a peer that accepts TCP but never completes a
 *  libp2p handshake, i.e. a stale address-book entry pointing at a machine that is up but no longer
 *  running the node.
 *
 *  Everything is localhost and nothing depends on the public DHT. Nodes are started in dependency
 *  order because a node can only dial peers it has bootstrapped to (mDNS discovery is not reliably
 *  available in a sandbox), so requests only ever flow from a later-started node to an earlier one.
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class P2pProxyStressTests {

    static {
        // Must happen before netty loads: NioSocketChannel caches SelectorProvider.provider() in a
        // static final at class-init time, so installing later would trace nothing.
        if (Boolean.getBoolean("stress.tracesockets"))
            SocketTracer.install();
    }

    private static final int HEALTH_TIMEOUT_MILLIS = 30_000;
    /** Bail out of a load phase once the JVM is this deep into the leak. Left to run past it the
     *  process spends all its time in full GC and stops responding at all, which takes many minutes
     *  and tells us nothing more than the count already has. */
    private static final long FD_ABORT_THRESHOLD = 60_000;

    /** started first, bootstraps to nobody: the healthy target everyone else can reach */
    private static Node hub;
    /** reachable to start with, then killed and replaced by a tarpit on its swarm port */
    private static Node ghost;
    /** the node we jam with hung outbound dials - stands in for the PKI / peergos.net */
    private static Node victim;
    /** where most gateway requests are sent from */
    private static Node client;

    private static Tarpit tarpit;
    private static final List<Node> nodes = new ArrayList<>();

    /** -Dstress.only=<phase> runs a single phase. Node startup dominates a run, so this keeps the
     *  methodology identical to a full run while making one phase cheap enough to repeat - e.g. for
     *  bisecting a dependency against the jam phase. */
    private static final String ONLY = System.getProperty("stress.only", "");

    private static void only(String phase) {
        // comma separated, so dependent phases can be run together (torture needs the tarpit that
        // the jam phase installs)
        Assume.assumeTrue("skipped, stress.only=" + ONLY,
                ONLY.isEmpty() || Arrays.asList(ONLY.split(",")).contains(phase));
    }

    /** Transport-level open/close ledger: created minus closed is the number of dial sockets still
     *  held, and ok+failed against created shows how many dials never completed at all. */
    @SuppressWarnings("unchecked")
    private static String dialLedger() {
        Map<String, Long> counts = new TreeMap<>();
        try {
            // reflective: the patched transport is prepended at runtime, not on the compile classpath
            Object ledger = Class.forName("io.libp2p.transport.implementation.PlainNettyTransport")
                    .getField("DIAL_LEDGER").get(null);
            ((Map<String, java.util.concurrent.atomic.AtomicLong>) ledger)
                    .forEach((k, v) -> counts.put(k, v.get()));
        } catch (Throwable t) {
            return "transport {unavailable: " + t.getClass().getSimpleName() + "}";
        }
        long created = counts.getOrDefault("channels-created", 0L);
        long closed = counts.getOrDefault("channels-closed", 0L);
        long done = counts.getOrDefault("connection-ok", 0L) + counts.getOrDefault("connection-failed", 0L);
        return "transport " + counts + " held=" + (created - closed) + " never-completed=" + (created - done);
    }

    /** Loopback forwards to the local API: one per inbound p2p request, the highest volume socket
     *  site in a node. opened minus closed is how many it is still holding. */
    @SuppressWarnings("unchecked")
    private static String forwardLedger() {
        Map<String, Long> counts = new TreeMap<>();
        try {
            Object ledger = Class.forName("org.peergos.protocol.http.HttpProtocol")
                    .getField("FORWARD_LEDGER").get(null);
            ((Map<String, java.util.concurrent.atomic.AtomicLong>) ledger)
                    .forEach((k, v) -> counts.put(k, v.get()));
        } catch (Throwable t) {
            return "forwards {unavailable}";
        }
        return "forwards " + counts + " held="
                + (counts.getOrDefault("opened", 0L) - counts.getOrDefault("closed", 0L));
    }

    /** p2p-http dial volume: the only dial site that runs once per request. */
    private static String proxyDials() {
        return counters("org.peergos.HttpProxyService", "PROXY_COUNTS", "proxy ");
    }

    /** Reads a diagnostic counter map out of nabu reflectively, so the harness still compiles and
     *  runs against a nabu jar that does not carry the instrumentation. Ledgers like these are added
     *  to nabu while chasing something and stripped again before it is committed. */
    @SuppressWarnings("unchecked")
    private static String counters(String className, String field, String label) {
        Map<String, Long> counts = new TreeMap<>();
        try {
            Object ledger = Class.forName(className).getField(field).get(null);
            ((Map<String, java.util.concurrent.atomic.AtomicLong>) ledger)
                    .forEach((k, v) -> counts.put(k, v.get()));
        } catch (Throwable t) {
            return label + "{uninstrumented}";
        }
        return label + counts;
    }

    /** Inbound gateway connections, straight out of the JDK's HttpServer. Nothing else instrumented
     *  here can see these: they are accepted sockets, so they never go through SelectorProvider, and
     *  once a client RSTs its end the kernel unhashes them, so they vanish from /proc/net/tcp while
     *  the fd stays open - which is exactly the NOT-IN-KERNEL-TABLES bucket.
     *  Needs --add-opens jdk.httpserver/sun.net.httpserver=ALL-UNNAMED. */
    private static String gatewayConns() {
        StringBuilder b = new StringBuilder("gateway ");
        for (Node n : nodes) {
            if (n.ipfs == null)
                continue;
            try {
                Object impl = get(n.ipfs, "p2pServer");
                Object server = get(impl, "server");
                b.append(n.name).append("{all=").append(((Collection<?>) get(server, "allConnections")).size())
                        .append(" req=").append(((Collection<?>) get(server, "reqConnections")).size())
                        .append(" rsp=").append(((Collection<?>) get(server, "rspConnections")).size())
                        .append(" idle=").append(((Collection<?>) get(server, "idleConnections")).size())
                        .append(" exchanges=").append(get(server, "exchangeCount")).append("} ");
            } catch (Throwable t) {
                return "gateway {unavailable: " + t + "}";
            }
        }
        return b.toString();
    }

    private static Object get(Object target, String field) throws Exception {
        java.lang.reflect.Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        return f.get(target);
    }

    /** Which branch of Kademlia's dial-failure handling actually fires, and how often. */
    private static String dialOutcomes() {
        return counters("org.peergos.protocol.dht.Kademlia", "DIAL_OUTCOMES", "dht dials ");
    }

    /** -Dstress.noquic=true starts every node with a tcp-only swarm address. The leaked descriptors
     *  are UDP sockets under QUIC channels, so this is the control: if the leak survives with QUIC
     *  off, it isn't the QUIC dial path. */
    private static final boolean NO_QUIC = Boolean.getBoolean("stress.noquic");

    /** -Dstress.torture=<csv> selects which torture workers run; default all. */
    private static final String TORTURE = System.getProperty("stress.torture", "all");

    private static boolean torture(String worker) {
        return TORTURE.equals("all") || Arrays.asList(TORTURE.split(",")).contains(worker);
    }

    @BeforeClass
    public static void init() throws Exception {
        hub = startNode("hub", List.of());
        ghost = startNode("ghost", List.of(hub));
        victim = startNode("victim", List.of(hub, ghost));
        client = startNode("client", List.of(hub, ghost, victim));

        // populate address books in the directions the tests use
        warmUp(client, victim);
        warmUp(client, hub);
        warmUp(victim, hub);
        warmUp(victim, ghost);
    }

    @AfterClass
    public static void cleanup() {
        // A leaking run ends holding tens of thousands of dialled channels, and PlainNettyTransport
        // closes every one of them on stop(), which can take longer than the run itself. The JVM is
        // about to exit anyway, so give shutdown a budget and then move on.
        ExecutorService stoppers = Executors.newCachedThreadPool();
        for (Node n : nodes)
            stoppers.submit(n::stop);
        stoppers.shutdown();
        try {
            if (! stoppers.awaitTermination(30, TimeUnit.SECONDS))
                System.out.println("gave up waiting for the nodes to shut down cleanly");
        } catch (InterruptedException e) {}
        stoppers.shutdownNow();
        try { if (tarpit != null) tarpit.stop(); } catch (Throwable t) {}
        for (Node n : nodes)
            UserTests.deleteFiles(n.args.fromPeergosDir("", "").toFile());
    }

    /** Baseline: a lot of concurrent well behaved requests must all succeed, without leaking fds. */
    @Test
    public void t1_concurrentHappyPath() throws Exception {
        only("fast");
        int threads = 40, perThread = 25;
        long fdsBefore = openFds();
        Stats stats = flood(threads, perThread, () -> p2p(client, victim.id, "/fast", HEALTH_TIMEOUT_MILLIS));
        System.out.println("happy path: " + stats);
        assertEquals("all happy-path p2p requests should succeed", threads * perThread, stats.ok.get());

        assertHealthy("after happy path");
        assertNoFdLeak(reportFds(fdsBefore, threads * perThread, "happy path"), threads * perThread, "happy path");
    }

    /** Requests for a peer id nobody has ever heard of. In production these are the "Target not
     *  found" warnings. They must fail promptly and must not wedge the gateway. */
    @Test
    public void t2_unknownTargetFlood() throws Exception {
        only("unknown");
        long fdsBefore = openFds();
        Multihash unknown = randomPeerId();
        Stats stats = flood(60, 5, () -> p2p(client, unknown, "/fast", HEALTH_TIMEOUT_MILLIS));
        System.out.println("unknown target: " + stats);
        // report the leak numbers before asserting anything, so a phase that fails on latency still
        // tells us what it did to the fd count
        long leaked = reportFds(fdsBefore, 300, "unknown target flood");
        assertEquals("requests to an unknown peer must not succeed", 0, stats.ok.get());
        assertEquals("requests to an unknown peer should be refused, not hang until the client gives"
                + " up (" + stats.timedOut.get() + " client timeouts)", 0, stats.timedOut.get());

        assertHealthy("after unknown-target flood");
        assertNoFdLeak(leaked, 300, "unknown target flood");
    }

    /** Clients that vanish mid-request. The gateway must not accumulate half finished exchanges. */
    @Test
    public void t3_abandonedClients() throws Exception {
        only("abandoned");
        long fdsBefore = openFds();
        String path = "/p2p/" + victim.id + "/http/slow?ms=3000";
        for (int i = 0; i < 300; i++) {
            try (Socket s = new Socket("127.0.0.1", client.gatewayPort)) {
                s.setSoLinger(true, 0); // RST rather than a clean FIN, like a dropped client
                OutputStream out = s.getOutputStream();
                out.write(("POST " + path + " HTTP/1.1\r\nHost: localhost\r\nContent-Length: 0\r\n\r\n")
                        .getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        }
        Thread.sleep(8000); // let the in-flight requests finish with nobody listening

        assertHealthy("after abandoned clients");
        assertNoFdLeak(reportFds(fdsBefore, 300, "abandoned clients"), 300, "abandoned clients");
    }

    /** A remote whose local API never responds. HttpProtocol's ReadTimeoutHandler is meant to cap
     *  this at 60s; without it the responder side waits forever and so does everyone behind it. */
    @Test
    public void t4_unresponsiveRemoteApi() throws Exception {
        only("unresponsive");
        long fdsBefore = openFds();
        // 100s is longer than HttpProtocol's 60s PROXY_RESPONSE_TIMEOUT_SECONDS, so a bounded wait
        // comes back as a 502 while an unbounded one shows up here as a client timeout
        Stats stats = flood(20, 1, () -> p2p(client, victim.id, "/hang", 100_000));
        System.out.println("unresponsive remote api: " + stats);
        assertEquals("a remote API that never replies must not produce a 200", 0, stats.ok.get());
        assertEquals("requests to an unresponsive remote should come back as errors, not hang past"
                + " the response timeout (" + stats.timedOut.get() + " client timeouts)", 0, stats.timedOut.get());

        assertHealthy("after unresponsive remote api");
        assertNoFdLeak(reportFds(fdsBefore, 20, "unresponsive remote api"), 20, "unresponsive remote api");
    }

    /* The next three isolate one traffic shape each. t1 pushes 1000 requests but finishes in under
     * two seconds, so it measures a burst; these run flat out for a minute, which is what separates
     * churning file descriptors from accumulating them. Between them they cover what the mixed
     * phase does that the passing phases don't: sustained duration, long-lived requests, and bodies
     * large enough to matter to the aggregator. */

    @Test
    public void t5_sustainedSmallRequests() throws Exception {
        only("small");
        long leaked = sustained("sustained fast", 20,
                () -> p2p(client, victim.id, "/fast", HEALTH_TIMEOUT_MILLIS), 60);
        assertHealthy("after sustained small requests");
        assertTrue("a minute of small proxied requests left " + leaked + " file descriptors held", leaked < 500);
    }

    @Test
    public void t6_sustainedSlowRequests() throws Exception {
        only("slow");
        long leaked = sustained("sustained slow", 10,
                () -> p2p(client, victim.id, "/slow?ms=2000", HEALTH_TIMEOUT_MILLIS), 60);
        assertHealthy("after sustained slow requests");
        assertTrue("a minute of slow proxied requests left " + leaked + " file descriptors held", leaked < 500);
    }

    @Test
    public void t7_sustainedLargeResponses() throws Exception {
        only("big");
        long leaked = sustained("sustained big", 5,
                () -> p2p(client, victim.id, "/big?len=4000000", HEALTH_TIMEOUT_MILLIS), 60);
        assertHealthy("after sustained large responses");
        assertTrue("a minute of 4MB proxied responses left " + leaked + " file descriptors held", leaked < 500);
    }

    /** The production scenario. The ghost's address-book entry still looks valid - something is
     *  listening on its swarm port - but it no longer completes a libp2p handshake, so every dial to
     *  it hangs forever. The victim keeps polling it, the way MirrorCoreNode polls a mirrored user's
     *  home node once a minute. Does the victim's jammed outbound traffic stop it answering inbound
     *  p2p requests, and does it stop its own gateway serving healthy peers? */
    @Test
    public void t8_jammedOutboundDoesNotBlockInbound() throws Exception {
        only("jam");
        int swarmPort = ghost.args.getInt("ipfs-swarm-port");
        ghost.stop();
        Thread.sleep(2000);
        tarpit = new Tarpit(swarmPort);
        System.out.println("tarpit listening on " + swarmPort + " in place of " + ghost.id);

        long fdsBefore = openFds();
        AtomicBoolean stop = new AtomicBoolean(false);
        AtomicInteger inFlight = new AtomicInteger(0);
        ExecutorService jammers = Executors.newCachedThreadPool();
        int jamCount = 200;
        for (int i = 0; i < jamCount; i++)
            jammers.submit(() -> {
                while (! stop.get()) {
                    inFlight.incrementAndGet();
                    p2p(victim, ghost.id, "/fast", 120_000);
                    inFlight.decrementAndGet();
                }
            });

        try {
            Thread.sleep(20_000); // let the jam build up
            System.out.println("jam: " + inFlight.get() + " in-flight dials to the tarpit, fds "
                    + fdsBefore + " -> " + openFds() + ", of which " + tarpit.held()
                    + " are the tarpit's own accepted sockets, " + liveP2p() + ", " + dialOutcomes() + ", " + proxyDials() + ", " + dialLedger() + ", " + forwardLedger());
            // This is the cleanest isolated reproducer - only tarpit dials, no other traffic - so
            // take the heap census here, while the leaked channels are still reachable.
            captureHeapHistogram("jam");

            // the victim must still answer inbound p2p requests while its own outbound is jammed
            for (int i = 0; i < 10; i++) {
                Response r = p2p(client, victim.id, "/fast", HEALTH_TIMEOUT_MILLIS);
                if (r.status != 200) {
                    dumpThreads();
                    fail("the victim stopped answering inbound p2p requests while its outbound dials"
                            + " were jammed (attempt " + i + "): " + r);
                }
            }
            // ...and its own gateway must still serve requests for a healthy peer
            Response toHub = p2p(victim, hub.id, "/fast", HEALTH_TIMEOUT_MILLIS);
            if (toHub.status != 200) {
                dumpThreads();
                fail("the victim's gateway stopped serving healthy targets while jammed: " + toHub);
            }

            long leaked = openFds() - fdsBefore;
            System.out.println("fds held by " + inFlight.get() + " jammed dials: " + leaked);
            assertTrue("jammed dials are consuming file descriptors without bound: " + leaked
                    + " extra fds for " + jamCount + " concurrent requests", leaked < 4 * jamCount);
        } finally {
            stop.set(true);
            jammers.shutdownNow();
        }
    }

    /** Everything at once, sustained, then check the node is still alive and hasn't leaked. */
    @Test
    public void t9_mixedTorture() throws Exception {
        only("torture");
        long fdsBefore = openFds();
        long deadline = System.currentTimeMillis() + 60_000;
        AtomicBoolean stop = new AtomicBoolean(false);
        Multihash unknown = randomPeerId();
        ExecutorService pool = Executors.newCachedThreadPool();

        // -Dstress.torture=<csv> restricts which workers run, for attributing the leak to one
        // traffic shape. Default is all of them.
        System.out.println("torture workers: " + TORTURE);
        if (torture("fast"))
            repeat(pool, stop, 20, () -> p2p(client, victim.id, "/fast", HEALTH_TIMEOUT_MILLIS));
        if (torture("slow"))
            repeat(pool, stop, 10, () -> p2p(client, victim.id, "/slow?ms=2000", HEALTH_TIMEOUT_MILLIS));
        if (torture("big"))
            repeat(pool, stop, 5, () -> p2p(client, victim.id, "/big?len=4000000", HEALTH_TIMEOUT_MILLIS));
        if (torture("unknown"))
            repeat(pool, stop, 10, () -> p2p(client, unknown, "/fast", HEALTH_TIMEOUT_MILLIS));
        if (torture("victimhub"))
            repeat(pool, stop, 5, () -> p2p(victim, hub.id, "/fast", HEALTH_TIMEOUT_MILLIS));
        // clients that give up half way through
        if (torture("abandoned"))
        repeat(pool, stop, 3, () -> {
            try (Socket s = new Socket("127.0.0.1", client.gatewayPort)) {
                s.setSoLinger(true, 0);
                s.getOutputStream().write(("POST /p2p/" + victim.id + "/http/slow?ms=3000 HTTP/1.1\r\n"
                        + "Host: localhost\r\nContent-Length: 0\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                s.getOutputStream().flush();
            } catch (IOException e) {}
            return null;
        });

        boolean aborted = false;
        boolean profiled = false;
        try {
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(2_000);
                long fds = openFds();
                System.out.println("torture: " + (deadline - System.currentTimeMillis()) / 1000 + "s left, fds "
                        + fdsBefore + " -> " + fds + " (tarpit holds " + tarpit.held() + "), "
                        + socketsAllocated() + ", " + liveP2p() + ", " + dialOutcomes() + ", " + proxyDials() + ", " + dialLedger() + ", " + forwardLedger()
                        + ", " + SocketTracer.summary() + ", " + gatewayConns());
                if (fds - fdsBefore > 25_000 && ! profiled) {
                    // capture while the leak is live and still reachable
                    profiled = true;
                    captureHeapHistogram("torture");
                    captureLeakProfile("torture");
                }
                if (fds > FD_ABORT_THRESHOLD) {
                    aborted = true;
                    break;
                }
            }
        } finally {
            stop.set(true);
            pool.shutdownNow();
        }
        // fds churn under load, so what matters is whether they come back afterwards
        long settled = fdsBefore;
        for (int i = 0; i < 15; i++) {
            Thread.sleep(2_000);
            settled = openFds();
            if (settled - fdsBefore < 1000)
                break;
        }
        long leaked = settled - fdsBefore;
        System.out.println("torture: fds " + fdsBefore + " -> " + settled + " after draining ("
                + leaked + " leaked, tarpit holds " + tarpit.held() + "), " + socketsAllocated());
        System.out.println("torture fd attribution: " + fdBreakdown());
        System.out.println(SocketTracer.report(15));
        assertFalse("sustained p2p proxy load ran the fd count past " + FD_ABORT_THRESHOLD
                + " in under a minute (" + leaked + " still held after draining, " + socketsAllocated()
                + "); the proxy is allocating sockets faster than it releases them", aborted);
        assertHealthy("after mixed torture");
        assertTrue("sustained mixed load leaked " + leaked + " file descriptors", leaked < 1000);
    }

    // ---------------------------------------------------------------- nodes

    /** One nabu node plus the stand-in local API server it proxies inbound p2p requests to. */
    private static class Node {
        final String name;
        final Args args;
        final int gatewayPort;
        final Backend backend;
        IpfsWrapper ipfs;
        Multihash id;

        Node(String name, List<Node> bootstrap) throws IOException {
            this.name = name;
            Path dir = Files.createTempDirectory("peergos-p2p-stress-" + name + "-");
            Args a = UserTests.buildArgs()
                    .with(Main.PEERGOS_PATH, dir.toString())
                    .with("useIPFS", "true")
                    .with("async-bootstrap", bootstrap.isEmpty() ? "true" : "false")
                    .with("proxy-target", Main.getLocalMultiAddress(TestPorts.getPort()).toString());
            if (NO_QUIC)
                // nabu turns QUIC on iff a swarm address carries quic-v1 (EmbeddedPeer.build), so
                // listing only the tcp address disables the QUIC transport for listening and dialling
                a = a.with("ipfs-swarm-addrs", "/ip6/::/tcp/" + a.getInt("ipfs-swarm-port"));
            this.args = bootstrap.isEmpty() ?
                    a.removeArg(IpfsWrapper.IPFS_BOOTSTRAP_NODES) :
                    a.with(IpfsWrapper.IPFS_BOOTSTRAP_NODES, bootstrap.stream()
                            .map(n -> Main.getLocalBootstrapAddress(n.args.getInt("ipfs-swarm-port"), n.id).toString())
                            .reduce((x, y) -> x + "," + y).get());
            this.gatewayPort = tcpPort(args, "ipfs-gateway-address");
            this.backend = new Backend(tcpPort(args, "proxy-target"));
        }

        void start() {
            ipfs = Main.IPFS.main(args);
            id = new ContentAddressedStorage.HTTP(Builder.buildIpfsApi(args), false, crypto.hasher).id().join();
        }

        void stop() {
            try { if (ipfs != null) ipfs.stop(); } catch (Throwable t) {}
            try { backend.stop(); } catch (Throwable t) {}
            ipfs = null;
        }

        public String toString() {
            return name + "(" + id + ")";
        }
    }

    private static Node startNode(String name, List<Node> bootstrap) throws IOException {
        Node n = new Node(name, bootstrap);
        nodes.add(n);
        n.start();
        System.out.println("started " + n + " gateway=" + n.gatewayPort
                + " swarm=" + n.args.getInt("ipfs-swarm-port"));
        return n;
    }

    private static int tcpPort(Args a, String arg) {
        return new peergos.shared.io.ipfs.MultiAddress(a.getArg(arg)).getTCPPort();
    }

    // ---------------------------------------------------------------- helpers

    private static final Crypto crypto = Main.initCrypto();

    /** First contact has to find the peer and negotiate a connection, so allow a few attempts
     *  before declaring the setup broken. */
    private static void warmUp(Node from, Node to) {
        Response last = null;
        for (int i = 0; i < 5; i++) {
            last = p2p(from, to.id, "/fast", HEALTH_TIMEOUT_MILLIS);
            System.out.println("warm up " + from.name + " -> " + to.name + " attempt " + i + ": " + last);
            if (last.status == 200)
                return;
        }
        dumpThreads();
        fail("couldn't establish a p2p connection " + from.name + " -> " + to.name + ": " + last);
    }

    private static void assertHealthy(String when) {
        Response r = p2p(client, victim.id, "/fast", HEALTH_TIMEOUT_MILLIS);
        if (r.status != 200) {
            dumpThreads();
            fail("the node is no longer serving p2p requests " + when + ": " + r);
        }
    }

    /** Prints the fd delta for a phase and returns it, or -1 where fd counts aren't visible. */
    private static long reportFds(long before, int requests, String phase) {
        if (before < 0)
            return -1;
        long after = openFds();
        System.out.println(phase + ": fds " + before + " -> " + after + " over " + requests
                + " requests, " + socketsAllocated() + ", " + liveP2p() + ", " + dialOutcomes() + ", " + proxyDials() + ", " + dialLedger() + ", " + forwardLedger());
        return after - before;
    }

    private static void assertNoFdLeak(long leaked, int requests, String phase) {
        if (leaked < 0)
            return; // fd counts aren't visible on this platform
        // some growth is expected (kept-alive libp2p connections, growing pools); growth
        // proportional to the request count is a leak
        assertTrue(phase + " leaked " + leaked + " file descriptors over " + requests + " requests",
                leaked < Math.max(200, requests / 4));
    }

    /** Run one traffic pattern flat out for a while, then report what it left behind once the
     *  in-flight work has drained. Returns the fds still held afterwards. */
    private static long sustained(String name, int workers, Callable<Response> task, int seconds) throws Exception {
        long before = openFds();
        AtomicLong count = new AtomicLong(), ok = new AtomicLong();
        AtomicReference<String> lastError = new AtomicReference<>();
        AtomicBoolean stop = new AtomicBoolean(false);
        ExecutorService pool = Executors.newCachedThreadPool();
        repeat(pool, stop, workers, () -> {
            count.incrementAndGet();
            Response r = task.call();
            // a phase whose requests are all failing fast isn't testing the pattern it claims to
            if (r.status == 200)
                ok.incrementAndGet();
            else
                lastError.set(r.toString());
            return r;
        });
        long peak = before;
        try {
            long deadline = System.currentTimeMillis() + seconds * 1000L;
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(2_000);
                peak = Math.max(peak, openFds());
                if (peak > FD_ABORT_THRESHOLD)
                    break;
            }
        } finally {
            stop.set(true);
            pool.shutdownNow();
        }
        long settled = before;
        for (int i = 0; i < 15; i++) {
            Thread.sleep(2_000);
            settled = openFds();
            if (settled - before < 200)
                break;
        }
        System.out.println(name + ": " + count.get() + " reqs over " + seconds + "s (" + ok.get()
                + " ok), fds " + before + " -> peak " + peak + " -> " + settled + " settled, "
                + socketsAllocated() + ", " + liveP2p() + ", " + dialOutcomes() + ", " + proxyDials() + ", " + dialLedger() + ", " + forwardLedger() + (lastError.get() == null ? "" : ", e.g. " + lastError.get()));
        assertTrue(name + ": only " + ok.get() + " of " + count.get() + " requests succeeded, so this"
                + " phase isn't exercising the pattern it claims to. e.g. " + lastError.get(),
                ok.get() > count.get() / 2);
        return settled - before;
    }

    private static void repeat(ExecutorService pool, AtomicBoolean stop, int workers, Callable<?> task) {
        for (int i = 0; i < workers; i++)
            pool.submit(() -> {
                while (! stop.get()) {
                    try {
                        task.call();
                    } catch (Exception e) {}
                }
            });
    }

    private static Stats flood(int threads, int perThread, Callable<Response> req) throws Exception {
        Stats stats = new Stats();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<?>> futures = new ArrayList<>();
        long t0 = System.currentTimeMillis();
        for (int t = 0; t < threads; t++)
            futures.add(pool.submit(() -> {
                for (int i = 0; i < perThread; i++) {
                    Response r = req.call();
                    if (r.status == 200)
                        stats.ok.incrementAndGet();
                    else if (r.timedOut)
                        stats.timedOut.incrementAndGet();
                    else
                        stats.errored.incrementAndGet();
                    stats.totalMillis.addAndGet(r.millis);
                    stats.maxMillis.accumulateAndGet(r.millis, Math::max);
                    stats.lastError.compareAndSet(null, r.status == 200 ? null : r.toString());
                }
                return null;
            }));
        for (Future<?> f : futures)
            f.get();
        pool.shutdown();
        stats.wallMillis = System.currentTimeMillis() - t0;
        return stats;
    }

    private static class Stats {
        final AtomicInteger ok = new AtomicInteger(), errored = new AtomicInteger(), timedOut = new AtomicInteger();
        final AtomicLong totalMillis = new AtomicLong(), maxMillis = new AtomicLong();
        final AtomicReference<String> lastError = new AtomicReference<>();
        long wallMillis;

        public String toString() {
            int n = ok.get() + errored.get() + timedOut.get();
            return n + " reqs in " + wallMillis + "ms, ok=" + ok + " err=" + errored + " clientTimeout=" + timedOut
                    + ", mean=" + (n == 0 ? 0 : totalMillis.get() / n) + "ms max=" + maxMillis + "ms"
                    + (lastError.get() == null ? "" : ", e.g. " + lastError.get());
        }
    }

    private static class Response {
        final int status;
        final boolean timedOut;
        final long millis;
        final String detail;

        Response(int status, boolean timedOut, long millis, String detail) {
            this.status = status;
            this.timedOut = timedOut;
            this.millis = millis;
            this.detail = detail;
        }

        public String toString() {
            return "status=" + status + (timedOut ? " CLIENT-TIMEOUT" : "") + " in " + millis + "ms"
                    + (detail.isEmpty() ? "" : " (" + detail + ")");
        }
    }

    /** A p2p proxied request sent through {@code from}'s gateway to {@code target}. */
    private static Response p2p(Node from, Multihash target, String path, int timeoutMillis) {
        long t0 = System.currentTimeMillis();
        HttpURLConnection conn = null;
        try {
            URL url = new URL("http://127.0.0.1:" + from.gatewayPort + "/p2p/" + target + "/http" + path);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(5_000);
            conn.setReadTimeout(timeoutMillis);
            // nabu's gateway only accepts POST (HttpUtil.allowedQuery); anything else gets a 403
            // with an unterminated chunked body, which just hangs the caller
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setFixedLengthStreamingMode(0);
            conn.getOutputStream().close();
            int status = conn.getResponseCode();
            InputStream in = status < 400 ? conn.getInputStream() : conn.getErrorStream();
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            if (in != null) {
                byte[] buf = new byte[16384];
                for (int r = in.read(buf); r >= 0; r = in.read(buf))
                    if (status >= 400 && body.size() < 400)
                        body.write(buf, 0, r);
                in.close();
            }
            // nabu reports the failure in a url-encoded Trailer header (HttpUtil.replyError), not in
            // the body, which is always empty
            String detail = "";
            if (status >= 400) {
                String trailer = conn.getHeaderField("Trailer");
                if (trailer != null)
                    detail = URLDecoder.decode(trailer, StandardCharsets.UTF_8);
                String bodyText = new String(body.toByteArray(), StandardCharsets.UTF_8).trim();
                if (! bodyText.isEmpty())
                    detail = detail.isEmpty() ? bodyText : detail + " / " + bodyText;
            }
            return new Response(status, false, System.currentTimeMillis() - t0, detail);
        } catch (SocketTimeoutException e) {
            return new Response(-1, true, System.currentTimeMillis() - t0, "read timeout");
        } catch (Exception e) {
            return new Response(-1, false, System.currentTimeMillis() - t0,
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            if (conn != null)
                conn.disconnect();
        }
    }

    private static Multihash randomPeerId() {
        byte[] key = new byte[32];
        new Random().nextBytes(key);
        return new peergos.shared.io.ipfs.Cid(1, peergos.shared.io.ipfs.Cid.Codec.LibP2pKey,
                Multihash.Type.id, ed25519PubKeyProto(key));
    }

    /** a protobuf encoded ed25519 public key, the payload of an identity multihash peer id */
    private static byte[] ed25519PubKeyProto(byte[] rawKey) {
        byte[] res = new byte[rawKey.length + 4];
        res[0] = 0x08; res[1] = 0x01; // field 1 (Type) = Ed25519
        res[2] = 0x12; res[3] = (byte) rawKey.length; // field 2 (Data), length delimited
        System.arraycopy(rawKey, 0, res, 4, rawKey.length);
        return res;
    }

    /** number of open file descriptors for this JVM, or -1 where that isn't visible */
    private static long openFds() {
        try {
            Path fds = Paths.get("/proc/self/fd");
            if (! Files.isDirectory(fds))
                return -1;
            try (var s = Files.list(fds)) {
                return s.count();
            }
        } catch (IOException e) {
            return -1;
        }
    }

    /** Live libp2p streams and connections per node. Streams are the thing suspected of not being
     *  released - HttpProtocol closes a stream only when a response arrives - and a stream count
     *  that climbs while the connection count stays flat is what a stream leak looks like.
     *  Read reflectively so the diagnostic stays in the harness instead of in production code. */
    private static String liveP2p() {
        StringBuilder res = new StringBuilder("p2p ");
        for (Node n : nodes) {
            if (n.ipfs == null) {
                res.append(n.name).append("{stopped} ");
                continue;
            }
            try {
                java.lang.reflect.Field f = IpfsWrapper.class.getDeclaredField("embeddedIpfs");
                f.setAccessible(true);
                Object peer = f.get(n.ipfs);
                Object host = peer.getClass().getField("node").get(peer);
                int streams = ((Collection<?>) host.getClass().getMethod("getStreams").invoke(host)).size();
                Object network = host.getClass().getMethod("getNetwork").invoke(host);
                int conns = ((Collection<?>) network.getClass().getMethod("getConnections").invoke(network)).size();
                res.append(n.name).append("{streams=").append(streams).append(",conns=").append(conns).append("} ");
            } catch (Exception e) {
                res.append(n.name).append("{").append(e.getClass().getSimpleName()).append("} ");
            }
        }
        return res.toString().trim();
    }

    /** Attribute this JVM's socket descriptors by looking each one's inode up in the kernel's socket
     *  tables. Buckets by protocol, local port and TCP state; anything not present in any table is a
     *  socket that was created and never bound or connected. Aggregate fd counts can't distinguish
     *  the node's sockets from the harness's, and this can. */
    private static String fdBreakdown() {
        try {
            Map<String, String> inodeToDesc = new HashMap<>();
            for (String proto : List.of("tcp", "tcp6", "udp", "udp6")) {
                Path table = Paths.get("/proc/net/" + proto);
                if (! Files.exists(table))
                    continue;
                List<String> lines = Files.readAllLines(table);
                for (String line : lines.subList(Math.min(1, lines.size()), lines.size())) {
                    String[] f = line.trim().split("\\s+");
                    if (f.length < 10)
                        continue;
                    String localPort = String.valueOf(Integer.parseInt(f[1].split(":")[1], 16));
                    String state = proto.startsWith("tcp") ? TCP_STATES.getOrDefault(f[3], f[3]) : "";
                    inodeToDesc.put(f[9], proto + ":" + localPort + (state.isEmpty() ? "" : "/" + state));
                }
            }
            Map<String, Integer> buckets = new TreeMap<>();
            try (var fds = Files.list(Paths.get("/proc/self/fd"))) {
                for (Path fd : (Iterable<Path>) fds::iterator) {
                    String target;
                    try {
                        target = Files.readSymbolicLink(fd).toString();
                    } catch (IOException e) {
                        continue; // fd closed while we walked
                    }
                    if (! target.startsWith("socket:["))
                        continue;
                    String inode = target.substring(8, target.length() - 1);
                    buckets.merge(inodeToDesc.getOrDefault(inode, "NOT-IN-KERNEL-TABLES"), 1, Integer::sum);
                }
            }
            return buckets.entrySet().stream()
                    .sorted((a, b) -> b.getValue() - a.getValue())
                    .limit(12)
                    .map(e -> e.getValue() + "x " + e.getKey())
                    .reduce((a, b) -> a + ", " + b)
                    .orElse("(no sockets)");
        } catch (Exception e) {
            return "fd breakdown unavailable: " + e;
        }
    }

    private static final Map<String, String> TCP_STATES = Map.ofEntries(
            Map.entry("01", "ESTAB"), Map.entry("02", "SYN_SENT"), Map.entry("03", "SYN_RECV"),
            Map.entry("04", "FIN_WAIT1"), Map.entry("05", "FIN_WAIT2"), Map.entry("06", "TIME_WAIT"),
            Map.entry("07", "CLOSE"), Map.entry("08", "CLOSE_WAIT"), Map.entry("09", "LAST_ACK"),
            Map.entry("0A", "LISTEN"), Map.entry("0B", "CLOSING"));

    /** System-wide TCP socket accounting. "alloc" far exceeding "inuse" means sockets have been
     *  created and abandoned before ever reaching a connected state - the signature of netty
     *  channels built by Bootstrap.connect that are never connected and never closed. */
    private static String socketsAllocated() {
        try {
            for (String line : Files.readAllLines(Paths.get("/proc/net/sockstat")))
                if (line.startsWith("TCP:"))
                    return line;
        } catch (IOException e) {}
        return "TCP: (unavailable)";
    }

    private static void dumpThreads() {
        Map<String, Integer> byName = new TreeMap<>();
        for (Thread t : Thread.getAllStackTraces().keySet())
            byName.merge(t.getName().replaceAll("[0-9]+", "#"), 1, Integer::sum);
        System.out.println("platform threads by name: " + byName);
        System.out.println("open fds: " + openFds());
        dumpParkedProxyThreads();
    }

    /** Dump the flight recording started on the command line, with reference chains. A histogram
     *  says what is leaking; OldObjectSample says who allocated it and what still retains it, which
     *  is the question a class count cannot answer. Requires the JVM to have been started with
     *  -XX:StartFlightRecording=name=leak,settings=profile . */
    private static void captureLeakProfile(String tag) {
        try {
            Path out = Paths.get(System.getProperty("java.io.tmpdir"), "p2p-stress-leak-" + tag + ".jfr");
            Files.deleteIfExists(out);
            Process p = new ProcessBuilder("jcmd", "" + ProcessHandle.current().pid(),
                    "JFR.dump", "name=leak", "filename=" + out, "path-to-gc-roots=true")
                    .redirectErrorStream(true).start();
            String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            p.waitFor(5, TimeUnit.MINUTES);
            System.out.println("leak profile (" + tag + "): " + output.trim());
        } catch (Exception e) {
            System.out.println("couldn't dump a leak profile: " + e);
        }
    }

    /** Class histogram of the live heap, for finding what is holding the leaked channels. -histo:live
     *  forces a full GC first, so anything it lists is genuinely reachable rather than uncollected
     *  garbage. Written next to the run's log so it survives the JVM. */
    private static void captureHeapHistogram(String tag) {
        try {
            Path out = Paths.get(System.getProperty("java.io.tmpdir"), "p2p-stress-histo-" + tag + ".txt");
            Process p = new ProcessBuilder("jmap", "-histo:live", "" + ProcessHandle.current().pid())
                    .redirectOutput(out.toFile()).redirectErrorStream(false).start();
            if (! p.waitFor(10, TimeUnit.MINUTES)) {
                System.out.println("heap histogram timed out");
                return;
            }
            System.out.println("heap histogram (" + tag + ") written to " + out + ", top entries:");
            List<String> lines = Files.readAllLines(out);
            for (String line : lines.subList(0, Math.min(25, lines.size())))
                System.out.println("  " + line);
            // and the netty/libp2p classes specifically, wherever they rank
            for (String line : lines)
                if (line.contains("netty.channel") || line.contains("libp2p") || line.contains("org.peergos"))
                    System.out.println("  * " + line);
        } catch (Exception e) {
            System.out.println("couldn't capture a heap histogram: " + e);
        }
    }

    /** The gateway handler pool is virtual threads, which don't appear in Thread.getAllStackTraces,
     *  so ask the JVM for a full dump and tally where the proxy requests are parked. That's what
     *  separates "stuck dialling" from "stuck waiting for a response". */
    private static void dumpParkedProxyThreads() {
        try {
            Path out = Files.createTempFile("p2p-stress-threads-", ".txt");
            Files.deleteIfExists(out); // Thread.dump_to_file won't overwrite
            Process p = new ProcessBuilder("jcmd", "" + ProcessHandle.current().pid(),
                    "Thread.dump_to_file", out.toString()).redirectErrorStream(true).start();
            if (! p.waitFor(60, TimeUnit.SECONDS) || ! Files.exists(out)) {
                System.out.println("couldn't take a thread dump (is jcmd on the path?)");
                return;
            }
            Map<String, Integer> parkedIn = new TreeMap<>();
            List<String> block = new ArrayList<>();
            int stacks = 0;
            for (String line : Files.readAllLines(out)) {
                if (line.isBlank()) {
                    stacks += tally(block, parkedIn);
                    block.clear();
                } else
                    block.add(line.trim());
            }
            stacks += tally(block, parkedIn);
            System.out.println("stacks inside the p2p proxy: " + stacks + " " + parkedIn);
            Files.deleteIfExists(out);
        } catch (Exception e) {
            System.out.println("couldn't take a thread dump: " + e);
        }
    }

    /** Records the outermost interesting frame of a stack that is inside the proxy path. */
    private static int tally(List<String> block, Map<String, Integer> parkedIn) {
        boolean inProxy = block.stream().anyMatch(l -> l.contains("HttpProxyService")
                || l.contains("HttpProtocol") || l.contains("HttpProxyHandler"));
        if (! inProxy)
            return 0;
        String where = block.stream()
                .filter(l -> l.startsWith("at ") && (l.contains("org.peergos") || l.contains("io.libp2p")))
                .findFirst()
                .orElse("at ?");
        parkedIn.merge(where.substring(3), 1, Integer::sum);
        return 1;
    }

    /** Stands in for a node's local API server, with endpoints that misbehave on demand. */
    private static class Backend {
        private final HttpServer server;

        Backend(int port) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 500);
            server.createContext("/", ex -> {
                try {
                    String path = ex.getRequestURI().getPath();
                    Map<String, String> q = query(ex.getRequestURI().getRawQuery());
                    drain(ex.getRequestBody());
                    switch (path) {
                        case "/fast": {
                            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
                            ex.sendResponseHeaders(200, body.length);
                            ex.getResponseBody().write(body);
                            break;
                        }
                        case "/slow": {
                            Thread.sleep(Long.parseLong(q.getOrDefault("ms", "1000")));
                            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
                            ex.sendResponseHeaders(200, body.length);
                            ex.getResponseBody().write(body);
                            break;
                        }
                        case "/big": {
                            byte[] body = new byte[Integer.parseInt(q.getOrDefault("len", "1000000"))];
                            ex.sendResponseHeaders(200, body.length);
                            ex.getResponseBody().write(body);
                            break;
                        }
                        case "/hang": {
                            Thread.sleep(300_000); // never reply within the life of a test
                            break;
                        }
                        default:
                            ex.sendResponseHeaders(404, -1);
                    }
                } catch (InterruptedException e) {
                    // shutting down
                } catch (IOException e) {
                    // the client went away
                } finally {
                    ex.close();
                }
            });
            server.setExecutor(Threads.newPool(200, "stress-backend-"));
            server.start();
        }

        void stop() {
            server.stop(0);
        }

        private static void drain(InputStream in) throws IOException {
            byte[] buf = new byte[16384];
            while (in.read(buf) >= 0) {}
            in.close();
        }

        private static Map<String, String> query(String raw) {
            Map<String, String> res = new HashMap<>();
            if (raw == null)
                return res;
            for (String pair : raw.split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0)
                    res.put(pair.substring(0, eq), pair.substring(eq + 1));
            }
            return res;
        }
    }

    /** Accepts TCP connections on a dead peer's swarm port and then says nothing, so libp2p dials
     *  connect but never complete a handshake. This is what a stale address-book entry looks like
     *  when the machine is up but the node isn't. */
    private static class Tarpit {
        private final ServerSocket socket;
        private final List<Socket> held = Collections.synchronizedList(new ArrayList<>());
        private volatile boolean running = true;

        /** The tarpit runs in the same JVM whose fds we count, and deliberately never closes an
         *  accepted connection - so each dial to it costs two fds in our measurements, one of which
         *  is the harness itself rather than the node under test. Report it so the two can be
         *  separated. */
        int held() {
            return held.size();
        }

        Tarpit(int port) throws IOException {
            socket = new ServerSocket(port, 1000);
            Thread acceptor = new Thread(() -> {
                while (running) {
                    try {
                        held.add(socket.accept());
                    } catch (IOException e) {
                        if (! running)
                            return;
                    }
                }
            }, "p2p-stress-tarpit");
            acceptor.setDaemon(true);
            acceptor.start();
        }

        void stop() throws IOException {
            running = false;
            socket.close();
            synchronized (held) {
                for (Socket s : held)
                    try { s.close(); } catch (IOException e) {}
            }
        }
    }
}
