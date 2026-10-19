package peergos.server.routing;

import peergos.server.util.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.*;
import peergos.shared.routing.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.logging.*;

/** A light server's view of the log (remove-pki §4.7). It holds its own records and its users' in full, and a cache
 *  of what it has resolved: anything else it asks a full node for, and verifies itself, as any replica does. A full
 *  node can't forge an answer, only withhold one, so on "not found" it asks up to {@link #SAMPLE} others, and it
 *  re-checks a random fraction of lookups against another, so one that withholds is caught.
 */
public class LightLookup implements RoutingLookup {
    private static final Logger LOG = Logging.LOG();
    public static final int SAMPLE = 10;
    public static final double SAMPLE_FRACTION = 0.05;
    public static final long FRESH_MILLIS = 10 * 60_000, MISSING_MILLIS = 60_000;
    private static final long PULL_TIMEOUT_SECONDS = 15;

    private final RoutingReplica replica;
    private final RoutingNode node;
    private final Multihash self;
    private final List<RoutingPeer> fullNodes;
    private volatile Supplier<List<RoutingPeer>> drawn = Collections::emptyList;
    private final LongSupplier clock;
    private final Random random;
    private final ExecutorService background;
    private final LRUCache<Subject, Long> fetched = new LRUCache<>(10_000), missing = new LRUCache<>(10_000);

    public LightLookup(RoutingNode node, Multihash self, List<RoutingPeer> fullNodes, LongSupplier clock, Random random, ExecutorService background) {
        if (fullNodes.isEmpty())
            throw new IllegalStateException("A light server needs a full node to ask");
        this.replica = node.replica();
        this.node = node;
        this.self = self.bareMultihash();
        this.fullNodes = List.copyOf(fullNodes);
        this.clock = clock;
        this.random = random;
        this.background = background;
    }

    /** Full nodes drawn by the sync-peer rules, sampled alongside the configured ones. */
    public void sampleFrom(Supplier<List<RoutingPeer>> drawn) {
        this.drawn = drawn;
    }

    @Override
    public RoutingReplica replica() {
        return replica;
    }

    @Override
    public Optional<IdentityLog> identity(PublicKeyHash identity) {
        return resolve(Subject.identity(identity)).map(l -> (IdentityLog) l);
    }

    @Override
    public Optional<ServerLog> server(Multihash peerId) {
        return resolve(Subject.server(peerId.bareMultihash())).map(l -> (ServerLog) l);
    }

    /** This server and the identities it hosts: their records arrive here first, so the replica is their source. */
    public boolean isOurs(Subject s, SubjectLog log) {
        if (! log.exists())
            return false;
        if (log instanceof ServerLog)
            return ((ServerLog) log).peerId.bareMultihash().equals(self);
        return ((IdentityLog) log).providers().stream().anyMatch(p -> p.bareMultihash().equals(self));
    }

    private Optional<SubjectLog> resolve(Subject requested) {
        Subject s = replica.canonical(requested);
        SubjectLog held = replica.log(s);
        long now = clock.getAsLong();
        if (held.exists() && (isOurs(s, held) || isFresh(s, now))) {
            if (! isOurs(s, held) && random.nextDouble() < SAMPLE_FRACTION)
                background.submit(() -> pull(other(), s));
            return Optional.of(held);
        }
        if (! held.exists() && isMissing(s, now))
            return Optional.empty();
        pull(fullNodes.get(0), s);
        if (! replica.log(s).exists())
            for (RoutingPeer peer : sample())
                pull(peer, s);
        SubjectLog after = replica.log(s);
        synchronized (this) {
            if (after.exists()) {
                fetched.put(s, now);
                missing.remove(s);
            } else
                missing.put(s, now);
        }
        return after.exists() ? Optional.of(after) : Optional.empty();
    }

    private synchronized boolean isFresh(Subject s, long now) {
        Long at = fetched.get(s);
        return at != null && now - at < FRESH_MILLIS;
    }

    private synchronized boolean isMissing(Subject s, long now) {
        Long at = missing.get(s);
        return at != null && now - at < MISSING_MILLIS;
    }

    private void pull(RoutingPeer from, Subject s) {
        try {
            CompletableFuture.runAsync(() -> node.fetch(from, s, true), background)
                    .orTimeout(PULL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .join();
        } catch (Exception e) {
            LOG.log(Level.FINE, "Couldn't pull " + s + " from " + from.id() + ": " + e.getMessage());
        }
    }

    private List<RoutingPeer> others() {
        List<RoutingPeer> res = new ArrayList<>(fullNodes.subList(1, fullNodes.size()));
        for (RoutingPeer p : drawn.get())
            if (res.stream().noneMatch(q -> q.id().bareMultihash().equals(p.id().bareMultihash()))
                    && ! fullNodes.get(0).id().bareMultihash().equals(p.id().bareMultihash()))
                res.add(p);
        return res;
    }

    private RoutingPeer other() {
        List<RoutingPeer> others = others();
        return others.isEmpty() ? fullNodes.get(0) : others.get(random.nextInt(others.size()));
    }

    private List<RoutingPeer> sample() {
        List<RoutingPeer> others = others();
        Collections.shuffle(others, random);
        return others.subList(0, Math.min(SAMPLE, others.size()));
    }

    /** Hands this server's records, and its users', to the full nodes, which pass them on. */
    public void publish(Subject s, SubjectLog log) {
        if (! isOurs(s, log))
            return;
        List<SignedRecord> records = new ArrayList<>();
        log.held().forEach(v -> records.add(v.record));
        for (RoutingPeer peer : fullNodes)
            peer.offer(s, records).exceptionally(t -> {
                LOG.log(Level.FINE, "Couldn't hand " + s + " to " + peer.id() + ": " + t.getMessage());
                return Outcome.Ignored;
            });
    }
}
