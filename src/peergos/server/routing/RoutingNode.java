package peergos.server.routing;

import peergos.server.util.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.*;
import peergos.shared.routing.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import java.util.logging.*;
import java.util.stream.*;

/** A full node's replication (remove-pki §4.3): it answers other nodes, announces subjects whose state changed,
 *  fetches what it is announced, reconciles with the sync peers it chose, and pulls single subjects on contact.
 *  Everything new passes the limits of §4.5 first, unless most of its chosen peers already hold it.
 */
public class RoutingNode {
    private static final Logger LOG = Logging.LOG();
    public static final int ITEMS = 32, SPLIT = 8, PAGE = 1000, BATCH = 16;
    public static final int MAX_FETCHING_PER_PEER = 64, UNPROVEN_CACHE = 1000, MAX_OFFERS_PER_SERVER = 1000;
    public static final long MAX_BYTES_PER_PEER = 64 * 1024 * 1024;

    private final RoutingReplica replica;
    private final Hasher hasher;
    private final LongSupplier clock;
    private final RateLimits.Updates updates;
    private final RateLimits.Inceptions<Offer> inceptions;
    private final Function<Multihash, AddressBucket> buckets;
    private final Function<Multihash, RoutingPeer> dial;
    private final Map<Multihash, RoutingPeer> syncPeers = new ConcurrentHashMap<>();
    private final Map<Subject, Map<ByteArrayWrapper, Set<Multihash>>> seen = Collections.synchronizedMap(new LRUCache<>(100_000));
    private final Map<Subject, Held> held = new ConcurrentHashMap<>();
    private final Map<Subject, SubjectLog> unproven = Collections.synchronizedMap(new LRUCache<>(UNPROVEN_CACHE));
    private final Map<Multihash, AtomicInteger> fetching = new ConcurrentHashMap<>();
    private final Map<Multihash, AtomicLong> charged = new ConcurrentHashMap<>();
    private final ExecutorService work;

    private static final class Offer {
        final RoutingPeer from;
        final Subject subject;

        Offer(RoutingPeer from, Subject subject) {
            this.from = from;
            this.subject = subject;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Offer && subject.equals(((Offer) o).subject);
        }

        @Override
        public int hashCode() {
            return subject.hashCode();
        }
    }

    private static final class Held {
        final RoutingPeer from;
        final SignedRecord record;

        Held(RoutingPeer from, SignedRecord record) {
            this.from = from;
            this.record = record;
        }
    }

    public RoutingNode(RoutingReplica replica,
                       Hasher hasher,
                       LongSupplier clock,
                       int inceptionsPerDay,
                       Function<Multihash, AddressBucket> buckets,
                       Function<Multihash, RoutingPeer> dial,
                       ExecutorService work) {
        this.replica = replica;
        this.hasher = hasher;
        this.clock = clock;
        this.updates = new RateLimits.Updates(clock);
        this.inceptions = new RateLimits.Inceptions<>(inceptionsPerDay, MAX_OFFERS_PER_SERVER, clock);
        this.buckets = buckets;
        this.dial = dial;
        this.work = work;
        replica.onChange((s, log) -> {
            if (replica.isProven(s, true))
                announce(new JdbcRoutingStore.State(s, replica.stateHash(s)));
        });
    }

    public RoutingReplica replica() {
        return replica;
    }

    public void addSyncPeer(RoutingPeer peer) {
        syncPeers.put(peer.id().bareMultihash(), peer);
    }

    public void removeSyncPeer(Multihash id) {
        syncPeers.remove(id.bareMultihash());
    }

    public Collection<RoutingPeer> syncPeers() {
        return syncPeers.values();
    }

    // ---- answering other nodes

    public List<SignedRecord> records(Subject s) {
        return replica.log(s).held().stream()
                .map(v -> (SignedRecord) v.record)
                .collect(Collectors.toList());
    }

    public List<Reconciliation.Summary> summaries(int kind, List<Reconciliation.Range> ranges) {
        return ranges.stream()
                .map(r -> summary(kind, r))
                .collect(Collectors.toList());
    }

    public List<JdbcRoutingStore.State> states(int kind, Reconciliation.Range range, int limit) {
        return replica.store().states(kind, range.after, range.upTo, Math.min(limit, PAGE));
    }

    /** Fetches each announced subject whose state differs from ours, from the announcer, within a bound on what
     *  is outstanding per announcer; beyond it the announcement is ignored, and the peer announces again later.
     */
    public void announced(Multihash caller, List<JdbcRoutingStore.State> changed) {
        RoutingPeer from = dial.apply(caller);
        for (JdbcRoutingStore.State st : changed) {
            Subject s = replica.canonical(st.subject);
            saw(s, st.hash, caller);
            if (Arrays.equals(replica.stateHash(s), st.hash))
                continue;
            AtomicInteger outstanding = fetching.computeIfAbsent(caller.bareMultihash(), c -> new AtomicInteger());
            if (outstanding.incrementAndGet() > MAX_FETCHING_PER_PEER) {
                outstanding.decrementAndGet();
                continue;
            }
            work.submit(() -> {
                try {
                    fetch(from, s, false);
                } catch (Throwable t) {
                    LOG.log(Level.FINE, "Fetching " + s + " from " + caller + " failed: " + t.getMessage());
                } finally {
                    outstanding.decrementAndGet();
                }
            });
        }
    }

    public Outcome offered(Multihash caller, Subject s, List<SignedRecord> records) {
        return accept(dial.apply(caller), s, records, false);
    }

    // ---- acting for this node

    /** Runs off the caller's thread: change listeners are called while the replica holds the subject's lock. */
    private void announce(JdbcRoutingStore.State st) {
        for (RoutingPeer peer : syncPeers.values())
            work.submit(() -> peer.announce(List.of(st)).exceptionally(t -> false));
    }

    public Outcome fetch(RoutingPeer from, Subject s, boolean fromQueue) {
        return accept(from, s, from.records(s).join(), fromQueue);
    }

    /** Pulls one subject from the server in front of this one, for a lookup this node is making. It is used at
     *  once; if its proof of work is not done it is kept only in a bounded cache, never stored or passed on.
     */
    public Optional<SubjectLog> pull(RoutingPeer from, Subject s) {
        fetch(from, s, false);
        return lookup(s);
    }

    public Optional<SubjectLog> lookup(Subject s) {
        SubjectLog log = replica.log(s);
        if (log.exists())
            return Optional.of(log);
        return Optional.ofNullable(unproven.get(replica.canonical(s)));
    }

    private void saw(Subject s, byte[] hash, Multihash peer) {
        synchronized (seen) {
            seen.computeIfAbsent(s, x -> new HashMap<>())
                    .computeIfAbsent(new ByteArrayWrapper(hash), h -> new HashSet<>())
                    .add(peer.bareMultihash());
        }
    }

    /** Most of the sync peers this node chose already hold this state, so it passed every honest node's limits. */
    private boolean isCatchUp(Subject s, byte[] hash) {
        Set<Multihash> holders;
        synchronized (seen) {
            holders = new HashSet<>(seen.getOrDefault(s, Map.of()).getOrDefault(new ByteArrayWrapper(hash), Set.of()));
        }
        holders.retainAll(syncPeers.keySet());
        return ! holders.isEmpty() && holders.size() * 2 > syncPeers.size();
    }

    /** Applies a peer's records for a subject. A subject new to this node needs a proven inception, and then waits
     *  its turn in its admitting server's address bucket unless it comes from that queue or is catch-up.
     */
    Outcome accept(RoutingPeer from, Subject requested, List<SignedRecord> records, boolean fromQueue) {
        Subject s = replica.canonical(requested);
        boolean catchUp = isCatchUp(s, replica.stateHash(records));
        SubjectLog log = replica.log(s);
        if (log.inception().isEmpty()) {
            Optional<SignedRecord> inception = records.stream().filter(RoutingReplica::isInception).findFirst();
            if (inception.isEmpty())
                return Outcome.Unconnected;
            Optional<Multihash> admitter = admitter(inception.get());
            Multihash chargedTo = admitter.orElse(s.id);
            if (admitter.isPresent() && replica.log(Subject.server(admitter.get())).inception().isEmpty()) {
                fetch(from, Subject.server(admitter.get()), fromQueue);
                if (replica.log(Subject.server(admitter.get())).inception().isEmpty()) {
                    unproven.put(s, replica.preview(s, records));
                    if (! catchUp)
                        inceptions.offer(buckets.apply(chargedTo), chargedTo, new Offer(from, s));
                    return Outcome.Limited;
                }
            }
            if (! replica.isProven(s, inception.get(), catchUp)) {
                unproven.put(s, replica.preview(s, records));
                return Outcome.Ignored;
            }
            if (! catchUp && ! fromQueue) {
                inceptions.offer(buckets.apply(chargedTo), chargedTo, new Offer(from, s));
                return Outcome.Limited;
            }
        }
        RoutingReplica.Gate gate = (subject, record, isNewInception) -> {
            if (catchUp)
                return true;
            RecordType type = RecordType.of(record);
            if (updates.isLimited(type) && ! updates.tryTake(subject, type)) {
                if (record instanceof Update)
                    hold(subject, from, record);
                return false;
            }
            return charge(from, record);
        };
        Outcome res = Outcome.Duplicate;
        List<SignedRecord> pending = new ArrayList<>(records);
        boolean progress = true;
        while (progress && ! pending.isEmpty()) {
            progress = false;
            List<SignedRecord> retry = new ArrayList<>();
            for (SignedRecord r : pending) {
                Outcome o = replica.add(s, r, gate);
                if (o == Outcome.Unconnected)
                    retry.add(r);
                else
                    progress = true;
                if (o == Outcome.Added || (o == Outcome.Limited && res != Outcome.Added))
                    res = o;
            }
            pending = retry;
        }
        if (replica.log(s).inception().isPresent())
            unproven.remove(s);
        return res;
    }

    private static Optional<Multihash> admitter(SignedRecord inception) {
        return inception instanceof IdentityInception ?
                Optional.of(((IdentityInception) inception).admittingServer().bareMultihash()) :
                Optional.empty();
    }

    /** Records new to this replica are charged to the peer that sent them, up to a cap on what each peer may add. */
    private boolean charge(RoutingPeer from, SignedRecord record) {
        AtomicLong used = charged.computeIfAbsent(from.id().bareMultihash(), p -> new AtomicLong());
        long size = record.serialize().length;
        if (used.addAndGet(size) > MAX_BYTES_PER_PEER) {
            used.addAndGet(-size);
            return false;
        }
        return true;
    }

    /** An update that arrives before its token is kept, the newest only, and applied when the token is due. */
    private void hold(Subject s, RoutingPeer from, SignedRecord record) {
        held.merge(s, new Held(from, record), (old, update) ->
                ((Update) update.record).counter() >= ((Update) old.record).counter() ? update : old);
    }

    /** Takes the inceptions whose turn has come and the held updates whose token is due. Run periodically. */
    public void tick() {
        for (Offer o : inceptions.take()) {
            try {
                fetch(o.from, o.subject, true);
            } catch (Throwable t) {
                LOG.log(Level.FINE, "Fetching " + o.subject + " failed: " + t.getMessage());
            }
        }
        long now = clock.getAsLong();
        for (Map.Entry<Subject, Held> e : new ArrayList<>(held.entrySet())) {
            RecordType type = RecordType.of(e.getValue().record);
            if (updates.nextToken(e.getKey(), type) > now)
                continue;
            held.remove(e.getKey(), e.getValue());
            accept(e.getValue().from, e.getKey(), List.of(e.getValue().record), false);
        }
        updates.prune();
    }

    public int waitingInceptions() {
        return inceptions.waiting();
    }

    public int heldUpdates() {
        return held.size();
    }

    // ---- reconciliation

    private Reconciliation.Summary summary(int kind, Reconciliation.Range range) {
        Reconciliation.Accumulator acc = new Reconciliation.Accumulator(hasher);
        scan(kind, range, acc::add);
        return acc.summary();
    }

    private void scan(int kind, Reconciliation.Range range, Consumer<JdbcRoutingStore.State> each) {
        byte[] after = range.after;
        while (true) {
            List<JdbcRoutingStore.State> page = replica.store().states(kind, after, range.upTo, PAGE);
            page.forEach(each);
            if (page.size() < PAGE)
                return;
            after = page.get(page.size() - 1).subject.idBytes();
        }
    }

    /** The peer's states that differ from ours, found by comparing range fingerprints and splitting those that
     *  differ by our own subject ids. Each is also recorded as held by that peer.
     */
    public List<JdbcRoutingStore.State> differences(RoutingPeer peer, int kind) {
        List<JdbcRoutingStore.State> res = new ArrayList<>();
        Deque<Reconciliation.Range> todo = new ArrayDeque<>(List.of(Reconciliation.Range.ALL));
        while (! todo.isEmpty()) {
            List<Reconciliation.Range> batch = new ArrayList<>();
            while (! todo.isEmpty() && batch.size() < BATCH)
                batch.add(todo.poll());
            List<Reconciliation.Summary> remote = peer.summaries(kind, batch).join();
            for (int i = 0; i < batch.size(); i++) {
                Reconciliation.Range range = batch.get(i);
                Reconciliation.Summary local = summary(kind, range);
                if (local.equals(remote.get(i)))
                    continue;
                if (remote.get(i).count <= ITEMS || local.count < 2L * SPLIT)
                    res.addAll(differing(peer, kind, range));
                else
                    todo.addAll(split(kind, range, local.count));
            }
        }
        return res;
    }

    private List<JdbcRoutingStore.State> differing(RoutingPeer peer, int kind, Reconciliation.Range range) {
        List<JdbcRoutingStore.State> res = new ArrayList<>();
        byte[] after = range.after;
        while (true) {
            List<JdbcRoutingStore.State> page = peer.states(kind, new Reconciliation.Range(after, range.upTo), PAGE).join();
            for (JdbcRoutingStore.State st : page) {
                saw(st.subject, st.hash, peer.id());
                if (! Arrays.equals(replica.stateHash(st.subject), st.hash))
                    res.add(st);
            }
            if (page.size() < PAGE)
                return res;
            after = page.get(page.size() - 1).subject.idBytes();
        }
    }

    private List<Reconciliation.Range> split(int kind, Reconciliation.Range range, long count) {
        long step = Math.max(1, count / SPLIT);
        List<byte[]> bounds = new ArrayList<>();
        long[] i = {0};
        scan(kind, range, st -> {
            if (++i[0] % step == 0 && bounds.size() < SPLIT - 1)
                bounds.add(st.subject.idBytes());
        });
        List<Reconciliation.Range> res = new ArrayList<>();
        byte[] after = range.after;
        for (byte[] b : bounds) {
            res.add(new Reconciliation.Range(after, Optional.of(b)));
            after = b;
        }
        res.add(new Reconciliation.Range(after, range.upTo));
        return res;
    }

    /** One round with every sync peer: gather what differs from all of them first, so a state most of them hold is
     *  known to be catch-up, then fetch each subject's most widely held state from one of its holders, servers
     *  first since identities' admissions are checked against them. Bootstrap is the same round from an empty
     *  replica.
     */
    public int syncRound() {
        Map<Subject, Map<ByteArrayWrapper, List<RoutingPeer>>> wanted = new TreeMap<>(
                Comparator.comparingInt((Subject s) -> s.isIdentity() ? 1 : 0).thenComparing(s -> s));
        for (RoutingPeer peer : new ArrayList<>(syncPeers.values())) {
            for (int kind : new int[]{Subject.SERVER, Subject.IDENTITY}) {
                try {
                    for (JdbcRoutingStore.State st : differences(peer, kind))
                        wanted.computeIfAbsent(st.subject, x -> new HashMap<>())
                                .computeIfAbsent(new ByteArrayWrapper(st.hash), h -> new ArrayList<>())
                                .add(peer);
                } catch (Throwable t) {
                    LOG.log(Level.INFO, "Reconciling with " + peer.id() + " failed: " + t.getMessage());
                }
            }
        }
        int changed = 0;
        for (Map.Entry<Subject, Map<ByteArrayWrapper, List<RoutingPeer>>> e : wanted.entrySet()) {
            List<RoutingPeer> holders = e.getValue().values().stream()
                    .max(Comparator.comparingInt(List::size))
                    .get();
            for (RoutingPeer holder : holders) {
                try {
                    if (fetch(holder, e.getKey(), false) == Outcome.Added)
                        changed++;
                    break;
                } catch (Throwable t) {
                    LOG.log(Level.FINE, "Fetching " + e.getKey() + " failed: " + t.getMessage());
                }
            }
        }
        return changed;
    }
}
