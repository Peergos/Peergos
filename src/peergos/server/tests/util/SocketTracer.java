package peergos.server.tests.util;

import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.channels.*;
import java.nio.channels.spi.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.stream.*;

/** Records the java stack that created every NIO socket, so a descriptor leak can be attributed to
 *  the code that opened it rather than inferred.
 *
 *  Every unbound TCP socket in the JVM comes from {@link SelectorProvider#openSocketChannel()} - it is
 *  the only way netty, the JDK http client, and libp2p can get one. Replacing the singleton provider
 *  with a delegating one therefore sees every creation, including the ones that are invisible to
 *  {@code ss} (never bound, so in no kernel table) and to {@code jmap -histo:live} (garbage collected
 *  without ever being closed, which is exactly what leaks the fd).
 *
 *  Channels are held strongly on purpose: a leaked channel is unreachable garbage by the time we want
 *  to ask about it, so the only way to still have the answer at the end of a run is to keep it.
 *
 *  Requires {@code --add-opens java.base/java.nio.channels.spi=ALL-UNNAMED} and must be installed
 *  before netty loads, because {@code NioSocketChannel} caches {@code SelectorProvider.provider()} in
 *  a static final field at class-init time.
 */
public class SocketTracer {

    private static volatile boolean installed = false;
    /** channel -> creation site. Channel has no equals/hashCode override, so this is identity keyed. */
    private static final Map<Channel, String> origins = new ConcurrentHashMap<>();
    /** creation site -> total ever opened, whether still open or not. */
    private static final Map<String, AtomicLong> opens = new ConcurrentHashMap<>();
    /** canonicalises site strings so origins only holds one reference per distinct stack. */
    private static final Map<String, String> sites = new ConcurrentHashMap<>();

    public static boolean isInstalled() {
        return installed;
    }

    /** Forces the JDK to resolve its SelectorProvider now and checks we are it. The provider is a
     *  {@code static final} in a holder class, so it cannot be swapped after the fact - it has to be
     *  named on the command line:
     *  <pre>
     *  -Djava.nio.channels.spi.SelectorProvider=peergos.server.tests.util.SocketTracer$Tracing
     *  --add-exports java.base/sun.nio.ch=ALL-UNNAMED
     *  </pre> */
    public static synchronized void install() {
        SelectorProvider provider = SelectorProvider.provider();
        if (! (provider instanceof Tracing))
            throw new IllegalStateException("socket tracer is not the selector provider (got "
                    + provider.getClass().getName() + ") - set -Djava.nio.channels.spi.SelectorProvider="
                    + Tracing.class.getName().replace('$', '$'));
    }

    private static void record(Channel ch, String kind) {
        StringBuilder b = new StringBuilder(kind);
        StackWalker.getInstance().walk(frames -> {
            frames.skip(2).limit(24)
                    .filter(fr -> ! fr.getClassName().equals(SocketTracer.class.getName())
                            && ! fr.getClassName().startsWith("peergos.server.tests.util.SocketTracer"))
                    .forEach(fr -> b.append('\n')
                            .append("      at ").append(fr.getClassName()).append('.')
                            .append(fr.getMethodName()).append(':').append(fr.getLineNumber()));
            return null;
        });
        String site = b.toString();
        String canonical = sites.computeIfAbsent(site, s -> s);
        opens.computeIfAbsent(canonical, s -> new AtomicLong()).incrementAndGet();
        origins.put(ch, canonical);
    }

    /** Number of traced channels still open. Comparable with the fd counts the harness prints. */
    public static long stillOpen() {
        return origins.keySet().stream().filter(Channel::isOpen).count();
    }

    /** Sites ranked by how many of the channels they created are still open. */
    public static String report(int topSites) {
        if (! installed)
            return "socket tracer: not installed";
        Map<String, long[]> perSite = new HashMap<>();
        for (Map.Entry<Channel, String> e : origins.entrySet()) {
            long[] counts = perSite.computeIfAbsent(e.getValue(), s -> new long[2]);
            counts[0]++;
            if (e.getKey().isOpen())
                counts[1]++;
        }
        long totalOpened = opens.values().stream().mapToLong(AtomicLong::get).sum();
        long totalLive = perSite.values().stream().mapToLong(c -> c[1]).sum();
        StringBuilder b = new StringBuilder("socket tracer: " + totalOpened + " channels opened, "
                + totalLive + " still open, across " + perSite.size() + " sites\n");
        perSite.entrySet().stream()
                .sorted((x, y) -> Long.compare(y.getValue()[1], x.getValue()[1]))
                .limit(topSites)
                .forEach(e -> b.append("  still-open=").append(e.getValue()[1])
                        .append(" of ").append(e.getValue()[0])
                        .append(" created here").append(e.getKey()).append('\n'));
        return b.toString();
    }

    /** One line per site, no stacks - cheap enough to print on every progress tick. */
    public static String summary() {
        if (! installed)
            return "tracer off";
        long live = 0, total = 0;
        for (Map.Entry<Channel, String> e : origins.entrySet()) {
            total++;
            if (e.getKey().isOpen())
                live++;
        }
        return "tracer {opened=" + total + " still-open=" + live + "}";
    }

    /** Instantiated by the JDK itself, via -Djava.nio.channels.spi.SelectorProvider, so it must be
     *  public with a public no-arg constructor and must not call SelectorProvider.provider() (that
     *  is the call we are in the middle of servicing). */
    public static class Tracing extends SelectorProvider {
        private final SelectorProvider real;

        public Tracing() {
            try {
                // the platform default, obtained without recursing back into SelectorProvider.provider()
                this.real = (SelectorProvider) Class.forName("sun.nio.ch.DefaultSelectorProvider")
                        .getMethod("get").invoke(null);
            } catch (Throwable t) {
                throw new IllegalStateException("could not get the default selector provider - is "
                        + "--add-exports java.base/sun.nio.ch=ALL-UNNAMED set?", t);
            }
            installed = true;
        }

        @Override
        public DatagramChannel openDatagramChannel() throws IOException {
            DatagramChannel ch = real.openDatagramChannel();
            record(ch, "openDatagramChannel()");
            return ch;
        }

        @Override
        public DatagramChannel openDatagramChannel(ProtocolFamily family) throws IOException {
            DatagramChannel ch = real.openDatagramChannel(family);
            record(ch, "openDatagramChannel(" + family + ")");
            return ch;
        }

        @Override
        public Pipe openPipe() throws IOException {
            return real.openPipe();
        }

        @Override
        public AbstractSelector openSelector() throws IOException {
            return real.openSelector();
        }

        @Override
        public ServerSocketChannel openServerSocketChannel() throws IOException {
            ServerSocketChannel ch = real.openServerSocketChannel();
            record(ch, "openServerSocketChannel()");
            return ch;
        }

        @Override
        public ServerSocketChannel openServerSocketChannel(ProtocolFamily family) throws IOException {
            ServerSocketChannel ch = real.openServerSocketChannel(family);
            record(ch, "openServerSocketChannel(" + family + ")");
            return ch;
        }

        @Override
        public SocketChannel openSocketChannel() throws IOException {
            SocketChannel ch = real.openSocketChannel();
            record(ch, "openSocketChannel()");
            return ch;
        }

        @Override
        public SocketChannel openSocketChannel(ProtocolFamily family) throws IOException {
            SocketChannel ch = real.openSocketChannel(family);
            record(ch, "openSocketChannel(" + family + ")");
            return ch;
        }

        @Override
        public Channel inheritedChannel() throws IOException {
            return real.inheritedChannel();
        }
    }
}
