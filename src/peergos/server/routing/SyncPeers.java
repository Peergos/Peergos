package peergos.server.routing;

import peergos.server.util.*;
import peergos.shared.io.ipfs.*;
import peergos.shared.routing.*;

import java.util.*;
import java.util.function.*;
import java.util.logging.*;

/** Chooses the full nodes this server reconciles with, or a light server samples (remove-pki §4.3, §4.7), so that
 *  sybils don't help: those its operator configured, and full nodes drawn one per address bucket, each placed in its
 *  bucket by dialling it. A million sybils on a few address ranges get a few slots, not a million. One drawn peer is
 *  replaced each round, so they rotate.
 *
 *  A light server holds few server records, so before choosing it pulls a page of them from a configured node,
 *  starting at a random point.
 */
public class SyncPeers {
    private static final Logger LOG = Logging.LOG();
    public static final int DRAWN = 8, VERIFY_PER_ROUND = 16, DISCOVER = 32;
    private static final int MAX_SCANNED = 10_000;
    /** Server ids share their multihash and key type header, so a random point among them keeps it. */
    private static final int KEY_PREFIX = 6;

    private final RoutingNode node;
    private final BucketVerifier buckets;
    private final List<RoutingPeer> configured;
    private final Multihash self;
    private final boolean light;
    private final Random random;
    private final Map<Multihash, RoutingPeer> drawn = new LinkedHashMap<>();

    public SyncPeers(RoutingNode node, BucketVerifier buckets, List<RoutingPeer> configured, Multihash self, boolean light, Random random) {
        this.node = node;
        this.buckets = buckets;
        this.configured = List.copyOf(configured);
        this.self = self.bareMultihash();
        this.light = light;
        this.random = random;
    }

    public synchronized List<RoutingPeer> drawn() {
        return new ArrayList<>(drawn.values());
    }

    /** Draws peers for any bucket not yet covered, after dropping one so they rotate, and makes them the node's sync
     *  peers alongside the configured ones. Run periodically, off the network threads: it dials.
     */
    public void refresh() {
        if (light)
            discover();
        Map<AddressBucket, List<Multihash>> byBucket = new HashMap<>();
        List<Multihash> unverified = new ArrayList<>();
        for (Multihash id : fullNodes()) {
            Optional<AddressBucket> b = buckets.verified(id);
            if (b.isPresent())
                byBucket.computeIfAbsent(b.get(), x -> new ArrayList<>()).add(id);
            else
                unverified.add(id);
        }
        Collections.shuffle(unverified, random);
        for (Multihash id : unverified.subList(0, Math.min(VERIFY_PER_ROUND, unverified.size())))
            buckets.verify(id).ifPresent(b -> byBucket.computeIfAbsent(b, x -> new ArrayList<>()).add(id));

        synchronized (this) {
            drawn.keySet().removeIf(id -> buckets.verified(id).map(b -> ! byBucket.getOrDefault(b, List.of()).contains(id)).orElse(true));
            if (drawn.size() >= Math.min(DRAWN, byBucket.size()) && ! drawn.isEmpty()) {
                List<Multihash> ids = new ArrayList<>(drawn.keySet());
                drawn.remove(ids.get(random.nextInt(ids.size())));
            }
            Set<AddressBucket> covered = new HashSet<>();
            for (Multihash id : drawn.keySet())
                buckets.verified(id).ifPresent(covered::add);
            List<AddressBucket> open = new ArrayList<>(byBucket.keySet());
            open.removeAll(covered);
            Collections.shuffle(open, random);
            for (AddressBucket b : open) {
                if (drawn.size() >= DRAWN)
                    break;
                List<Multihash> in = byBucket.get(b);
                Multihash pick = in.get(random.nextInt(in.size()));
                drawn.put(pick, node.dial(endpoint(pick)));
            }
            if (! light)
                apply();
        }
    }

    /** The full nodes in the replica with a direct address, other than this one and those configured. */
    private List<Multihash> fullNodes() {
        RoutingReplica replica = node.replica();
        Set<Multihash> skip = new HashSet<>();
        skip.add(self);
        configured.forEach(p -> skip.add(p.id().bareMultihash()));
        List<Multihash> res = new ArrayList<>();
        byte[] after = new byte[0];
        int scanned = 0;
        while (scanned < MAX_SCANNED) {
            List<JdbcRoutingStore.State> page = replica.store().states(Subject.SERVER, after, 1_000);
            if (page.isEmpty())
                break;
            for (JdbcRoutingStore.State st : page) {
                Multihash id = st.subject.id.bareMultihash();
                if (skip.contains(id))
                    continue;
                replica.server(st.subject)
                        .filter(log -> replica.isProven(st.subject, false))
                        .flatMap(ServerLog::addresses)
                        .filter(a -> a.mode != ServerAddresses.Mode.Light && ! a.addrs.isEmpty())
                        .filter(a -> skip.stream().noneMatch(p -> p.equals(ServerKeys.peerId(a.endpoint).bareMultihash())))
                        .ifPresent(a -> res.add(id));
            }
            scanned += page.size();
            after = page.get(page.size() - 1).subject.idBytes();
        }
        return res;
    }

    private Multihash endpoint(Multihash server) {
        return node.replica().server(Subject.server(server))
                .flatMap(ServerLog::addresses)
                .map(a -> ServerKeys.peerId(a.endpoint).bareMultihash())
                .orElse(server);
    }

    private void apply() {
        Set<Multihash> wanted = new HashSet<>();
        for (RoutingPeer p : configured) {
            wanted.add(p.id().bareMultihash());
            node.addSyncPeer(p);
        }
        for (RoutingPeer p : drawn.values()) {
            wanted.add(p.id().bareMultihash());
            node.addSyncPeer(p);
        }
        for (RoutingPeer p : new ArrayList<>(node.syncPeers()))
            if (! wanted.contains(p.id().bareMultihash()))
                node.removeSyncPeer(p.id());
    }

    /** Pulls a page of server records from a configured node, from a random point in the key space. */
    private void discover() {
        if (configured.isEmpty())
            return;
        RoutingPeer from = configured.get(0);
        byte[] start = self.toBytes();
        byte[] tail = new byte[start.length - KEY_PREFIX];
        random.nextBytes(tail);
        System.arraycopy(tail, 0, start, KEY_PREFIX, tail.length);
        try {
            List<JdbcRoutingStore.State> page = from.states(Subject.SERVER, new Reconciliation.Range(start, Optional.empty()), DISCOVER)
                    .get(15, java.util.concurrent.TimeUnit.SECONDS);
            if (page.size() < DISCOVER) {
                List<JdbcRoutingStore.State> wrapped = new ArrayList<>(page);
                wrapped.addAll(from.states(Subject.SERVER, new Reconciliation.Range(new byte[0], Optional.of(start)), DISCOVER - page.size())
                        .get(15, java.util.concurrent.TimeUnit.SECONDS));
                page = wrapped;
            }
            for (JdbcRoutingStore.State st : page.subList(0, Math.min(DISCOVER, page.size())))
                if (! node.replica().log(st.subject).exists())
                    node.fetch(from, st.subject, true);
        } catch (Exception e) {
            LOG.log(Level.FINE, "Couldn't discover full nodes from " + from.id() + ": " + e.getMessage());
        }
    }
}
