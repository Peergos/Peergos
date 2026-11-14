package peergos.server.routing;

import peergos.server.util.*;
import peergos.shared.cbor.*;
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
    public static final int PAGE = 1000, BATCH = 16;
    /** A range in which a peer holds no more than this is listed rather than descended into. */
    public static final int LIST_BELOW = 512;
    /** Ranges listed in one call, as many as a range has children, and the states that call may return. */
    public static final int LIST_RANGES = 256, LISTED = 4 * PAGE;
    /** Subjects being fetched from one announcer at once, as many as it may announce in a call, and how many are
     *  asked for in one call.
     */
    public static final int MAX_FETCHING_PER_PEER = 1024, FETCH_BATCH = 256;
    public static final int UNPROVEN_CACHE = 1000, MAX_OFFERS_PER_SERVER = 1000;
    public static final long MAX_BYTES_PER_PEER = 64 * 1024 * 1024;
    /** Records new to this node taken for one subject at once, unless most of its sync peers hold them: what a
     *  subject can add within its rate limits is far fewer, and each costs a signature check. A pull, for a lookup
     *  this node is making, may bring an old identity's whole chain.
     */
    public static final int MAX_UNSOLICITED_RECORDS = 64, MAX_PULLED_RECORDS = 256;
    /** Honest peers never send a record that fails its checks, so each that does costs its sender one of a few. */
    public static final int REJECTIONS = 16;
    /** Peers a round must reach, or all of them if fewer, before it can show this node is synced. */
    public static final int SYNCED_PEERS = 3;

    private final RoutingReplica replica;
    private final Hasher hasher;
    private final LongSupplier clock;
    private final RateLimits.Updates updates;
    private final RateLimits.Inceptions<Offer> inceptions;
    private final Function<Multihash, AddressBucket> buckets;
    private volatile BiFunction<Multihash, SubjectLog, AddressBucket> placer;
    /** New servers being placed in buckets, by who handed them over, so one sender's can't fill the work queue. */
    private final Map<Subject, Multihash> placing = new ConcurrentHashMap<>();
    /** Identities admitted by a server being placed or waiting its turn, queued in its bucket once it is taken. */
    private final Map<Multihash, Map<Subject, Offer>> awaitingAdmitter = Collections.synchronizedMap(new LRUCache<>(1_000));
    public static final int MAX_PLACING_PER_SENDER = 4;
    private final Function<Multihash, RoutingPeer> dial;
    private final Map<Multihash, RoutingPeer> syncPeers = new ConcurrentHashMap<>();
    /** The state each sync peer last claimed of a subject: one per peer, so what is kept can't outgrow the peers. */
    private final Map<Subject, Map<Multihash, ByteArrayWrapper>> seen = Collections.synchronizedMap(new LRUCache<>(100_000));
    private final Map<Subject, Held> held = new ConcurrentHashMap<>();
    private final Map<Subject, SubjectLog> unproven = Collections.synchronizedMap(new LRUCache<>(UNPROVEN_CACHE));
    private final Map<Multihash, AtomicInteger> fetching = new ConcurrentHashMap<>();
    private final Set<JdbcRoutingStore.State> inFlight = ConcurrentHashMap.newKeySet();
    /** Servers being fetched from whoever announced them, or named them as an admitting server. */
    private final Map<Subject, Integer> fetchingServers = new ConcurrentHashMap<>();
    public static final long FETCH_TIMEOUT_SECONDS = 15;
    private static final Multihash POOL = new Multihash(Multihash.Type.id, new byte[0]);
    private static final byte[] ANY_STATE = new byte[0];
    /** Changes not yet announced to each peer, the latest state per subject, sent together. */
    private final Map<Multihash, Map<Subject, JdbcRoutingStore.State>> outbox = new ConcurrentHashMap<>();
    private final AtomicBoolean flushing = new AtomicBoolean();
    private volatile Consumer<Runnable> flushLater;
    /** Announcements a peer didn't take, the latest state per subject, sent again next round. */
    private final Map<Multihash, Map<Subject, JdbcRoutingStore.State>> unannounced = new ConcurrentHashMap<>();
    public static final int MAX_UNANNOUNCED_PER_PEER = 10_000;
    private volatile Optional<Subject> self = Optional.empty();
    /** New records' bytes each peer may add, in KiB, refilled over a day. */
    private final RateLimits.TokenBuckets<Multihash> charged;
    private final RateLimits.TokenBuckets<Multihash> rejections;
    private final ExecutorService work;
    private final AtomicBoolean taking = new AtomicBoolean();
    private volatile Set<Multihash> configured = Set.of();
    private volatile boolean synced;
    /** Behind a configured peer by more than this, a node that has never been synced reads that peer's log end to
     *  end, a page at a time, before it reconciles: reconciliation finds what differs, and is no way to fetch
     *  everything.
     */
    private volatile int snapshotBelow = 5_000;
    public static final int SNAPSHOT_PAGES_PER_ROUND = 1_000;
    private static final String SNAPSHOT = "snapshot";
    private static final byte[] SNAPSHOT_DONE = {1};
    private volatile boolean bootstrapping;
    /** Configured peers whose pages left out most of what they passed, as a compact node's do: it holds other
     *  servers' users without their signatures. Their log isn't read end to end again while this node runs.
     */
    private final Set<Multihash> cantPage = ConcurrentHashMap.newKeySet();
    /** Set while a page of a peer's log is taken in: every node holds those records, so they aren't announced. */
    private final ThreadLocal<Boolean> quiet = ThreadLocal.withInitial(() -> false);
    /** When this node's state of each recently changed subject changed, and the states it moved on from. */
    private final Map<Subject, Past> past = new LinkedHashMap<>();
    /** How long a change is remembered, and at most how many: by time, as a fixed number covers less of the grace
     *  below the busier the log is.
     */
    public static final long PAST_MILLIS = 2 * 3600_000L;
    public static final int MAX_PAST = 2_000_000;
    /** Updates kept until a token is due. One not kept stays with its sender, and reconciliation brings it. */
    public static final int MAX_HELD = 100_000;
    private final Map<Multihash, Tally> tallies = new ConcurrentHashMap<>();
    private final Map<Multihash, Integer> strikes = new ConcurrentHashMap<>();
    private final Map<Multihash, Long> suspects = new ConcurrentHashMap<>();
    /** How long a record may take to reach every honest node before a peer without it counts as behind. */
    public static final long BEHIND_GRACE_MILLIS = 20 * 60_000L;
    /** Behind by this many more than the second most current peer, round after round, and a peer is replaced. */
    public static final int MIN_BEHIND = 16, STRIKES = 3;
    /** Of our own states compared with a peer's list in one range, so a peer holding almost nothing costs a bounded scan. */
    public static final int LACK_SCAN = 20_000;
    public static final long SUSPECT_MILLIS = 24 * 3600_000L, UNREACHABLE_MILLIS = 6 * 3600_000L;
    private final Map<Multihash, Integer> missed = new ConcurrentHashMap<>();
    private final Map<Multihash, Long> unreachable = new ConcurrentHashMap<>();
    /** What one sync peer may cost a round: calls that fail, and time spent waiting on it. Past either, the rest of
     *  what it claims waits for the next round, so one peer can't hold up the others' records.
     */
    public static final int MAX_FAILURES_PER_PEER = 8;
    public static final long PEER_ROUND_MILLIS = 2 * 60_000L;
    private volatile long peerRoundMillis = PEER_ROUND_MILLIS;
    /** Subjects a peer may claim in a round and then answer that it can't serve: a compact peer can't when a
     *  provider is offline, which is no failure of its own, but nor may it claim without end.
     */
    public static final int MAX_UNDELIVERED_PER_PEER = 1024;

    private final class Budget {
        int failures, undelivered;
        long waitedNanos;
        /** Admitting servers already asked of this peer this round. */
        final Set<Subject> askedFor = new HashSet<>();

        boolean failing() {
            return failures >= MAX_FAILURES_PER_PEER;
        }

        boolean spent() {
            return failing() || undelivered >= MAX_UNDELIVERED_PER_PEER || waitedNanos > peerRoundMillis * 1_000_000;
        }

        /** Comparing and fetching each get a share of time, so a long comparison leaves time to fetch what it found. */
        void startFetching() {
            waitedNanos = 0;
        }
    }

    /** A peer's share of the round ran out: what it gave so far is kept, and the rest waits for the next round. */
    private static final class Spent extends RuntimeException {
        Spent(Multihash peer) {
            super("Peer " + peer + " has used its share of this round", null, false, false);
        }
    }

    /** A state some sync peers hold of a subject and this node doesn't, to fetch from one of them. */
    private static final class Claim {
        final Subject subject;
        final byte[] state;
        final List<RoutingPeer> holders;
        int next;

        Claim(Subject subject, byte[] state, List<RoutingPeer> holders) {
            this.subject = subject;
            this.state = state;
            this.holders = holders;
        }

        Optional<RoutingPeer> nextHolder(Predicate<RoutingPeer> usable) {
            while (next < holders.size()) {
                RoutingPeer holder = holders.get(next++);
                if (usable.test(holder))
                    return Optional.of(holder);
            }
            return Optional.empty();
        }
    }

    /** Waits for a sync peer's answer, within its budget for the round. */
    private <T> T ask(RoutingPeer peer, Budget budget, Supplier<CompletableFuture<T>> call) {
        if (budget.spent())
            throw new Spent(peer.id());
        long start = System.nanoTime();
        try {
            return call.get().join();
        } catch (RuntimeException e) {
            budget.failures++;
            throw e;
        } finally {
            budget.waitedNanos += System.nanoTime() - start;
        }
    }

    /** The budget of the sync peer whose records the round is taking, for anything more it must be asked for. */
    private final ThreadLocal<Budget> roundBudget = new ThreadLocal<>();

    /** Asks whoever sent an identity for the server that admitted it. In a sync round that is within the peer's
     *  budget, and once per server, so identities naming servers it then won't serve hold the round up no longer
     *  than any other call it fails.
     */
    private void fetchAdmitter(RoutingPeer from, Subject server, boolean fromQueue) {
        Budget budget = roundBudget.get();
        if (budget == null) {
            accept(from, server, from.records(server).orTimeout(FETCH_TIMEOUT_SECONDS, TimeUnit.SECONDS).join(), fromQueue, false);
            return;
        }
        if (budget.spent() || ! budget.askedFor.add(server))
            return;
        try {
            List<SignedRecord> records = ask(from, budget, () -> from.records(server).orTimeout(FETCH_TIMEOUT_SECONDS, TimeUnit.SECONDS));
            accept(from, server, records, fromQueue, false);
        } catch (RuntimeException e) {
            LOG.log(Level.FINE, "Fetching " + server + " from " + from.id() + " failed: " + e.getMessage());
        }
        if (replica.log(server).inception().isEmpty())
            budget.undelivered++;
    }

    private static final class Past {
        final long changedAt;
        final Map<ByteArrayWrapper, Long> superseded;
        final ByteArrayWrapper current;

        Past(long changedAt, Map<ByteArrayWrapper, Long> superseded, ByteArrayWrapper current) {
            this.changedAt = changedAt;
            this.superseded = superseded;
            this.current = current;
        }
    }

    /** One round's view of how far behind a peer is: subjects we have held a while that it lacks, or holds an
     *  older state of, against those it has that we lack. A withholder is behind on everything and ahead on nothing;
     *  honest peers taking a flood at their own pace lack some of ours but have others.
     */
    private static final class Tally {
        int lacks, stale, extra;

        int behind() {
            return Math.max(0, lacks + stale - extra);
        }
    }

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
        final long due;

        Held(RoutingPeer from, SignedRecord record, long due) {
            this.from = from;
            this.record = record;
            this.due = due;
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
        this.updates = new RateLimits.Updates(replica.store(), clock);
        this.inceptions = new RateLimits.Inceptions<>(inceptionsPerDay, MAX_OFFERS_PER_SERVER, clock);
        this.buckets = buckets;
        this.placer = (server, claimed) -> buckets.apply(server);
        this.dial = dial;
        this.work = work;
        this.flushLater = work::submit;
        this.charged = new RateLimits.TokenBuckets<>(new RateLimits.Rate((int) (MAX_BYTES_PER_PEER / 1024),
                RateLimits.DAY / (MAX_BYTES_PER_PEER / 1024)), clock);
        this.rejections = new RateLimits.TokenBuckets<>(new RateLimits.Rate(REJECTIONS, RateLimits.DAY / REJECTIONS), clock);
        replica.onChange((s, log) -> {
            byte[] hash = replica.stateHash(s);
            long now = clock.getAsLong();
            synchronized (past) {
                Past before = past.get(s);
                Map<ByteArrayWrapper, Long> superseded = new LinkedHashMap<>(before == null ? Map.of() : before.superseded);
                if (before != null && before.current != null)
                    superseded.put(before.current, now);
                while (superseded.size() > 4)
                    superseded.remove(superseded.keySet().iterator().next());
                // kept in the order they changed, so the oldest are first
                past.remove(s);
                past.put(s, new Past(now, superseded, new ByteArrayWrapper(hash)));
                Iterator<Past> oldest = past.values().iterator();
                while (oldest.hasNext()) {
                    if (oldest.next().changedAt >= now - PAST_MILLIS && past.size() <= MAX_PAST)
                        break;
                    oldest.remove();
                }
            }
            if (! quiet.get() && replica.isProven(s, true))
                announce(new JdbcRoutingStore.State(s, hash));
        });
    }

    /** How a new server is put in a bucket, from the records it came with; this may dial it or ask its relay. */
    public void setPlacer(BiFunction<Multihash, SubjectLog, AddressBucket> placer) {
        this.placer = placer;
    }

    public RoutingReplica replica() {
        return replica;
    }

    /** The operator's own choice of peers, one of which must take part in the round that shows this node synced. */
    public void setConfigured(Collection<Multihash> peers) {
        Set<Multihash> res = new HashSet<>();
        peers.forEach(p -> res.add(p.bareMultihash()));
        configured = res;
    }

    /** Whether a round of reconciliation has found nothing to fetch (remove-pki §4.3.1 step 6). Until then a subject
     *  this node lacks may simply not have reached it, so it answers "not synced yet" rather than "not found".
     */
    public boolean isSynced() {
        return synced;
    }

    public void requireSynced(Subject s) {
        if (! synced && ! replica.log(s).exists())
            throw new IllegalStateException(NOT_SYNCED);
    }

    public static final String NOT_SYNCED = "The routing log here is not synced yet";

    public RoutingPeer dial(Multihash peer) {
        return dial.apply(peer);
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

    /** A server asked for by a later peerid, its current key's or its endpoint's, is answered with its records. */
    public List<SignedRecord> records(Subject s) {
        if (! s.isIdentity() && ! replica.log(s).exists()) {
            Optional<ServerLog> byPeer = replica.serverForPeer(s.id);
            if (byPeer.isPresent())
                return replica.records(byPeer.get().subject(), dial);
        }
        requireSynced(s);
        return replica.records(s, dial);
    }

    /** Several subjects' records, leaving out any that can't be served, within one deadline and a bound on size. */
    public Map<Subject, List<SignedRecord>> recordsOf(List<Subject> subjects) {
        Map<Subject, List<SignedRecord>> res = new LinkedHashMap<>();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(FETCH_TIMEOUT_SECONDS);
        long bytes = 0;
        for (Subject s : subjects) {
            if (System.nanoTime() > deadline)
                break;
            try {
                List<SignedRecord> records = records(s);
                for (SignedRecord r : records)
                    bytes += r.serialize().length;
                if (bytes > HttpRoutingPeer.MAX_RESPONSE / 2)
                    break;
                res.put(s, records);
            } catch (RuntimeException e) {
                LOG.log(Level.FINE, "Couldn't serve " + s + ": " + e.getMessage());
            }
        }
        return res;
    }

    /** A page of this node's log from a place in it, with the records of each subject it can serve at once. */
    public RoutingPeer.Page page(int kind, Reconciliation.Cursor after, int limit) {
        int max = Math.min(limit, PAGE);
        List<JdbcRoutingStore.State> states = replica.store().states(kind, Reconciliation.Range.ALL, after, max);
        Map<Subject, List<SignedRecord>> res = new LinkedHashMap<>();
        long bytes = 0;
        Optional<Reconciliation.Cursor> next = Optional.empty();
        int skipped = 0;
        for (JdbcRoutingStore.State st : states) {
            if (replica.servesAtOnce(st.subject)) {
                List<SignedRecord> records = replica.records(st.subject, dial);
                for (SignedRecord r : records)
                    bytes += r.serialize().length;
                res.put(st.subject, records);
            } else
                skipped++;
            next = Optional.of(Reconciliation.Cursor.after(st.subject));
            if (bytes > HttpRoutingPeer.MAX_RESPONSE / 2)
                return new RoutingPeer.Page(res, next, skipped);
        }
        return new RoutingPeer.Page(res, states.size() < max ? Optional.empty() : next, skipped);
    }

    public List<Reconciliation.Summary> summaries(int kind, List<Reconciliation.Range> ranges) {
        return ranges.stream()
                .map(r -> replica.store().summary(kind, r))
                .collect(Collectors.toList());
    }

    public List<Map<Integer, Reconciliation.Summary>> children(int kind, List<Reconciliation.Range> parents) {
        return parents.stream()
                .map(r -> replica.store().children(kind, r))
                .collect(Collectors.toList());
    }

    public List<JdbcRoutingStore.State> states(int kind, Reconciliation.Range range, Reconciliation.Cursor after, int limit) {
        return replica.store().states(kind, range, after, Math.min(limit, PAGE));
    }

    /** The whole of each range in turn, stopping before the first that would pass the limit. */
    public List<List<JdbcRoutingStore.State>> statesIn(int kind, List<Reconciliation.Range> ranges, int limit) {
        List<List<JdbcRoutingStore.State>> res = new ArrayList<>();
        int left = Math.min(limit, LISTED);
        for (Reconciliation.Range range : ranges) {
            List<JdbcRoutingStore.State> all = replica.store().states(kind, range, Reconciliation.Cursor.at(range.from()), left + 1);
            if (all.size() > left)
                break;
            res.add(all);
            left -= all.size();
        }
        return res;
    }

    public void peerRoundMillis(long millis) {
        peerRoundMillis = millis;
    }

    /** A server in the log with its proof of work done. Any other peer id costs nothing to make, so wherever a
     *  budget is kept per peer, all of those share one.
     */
    public boolean isProvenServer(Multihash peer) {
        return replica.serverForPeer(peer).filter(log -> replica.isProven(log.subject(), false)).isPresent();
    }

    public Multihash budgetKey(Multihash peer) {
        return isProvenServer(peer) ? peer.bareMultihash() : POOL;
    }

    /** Fetches each announced subject whose state differs from ours, from the announcer, within a bound on what
     *  is outstanding per announcer; beyond it the announcement is not taken, and the peer announces again later.
     *
     * @return whether every announcement was taken
     */
    public boolean announced(Multihash caller, List<JdbcRoutingStore.State> changed) {
        RoutingPeer from = dial.apply(caller);
        boolean isSyncPeer = syncPeers.containsKey(caller.bareMultihash());
        List<JdbcRoutingStore.State> wanted = new ArrayList<>();
        for (JdbcRoutingStore.State st : changed) {
            if (st.hash.length != Reconciliation.HASH_BYTES)
                throw new IllegalStateException("A state hash is " + Reconciliation.HASH_BYTES + " bytes");
            Subject s = replica.canonical(st.subject);
            // only a sync peer's claims count towards catch-up, so no one else's are kept
            if (isSyncPeer)
                saw(s, st.hash, caller);
            if (! Arrays.equals(replica.stateHash(s), st.hash))
                wanted.add(new JdbcRoutingStore.State(s, st.hash));
        }
        return fetchLater(budgetKey(caller), from, wanted);
    }

    /** Asks a peer for subjects without waiting, a batch to a call, so one that answers slowly holds no thread of
     *  ours, only places among those outstanding for it. What it answers is taken on the work pool, servers first,
     *  as identities' admissions are checked against them.
     *
     * @return false if the peer has too many outstanding for all of these
     */
    private boolean fetchLater(Multihash chargedTo, RoutingPeer from, List<JdbcRoutingStore.State> wanted) {
        AtomicInteger outstanding = fetching.computeIfAbsent(chargedTo, c -> new AtomicInteger());
        List<JdbcRoutingStore.State> taking = new ArrayList<>();
        boolean all = true;
        for (JdbcRoutingStore.State w : wanted) {
            if (inFlight.contains(w))
                continue;
            if (outstanding.incrementAndGet() > MAX_FETCHING_PER_PEER) {
                outstanding.decrementAndGet();
                all = false;
                continue;
            }
            if (! inFlight.add(w)) {
                outstanding.decrementAndGet();
                continue;
            }
            if (! w.subject.isIdentity())
                fetchingServers.merge(w.subject, 1, Integer::sum);
            taking.add(w);
        }
        if (taking.isEmpty() && outstanding.get() == 0)
            fetching.remove(chargedTo, outstanding);
        taking.sort(Comparator.comparingInt(w -> w.subject.isIdentity() ? 1 : 0));
        for (int i = 0; i < taking.size(); i += FETCH_BATCH) {
            List<JdbcRoutingStore.State> batch = new ArrayList<>(taking.subList(i, Math.min(taking.size(), i + FETCH_BATCH)));
            CompletableFuture<Map<Subject, List<SignedRecord>>> asked;
            try {
                asked = from.recordsOf(batch.stream().map(w -> w.subject).collect(Collectors.toList()))
                        .orTimeout(FETCH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (Throwable t) {
                asked = Futures.errored(t);
            }
            asked.whenCompleteAsync((got, t) -> {
                if (t != null)
                    LOG.log(Level.FINE, "Fetching " + batch.size() + " subjects from " + from.id() + " failed: " + t.getMessage());
                for (JdbcRoutingStore.State w : batch) {
                    Subject s = w.subject;
                    try {
                        List<SignedRecord> records = t != null ? null : got.get(s);
                        if (records != null)
                            accept(from, s, records, false, false, true);
                    } catch (Throwable e) {
                        LOG.log(Level.FINE, "Taking " + s + " from " + from.id() + " failed: " + e.getMessage());
                    } finally {
                        inFlight.remove(w);
                        if (outstanding.decrementAndGet() == 0)
                            fetching.remove(chargedTo, outstanding);
                        if (! s.isIdentity()) {
                            fetchingServers.computeIfPresent(s, (x, n) -> n > 1 ? n - 1 : null);
                            if (! isOnItsWay(s))
                                release(s.id);
                        }
                    }
                }
            }, work);
        }
        return all;
    }

    public Outcome offered(Multihash caller, Subject s, List<SignedRecord> records) {
        return accept(dial.apply(caller), s, records, false, false, true);
    }

    // ---- acting for this node

    /** Runs off the caller's thread: change listeners are called while the replica holds the subject's lock. */
    private void announce(JdbcRoutingStore.State st) {
        for (RoutingPeer peer : syncPeers.values()) {
            Map<Subject, JdbcRoutingStore.State> pending = outbox.computeIfAbsent(peer.id().bareMultihash(), p -> new ConcurrentHashMap<>());
            if (pending.size() < MAX_UNANNOUNCED_PER_PEER || pending.containsKey(st.subject))
                pending.put(st.subject, st);
        }
        if (flushing.compareAndSet(false, true))
            flushLater.accept(this::flush);
    }

    /** Sends each peer what has changed since the last time, in as few calls as hold it: a peer allows each caller
     *  only so many calls, however much is in them.
     */
    private void flush() {
        flushing.set(false);
        for (RoutingPeer peer : new ArrayList<>(syncPeers.values())) {
            Map<Subject, JdbcRoutingStore.State> pending = outbox.remove(peer.id().bareMultihash());
            if (pending == null)
                continue;
            List<JdbcRoutingStore.State> states = new ArrayList<>(pending.values());
            for (int i = 0; i < states.size(); i += RoutingHandler.MAX_ITEMS)
                send(peer, states.subList(i, Math.min(states.size(), i + RoutingHandler.MAX_ITEMS)));
        }
        outbox.keySet().retainAll(syncPeers.keySet());
    }

    /** Announces changes this often rather than as each happens, so those of a busy second go in one call. */
    public void announceEvery(ScheduledExecutorService timer, long millis) {
        flushLater = flush -> timer.schedule(() -> work.submit(flush), millis, TimeUnit.MILLISECONDS);
    }

    private void send(RoutingPeer peer, List<JdbcRoutingStore.State> states) {
        peer.announce(states).exceptionally(t -> false).thenAccept(ok -> {
            if (ok)
                return;
            Map<Subject, JdbcRoutingStore.State> pending = unannounced.computeIfAbsent(peer.id().bareMultihash(), p -> new ConcurrentHashMap<>());
            for (JdbcRoutingStore.State st : states)
                if (pending.size() < MAX_UNANNOUNCED_PER_PEER || pending.containsKey(st.subject))
                    pending.put(st.subject, st);
        });
    }

    /** This server's own subject, whose state is announced every round: nobody reconciles with a server they haven't
     *  chosen, so a server whose first announcement was lost, or came before it had peers, would otherwise keep its
     *  records to itself.
     */
    public void setSelf(Subject s) {
        self = Optional.of(s);
    }

    private void reannounce() {
        for (RoutingPeer peer : new ArrayList<>(syncPeers.values())) {
            List<JdbcRoutingStore.State> states = new ArrayList<>();
            self.ifPresent(s -> states.add(new JdbcRoutingStore.State(s, replica.stateHash(s))));
            Map<Subject, JdbcRoutingStore.State> pending = unannounced.remove(peer.id().bareMultihash());
            if (pending != null)
                for (JdbcRoutingStore.State st : pending.values())
                    states.add(new JdbcRoutingStore.State(st.subject, replica.stateHash(st.subject)));
            for (int i = 0; i < states.size(); i += RoutingHandler.MAX_ITEMS)
                send(peer, states.subList(i, Math.min(states.size(), i + RoutingHandler.MAX_ITEMS)));
        }
        unannounced.keySet().retainAll(syncPeers.keySet());
    }

    public Outcome fetch(RoutingPeer from, Subject s, boolean fromQueue) {
        return accept(from, s, from.records(s).join(), fromQueue, false);
    }

    /** Pulls one subject from the server in front of this one, for a lookup this node is making. It is used at
     *  once; if its proof of work is not done it is kept only in a bounded cache, never stored or passed on.
     */
    public Optional<SubjectLog> pull(RoutingPeer from, Subject s) {
        accept(from, s, from.records(s).join(), false, true);
        return lookup(s);
    }

    /** A light server's lookup: what it asks a full node for is its own to use, and isn't queued behind others. */
    public Outcome pullForLookup(RoutingPeer from, Subject s) {
        return accept(from, s, from.records(s).join(), true, true);
    }

    public Optional<SubjectLog> lookup(Subject s) {
        SubjectLog log = replica.log(s);
        if (log.exists())
            return Optional.of(log);
        return Optional.ofNullable(unproven.get(replica.canonical(s)));
    }

    private void saw(Subject s, byte[] hash, Multihash peer) {
        if (hash.length != Reconciliation.HASH_BYTES)
            throw new IllegalStateException("A state hash is " + Reconciliation.HASH_BYTES + " bytes");
        synchronized (seen) {
            Map<Multihash, ByteArrayWrapper> claims = seen.computeIfAbsent(s, x -> new HashMap<>());
            claims.put(peer.bareMultihash(), new ByteArrayWrapper(hash));
            if (claims.size() > syncPeers.size())
                claims.keySet().retainAll(syncPeers.keySet());
        }
    }

    /** Most of the sync peers this node chose already hold this state, so it passed every honest node's limits;
     *  and one its operator configured, if any is a sync peer, so that peers drawn from an attacker's many address
     *  ranges can't outvote the rest and pass a flood off as catch-up.
     */
    private boolean isCatchUp(Subject s, byte[] hash) {
        Set<Multihash> holders = new HashSet<>();
        ByteArrayWrapper state = new ByteArrayWrapper(hash);
        synchronized (seen) {
            seen.getOrDefault(s, Map.of()).forEach((peer, claimed) -> {
                if (claimed.equals(state))
                    holders.add(peer);
            });
        }
        holders.retainAll(syncPeers.keySet());
        if (holders.isEmpty() || holders.size() * 2 <= syncPeers.size())
            return false;
        Set<Multihash> chosen = configured;
        return holders.stream().anyMatch(chosen::contains) || syncPeers.keySet().stream().noneMatch(chosen::contains);
    }

    /** Applies a peer's records for a subject. A subject new to this node needs a proven inception, and then waits
     *  its turn in its admitting server's address bucket unless it comes from that queue or is catch-up.
     */
    Outcome accept(RoutingPeer from, Subject requested, List<SignedRecord> records, boolean fromQueue) {
        return accept(from, requested, records, fromQueue, false);
    }

    /** @param pulled whether this node asked for these records to use them, as when following a link to someone
     *               who signed up moments ago: then an inception whose proof of work isn't done is still used, from a
     *               bounded cache, whereas one handed in unasked is turned away before its signatures are checked
     */
    Outcome accept(RoutingPeer from, Subject requested, List<SignedRecord> records, boolean fromQueue, boolean pulled) {
        return accept(from, requested, records, fromQueue, pulled, false);
    }

    /** @param unasked whether the peer handed these in or announced them, rather than this node choosing to ask it:
     *                then nothing here waits on that peer, which may answer as slowly as it likes
     */
    Outcome accept(RoutingPeer from, Subject requested, List<SignedRecord> records, boolean fromQueue, boolean pulled, boolean unasked) {
        Multihash sender = from.id().bareMultihash();
        if (! rejections.has(sender))
            return Outcome.Rejected;
        Subject s = ownSubject(replica.canonical(requested), records);
        SubjectLog log = replica.log(s);
        boolean catchUp = isCatchUp(s, replica.stateHash(records));
        List<SignedRecord> fresh = replica.notHeld(s, records);
        if (! catchUp && fresh.size() > (pulled ? MAX_PULLED_RECORDS : MAX_UNSOLICITED_RECORDS))
            return Outcome.Limited;
        if (log.inception().isEmpty()) {
            Optional<SignedRecord> inception = records.stream().filter(RoutingReplica::isInception).findFirst();
            if (inception.isEmpty())
                return Outcome.Unconnected;
            Optional<Multihash> admitter = admitter(inception.get());
            Multihash chargedTo = admitter.orElse(s.id);
            // A new server is its own admitter, so turns go by who handed it over: one node with a thousand
            // new servers gets one turn, not a thousand ahead of an honest one's.
            Multihash turn = admitter.orElse(sender);
            if (! pulled && ! replica.isWorkDone(s, inception.get())) {
                rejections.tryTake(sender);
                return Outcome.Rejected;
            }
            if (admitter.isPresent() && replica.log(Subject.server(admitter.get())).inception().isEmpty()) {
                Subject server = Subject.server(admitter.get());
                if (! unasked)
                    fetchAdmitter(from, server, fromQueue);
                else if (! isOnItsWay(server))
                    fetchLater(budgetKey(sender), from, List.of(new JdbcRoutingStore.State(server, ANY_STATE)));
                if (replica.log(server).inception().isEmpty()) {
                    if (pulled)
                        unproven.put(s, replica.preview(s, records));
                    // Only behind a server on its way in. Naming one nobody holds costs nothing, so such an
                    // identity is left with its sender, and reconciliation brings it once the server is here.
                    if (! catchUp && awaitAdmitter(admitter.get(), new Offer(from, s)))
                        return Outcome.Limited;
                    // unless it was taken just now
                    if (replica.log(server).inception().isEmpty())
                        return Outcome.Unconnected;
                }
            }
            if (! replica.isProven(s, inception.get(), catchUp)) {
                if (pulled)
                    unproven.put(s, replica.preview(s, records));
                return Outcome.Ignored;
            }
            if (! catchUp && ! fromQueue) {
                if (pulled)
                    unproven.put(s, replica.preview(s, records));
                if (admitter.isEmpty()) {
                    place(from, s, turn, records);
                    return Outcome.Limited;
                }
                // With a token to spare and nothing ahead of it, it is taken as it is rather than fetched again.
                // Not a pull, which is used whole from the cache until its records have all passed their limits.
                if (pulled || ! inceptions.tryTakeNow(buckets.apply(chargedTo))) {
                    if (inceptions.offer(buckets.apply(chargedTo), turn, new Offer(from, s)))
                        takeSoon();
                    return Outcome.Limited;
                }
            }
        }
        RoutingReplica.Gate gate = (subject, record, isNewInception) -> {
            if (catchUp)
                return true;
            if (RoutingReplica.isInception(record) && ! isNewInception && ! updates.tryTakeEvidence(subject))
                return false;
            RecordType type = RecordType.of(record);
            if (updates.isLimited(type) && ! updates.tryTake(subject, type)) {
                if (record instanceof Update)
                    hold(subject, from, record);
                return false;
            }
            return charge(from, record);
        };
        Outcome res = Outcome.Duplicate;
        List<SignedRecord> pending = new ArrayList<>(fresh);
        boolean progress = true;
        while (progress && ! pending.isEmpty()) {
            progress = false;
            List<SignedRecord> retry = new ArrayList<>();
            for (SignedRecord r : pending) {
                Outcome o = replica.add(s, r, gate);
                if (o == Outcome.Rejected) {
                    rejections.tryTake(sender);
                    return Outcome.Rejected;
                }
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

    /** A server's records sent for one of its later peerids belong to its first. */
    private Subject ownSubject(Subject s, List<SignedRecord> records) {
        if (s.isIdentity() || replica.log(s).exists())
            return s;
        return records.stream()
                .filter(r -> r instanceof ServerInception)
                .map(r -> Subject.server(((ServerInception) r).peerId()))
                .findFirst()
                .map(replica::canonical)
                .orElse(s);
    }

    private static Optional<Multihash> admitter(SignedRecord inception) {
        return inception instanceof IdentityInception ?
                Optional.of(((IdentityInception) inception).admittingServer().bareMultihash()) :
                Optional.empty();
    }

    /** Records new to this replica are charged to the peer that sent them, up to a cap on what each peer may add. */
    private boolean charge(RoutingPeer from, SignedRecord record) {
        return charged.tryTake(from.id().bareMultihash(), (record.serialize().length + 1023) / 1024);
    }

    /** An update that arrives before its token is kept, the newest only, and applied when the token is due. */
    private void hold(Subject s, RoutingPeer from, SignedRecord record) {
        if (held.size() >= MAX_HELD && ! held.containsKey(s))
            return;
        held.merge(s, new Held(from, record, updates.nextToken(s, RecordType.of(record))), (old, update) ->
                ((Update) update.record).counter() >= ((Update) old.record).counter() ? update : old);
    }

    /** Takes the inceptions whose turn has come and the held updates whose token is due. Run periodically. */
    public void tick() {
        inceptions.resize(replica.store().summary(Subject.IDENTITY, Reconciliation.Range.ALL).count);
        takeInceptions();
        long now = clock.getAsLong();
        for (Map.Entry<Subject, Held> e : new ArrayList<>(held.entrySet())) {
            if (e.getValue().due > now)
                continue;
            held.remove(e.getKey(), e.getValue());
            accept(e.getValue().from, e.getKey(), List.of(e.getValue().record), false);
        }
        updates.prune();
    }

    /** A bucket with a token to spare passes a new identity on at once rather than at the next tick, so honest
     *  signups cross the network in network time.
     */
    private void takeSoon() {
        if (taking.compareAndSet(false, true))
            work.submit(() -> {
                taking.set(false);
                takeInceptions();
            });
    }

    /** Queues a new server's inception once it is placed in a bucket, which may take a dial. Past a few placements
     *  at once from one sender, the rest wait in the shared bucket.
     */
    private void place(RoutingPeer from, Subject s, Multihash turn, List<SignedRecord> records) {
        if (placing.containsKey(s))
            return;
        if (placing.values().stream().filter(turn::equals).count() >= MAX_PLACING_PER_SENDER) {
            if (inceptions.offer(buckets.apply(s.id), turn, new Offer(from, s)))
                takeSoon();
            return;
        }
        if (placing.putIfAbsent(s, turn) != null)
            return;
        SubjectLog claimed = replica.preview(s, records);
        work.submit(() -> {
            AddressBucket bucket = AddressBucket.UNVERIFIED;
            try {
                bucket = placer.apply(s.id, claimed);
                if (inceptions.offer(bucket, turn, new Offer(from, s)))
                    takeSoon();
            } catch (Throwable t) {
                LOG.log(Level.FINE, "Placing " + s + " failed: " + t.getMessage());
            } finally {
                placing.remove(s);
                if (! isOnItsWay(s))
                    release(s.id);
            }
        });
    }

    /** A server being fetched, or whose inception, its proof of work done, is being placed in a bucket or waiting
     *  its turn there.
     */
    private boolean isOnItsWay(Subject server) {
        return fetchingServers.containsKey(server) || placing.containsKey(server) || inceptions.isWaiting(new Offer(null, server));
    }

    /** An identity whose admitting server is on its way in waits for it, to be queued in its bucket after it. */
    private boolean awaitAdmitter(Multihash admitter, Offer o) {
        Subject server = Subject.server(admitter.bareMultihash());
        if (! isOnItsWay(server))
            return false;
        synchronized (awaitingAdmitter) {
            Map<Subject, Offer> waiting = awaitingAdmitter.computeIfAbsent(server.id, a -> new LinkedHashMap<>());
            if (waiting.size() < MAX_OFFERS_PER_SERVER)
                waiting.putIfAbsent(o.subject, o);
        }
        if (! isOnItsWay(server))
            release(server.id); // taken meanwhile
        return true;
    }

    /** Queues the identities that waited for a server, if it was taken; otherwise they stay with their senders. */
    private void release(Multihash server) {
        Map<Subject, Offer> waiting = awaitingAdmitter.remove(server.bareMultihash());
        if (waiting == null || replica.log(Subject.server(server)).inception().isEmpty())
            return;
        AddressBucket bucket = buckets.apply(server);
        for (Offer o : waiting.values())
            inceptions.offer(bucket, server, o);
        takeSoon();
    }

    /** Fetches the inceptions whose turn has come, those from one peer together. */
    private void takeInceptions() {
        Map<Multihash, List<Offer>> byPeer = new LinkedHashMap<>();
        for (Offer o : inceptions.take())
            byPeer.computeIfAbsent(o.from.id().bareMultihash(), p -> new ArrayList<>()).add(o);
        for (List<Offer> offers : byPeer.values()) {
            RoutingPeer from = offers.get(0).from;
            for (int i = 0; i < offers.size(); i += FETCH_BATCH) {
                List<Offer> batch = offers.subList(i, Math.min(offers.size(), i + FETCH_BATCH));
                // A server taken from the queue is still on its way until it is stored: an identity it admitted
                // that arrives meanwhile waits for it, rather than being turned away for naming a server nobody holds.
                for (Offer o : batch)
                    if (! o.subject.isIdentity())
                        fetchingServers.merge(o.subject, 1, Integer::sum);
                Map<Subject, List<SignedRecord>> got = Map.of();
                try {
                    got = from.recordsOf(batch.stream().map(o -> o.subject).collect(Collectors.toList())).join();
                } catch (Throwable t) {
                    LOG.log(Level.FINE, "Fetching " + batch.size() + " subjects from " + from.id() + " failed: " + t.getMessage());
                }
                for (Offer o : batch) {
                    try {
                        List<SignedRecord> records = got.get(o.subject);
                        if (records != null)
                            accept(from, o.subject, records, true);
                    } catch (Throwable t) {
                        LOG.log(Level.FINE, "Taking " + o.subject + " failed: " + t.getMessage());
                    }
                    if (! o.subject.isIdentity()) {
                        fetchingServers.computeIfPresent(o.subject, (x, n) -> n > 1 ? n - 1 : null);
                        if (! isOnItsWay(o.subject))
                            release(o.subject.id);
                    }
                }
            }
        }
    }

    public int waitingInceptions() {
        return inceptions.waiting();
    }

    public int outstandingFetches() {
        return inFlight.size();
    }

    public int claimedStates(Subject s) {
        synchronized (seen) {
            return seen.getOrDefault(replica.canonical(s), Map.of()).size();
        }
    }

    public int heldUpdates() {
        return held.size();
    }

    // ---- telling which peers withhold

    /** Claimed a state, then served only records this node already holds, adding up to neither that state nor
     *  ours. An honest peer that moved on since it claimed serves what it moved on to, which is new here or ours.
     */
    private boolean misrepresented(Subject s, byte[] claimed, List<SignedRecord> served) {
        byte[] servedHash = replica.stateHash(served);
        return ! Arrays.equals(servedHash, claimed)
                && ! Arrays.equals(servedHash, replica.stateHash(s))
                && replica.holdsAll(s, served);
    }

    /** Strikes each peer far further behind than the second most current, and suspects it after a few rounds in a
     *  row. Relative, so peers lagging alike while a flood is taken at each node's own pace are not struck; it needs
     *  two current peers to compare with.
     */
    private void judge(List<RoutingPeer> peers) {
        List<Integer> behind = new ArrayList<>();
        for (RoutingPeer p : peers)
            behind.add(tallies.getOrDefault(p.id().bareMultihash(), new Tally()).behind());
        if (behind.size() < SYNCED_PEERS)
            return;
        Collections.sort(behind);
        int threshold = MIN_BEHIND + 4 * behind.get(1);
        for (RoutingPeer p : peers) {
            Multihash id = p.id().bareMultihash();
            Tally t = tallies.getOrDefault(id, new Tally());
            if (t.behind() <= threshold) {
                strikes.remove(id);
                continue;
            }
            int n = strikes.merge(id, 1, Integer::sum);
            if (n >= STRIKES) {
                strikes.remove(id);
                suspect(id, "it was behind on " + t.behind() + " records for " + n + " rounds in a row");
            }
        }
    }

    private void suspect(Multihash peer, String why) {
        Multihash id = peer.bareMultihash();
        if (suspects.put(id, clock.getAsLong() + SUSPECT_MILLIS) == null)
            LOG.info("Not syncing with " + id + " for a day: " + why);
    }

    /** A peer seen withholding or misrepresenting records, not to be drawn as a sync peer for a while. Only this
     *  node's own view: nothing a peer answers is signed, so it can't be shown to anyone else.
     */
    public boolean isSuspect(Multihash peer) {
        Long until = suspects.get(peer.bareMultihash());
        if (until == null)
            return false;
        if (until < clock.getAsLong()) {
            suspects.remove(peer.bareMultihash(), until);
            return false;
        }
        return ! configured.contains(peer.bareMultihash());
    }

    /** Peers that didn't answer two rounds running, not worth drawing for a few hours. */
    public Set<Multihash> unreachable() {
        long now = clock.getAsLong();
        unreachable.values().removeIf(until -> until < now);
        return new HashSet<>(unreachable.keySet());
    }

    public Set<Multihash> suspects() {
        Set<Multihash> res = new HashSet<>();
        for (Multihash id : new ArrayList<>(suspects.keySet()))
            if (isSuspect(id))
                res.add(id);
        return res;
    }

    // ---- reconciliation

    /** Stands for what a peer holds in a range it was asked to list and hasn't yet: something, and not much. */
    private static final Reconciliation.Summary UNLISTED = new Reconciliation.Summary(1, new byte[32]);

    /** A range that differs from a peer's, with what the peer says it holds there. */
    private static final class Differing {
        final Reconciliation.Range range;
        final Reconciliation.Summary theirs;

        Differing(Reconciliation.Range range, Reconciliation.Summary theirs) {
            this.range = range;
            this.theirs = theirs;
        }
    }

    /** The peer's states that differ from ours, found by comparing range fingerprints, descending into those that
     *  differ and listing the small ones. Each is also recorded as held by that peer. Both sides answer from sums
     *  they keep, so the cost is by what differs, not by what is held.
     */
    public List<JdbcRoutingStore.State> differences(RoutingPeer peer, int kind) {
        List<JdbcRoutingStore.State> res = new ArrayList<>();
        differences(peer, kind, new Budget(), res);
        return res;
    }

    /** @param res filled as differences are found, so that what was found is kept if the peer then fails
     *  @return whether the comparison finished, rather than running out of the peer's share of the round
     */
    private boolean differences(RoutingPeer peer, int kind, Budget budget, List<JdbcRoutingStore.State> res) {
        try {
            return compare(peer, kind, budget, res);
        } catch (Spent e) {
            return false;
        }
    }

    private boolean compare(RoutingPeer peer, int kind, Budget budget, List<JdbcRoutingStore.State> res) {
        JdbcRoutingStore store = replica.store();
        int start = res.size();
        Reconciliation.Summary all = ask(peer, budget, () -> peer.summaries(kind, List.of(Reconciliation.Range.ALL))).get(0);
        Deque<Differing> todo = new ArrayDeque<>();
        if (! store.summary(kind, Reconciliation.Range.ALL).equals(all))
            todo.add(new Differing(Reconciliation.Range.ALL, all));
        while (! todo.isEmpty()) {
            List<Reconciliation.Range> descend = new ArrayList<>(), list = new ArrayList<>();
            while (! todo.isEmpty() && descend.size() < BATCH && list.size() < LIST_RANGES) {
                Differing d = todo.poll();
                if (d.range.depth < Reconciliation.MAX_DEPTH && d.theirs.count > LIST_BELOW)
                    descend.add(d.range);
                else if (d.theirs.count == 0)
                    tally(peer, kind, d.range, Map.of());
                else
                    list.add(d.range);
            }
            if (! list.isEmpty()) {
                // Small ranges are listed many to a call: a node that is far behind differs in most of them.
                List<List<JdbcRoutingStore.State>> listed = ask(peer, budget, () -> peer.statesIn(kind, list, LISTED));
                int total = listed.stream().mapToInt(List::size).sum();
                if (listed.size() > list.size() || total > LISTED)
                    throw new IllegalStateException("Peer " + peer.id() + " sent more states than asked for");
                for (int i = 0; i < listed.size(); i++) {
                    Reconciliation.Range range = list.get(i);
                    Map<ByteArrayWrapper, byte[]> theirs = new HashMap<>();
                    differing(peer, kind, range, Reconciliation.Cursor.at(range.from()), listed.get(i), theirs, res);
                    tally(peer, kind, range, theirs);
                }
                // one too long for the call is read a page at a time
                if (listed.size() < list.size())
                    paged(peer, kind, list.get(listed.size()), MAX_DIFFERENCES - (res.size() - start), budget, res);
                for (int i = list.size() - 1; i > listed.size(); i--)
                    todo.addFirst(new Differing(list.get(i), UNLISTED));
                if (res.size() - start >= MAX_DIFFERENCES)
                    return false;
            }
            if (descend.isEmpty())
                continue;
            List<Map<Integer, Reconciliation.Summary>> remote = ask(peer, budget, () -> peer.children(kind, descend));
            if (remote.size() != descend.size())
                throw new IllegalStateException("Peer " + peer.id() + " answered for other ranges than asked");
            for (int i = 0; i < descend.size(); i++) {
                Reconciliation.Range parent = descend.get(i);
                Map<Integer, Reconciliation.Summary> theirs = remote.get(i), ours = store.children(kind, parent);
                Set<Integer> either = new TreeSet<>(theirs.keySet());
                either.addAll(ours.keySet());
                for (int child : either) {
                    if (child < 0 || child >= Reconciliation.FANOUT)
                        throw new IllegalStateException("Peer " + peer.id() + " answered with a range not asked for");
                    Reconciliation.Summary held = theirs.getOrDefault(child, Reconciliation.Summary.EMPTY);
                    if (! held.equals(ours.getOrDefault(child, Reconciliation.Summary.EMPTY)))
                        todo.add(new Differing(parent.child(child), held));
                }
            }
        }
        return true;
    }

    /** A peer's states in a range that differ from ours, a page at a time. A round takes at most
     *  {@link #MAX_DIFFERENCES} from one peer; the rest wait for the next round.
     */
    private void paged(RoutingPeer peer, int kind, Reconciliation.Range range, int max, Budget budget, List<JdbcRoutingStore.State> res) {
        int start = res.size();
        Map<ByteArrayWrapper, byte[]> theirs = new HashMap<>();
        Reconciliation.Cursor after = Reconciliation.Cursor.at(range.from());
        while (res.size() - start < max) {
            Reconciliation.Cursor from = after;
            List<JdbcRoutingStore.State> page = ask(peer, budget, () -> peer.states(kind, range, from, PAGE));
            if (page.size() > PAGE)
                throw new IllegalStateException("Peer " + peer.id() + " sent more states than asked for");
            after = differing(peer, kind, range, after, page, theirs, res);
            if (page.size() < PAGE) {
                tally(peer, kind, range, theirs);
                return;
            }
        }
    }

    /** Takes a stretch of a peer's states in a range. A peer can answer anything, so they must move forward within
     *  the range.
     *
     * @return the place after the last of them
     */
    private Reconciliation.Cursor differing(RoutingPeer peer, int kind, Reconciliation.Range range, Reconciliation.Cursor after,
                                            List<JdbcRoutingStore.State> states, Map<ByteArrayWrapper, byte[]> theirs,
                                            List<JdbcRoutingStore.State> res) {
        for (JdbcRoutingStore.State st : states) {
            byte[] id = st.subject.idBytes();
            int slot = Reconciliation.slot(id);
            boolean forward = slot > after.slot || (slot == after.slot && Subject.compareBytes(id, after.id) > 0);
            if (st.subject.kind != kind || ! forward || ! range.contains(slot))
                throw new IllegalStateException("Peer " + peer.id() + " sent states out of order or range");
            after = new Reconciliation.Cursor(slot, id);
            saw(st.subject, st.hash, peer.id());
            theirs.put(new ByteArrayWrapper(id), st.hash);
            if (! Arrays.equals(replica.stateHash(st.subject), st.hash))
                res.add(st);
        }
        return after;
    }

    /** Beyond this a peer is far enough behind; counting more would cost a lookup each. */
    private static final int MAX_COUNTED = 1000;

    /** A subject whose admitting server's bucket has had inceptions queued for its rate recently: honest nodes
     *  take those at their own pace, in their own order, so lacking one says nothing about a peer.
     */
    private boolean isCongested(Subject s) {
        Optional<SignedRecord> inception = replica.log(s).inception().map(v -> (SignedRecord) v.record);
        if (inception.isEmpty())
            return false;
        return inceptions.congested(buckets.apply(admitter(inception.get()).orElse(s.id)));
    }


    /** Compares a peer's complete list of states in a range with ours, for {@link Tally}. */
    private void tally(RoutingPeer peer, int kind, Reconciliation.Range range, Map<ByteArrayWrapper, byte[]> theirs) {
        Tally t = tallies.computeIfAbsent(peer.id().bareMultihash(), p -> new Tally());
        long cutoff = clock.getAsLong() - BEHIND_GRACE_MILLIS;
        Set<ByteArrayWrapper> ours = new HashSet<>();
        Reconciliation.Cursor after = Reconciliation.Cursor.at(range.from());
        int scanned = 0;
        boolean complete = false;
        while (scanned < LACK_SCAN) {
            List<JdbcRoutingStore.State> page = replica.store().states(kind, range, after, PAGE);
            for (JdbcRoutingStore.State st : page) {
                ByteArrayWrapper id = new ByteArrayWrapper(st.subject.idBytes());
                ours.add(id);
                byte[] held = theirs.get(id);
                Past p;
                synchronized (past) {
                    p = past.get(st.subject);
                }
                if (held == null) {
                    if ((p == null || p.changedAt < cutoff) && t.lacks < MAX_COUNTED && ! isCongested(st.subject))
                        t.lacks++;
                } else if (! Arrays.equals(held, st.hash) && p != null) {
                    Long supersededAt = p.superseded.get(new ByteArrayWrapper(held));
                    if (supersededAt != null && supersededAt < cutoff)
                        t.stale++;
                }
            }
            scanned += page.size();
            if (page.size() < PAGE) {
                complete = true;
                break;
            }
            after = Reconciliation.Cursor.after(page.get(page.size() - 1).subject);
        }
        if (complete)
            for (ByteArrayWrapper id : theirs.keySet())
                if (! ours.contains(id))
                    t.extra++;
    }

    public static final int MAX_DIFFERENCES = 100_000;

    /** Whether this node is still reading a configured peer's log end to end, so rounds should follow one another. */
    public boolean isBootstrapping() {
        return bootstrapping;
    }

    public void snapshotBelow(int subjects) {
        snapshotBelow = subjects;
    }

    /** Reads on through a configured peer's log, servers and then identities, in the order every node keeps, taking
     *  each page as catch-up: its operator chose that peer to start from, every record is checked, and nothing here
     *  is passed on unasked. Where it has got to is kept, so a restart carries on.
     *
     * @return how many subjects changed
     */
    private int snapshot() {
        JdbcRoutingStore store = replica.store();
        Optional<byte[]> progress = store.getMeta(SNAPSHOT);
        if (synced || progress.map(p -> Arrays.equals(p, SNAPSHOT_DONE)).orElse(false)) {
            bootstrapping = false;
            return 0;
        }
        Optional<RoutingPeer> seed = syncPeers.values().stream()
                .filter(p -> configured.contains(p.id().bareMultihash()) && ! unreachable.containsKey(p.id().bareMultihash())
                        && ! cantPage.contains(p.id().bareMultihash()))
                .findFirst();
        if (seed.isEmpty()) {
            bootstrapping = false;
            return 0;
        }
        RoutingPeer from = seed.get();
        int changed = 0;
        try {
            int kind = Subject.SERVER;
            Reconciliation.Cursor after = Reconciliation.Cursor.START;
            if (progress.isPresent()) {
                CborObject.CborMap m = (CborObject.CborMap) CborObject.fromByteArray(progress.get());
                kind = (int) m.getLong("k");
                after = Reconciliation.Cursor.fromCbor(m.get("a"));
            } else {
                long behind = 0;
                for (int k : new int[]{Subject.SERVER, Subject.IDENTITY})
                    behind += from.summaries(k, List.of(Reconciliation.Range.ALL)).join().get(0).count
                            - store.summary(k, Reconciliation.Range.ALL).count;
                if (behind < snapshotBelow) {
                    store.setMeta(SNAPSHOT, SNAPSHOT_DONE);
                    bootstrapping = false;
                    return 0;
                }
                LOG.info("Reading the routing log of " + from.id() + ", which holds " + behind + " more subjects");
            }
            bootstrapping = true;
            quiet.set(true);
            long served = 0, skipped = 0;
            // The next page is asked for while this one is checked and stored.
            CompletableFuture<RoutingPeer.Page> ahead = null;
            for (int pages = 0; pages < SNAPSHOT_PAGES_PER_ROUND; pages++) {
                RoutingPeer.Page page = (ahead != null ? ahead : from.page(kind, after, PAGE)).join();
                ahead = null;
                served += page.records.size();
                skipped += page.records.isEmpty() && page.next.isPresent() ? Math.max(1, page.skipped) : page.skipped;
                if (skipped > served) {
                    // Nothing is marked done: another configured peer, or reconciliation, brings what this one left out.
                    cantPage.add(from.id().bareMultihash());
                    bootstrapping = false;
                    store.setMeta(SNAPSHOT, progress(Subject.SERVER, Reconciliation.Cursor.START));
                    LOG.info("Not reading the routing log of " + from.id() + ": it holds most subjects without their " +
                            "signatures. Starting from little is far quicker from an archival routing peer");
                    return changed;
                }
                if (page.records.size() > PAGE)
                    throw new IllegalStateException("Peer " + from.id() + " sent a longer page than asked for");
                for (Subject s : page.records.keySet())
                    if (s.kind != kind || ! after.isBefore(s))
                        throw new IllegalStateException("Peer " + from.id() + " sent a page out of order");
                if (page.next.isPresent() && ! isForward(after, page.next.get()))
                    throw new IllegalStateException("Peer " + from.id() + " sent a page that doesn't move on");
                // Servers are stored before identities are asked for, whose admissions are checked against them.
                if (page.next.isPresent() && pages + 1 < SNAPSHOT_PAGES_PER_ROUND)
                    ahead = from.page(kind, page.next.get(), PAGE);
                changed += replica.takeAll(page.records);
                if (page.next.isPresent())
                    after = page.next.get();
                else if (kind == Subject.SERVER) {
                    kind = Subject.IDENTITY;
                    after = Reconciliation.Cursor.START;
                } else {
                    store.setMeta(SNAPSHOT, SNAPSHOT_DONE);
                    bootstrapping = false;
                    LOG.info("Read the routing log of " + from.id() + " to its end");
                    return changed;
                }
                store.setMeta(SNAPSHOT, progress(kind, after));
            }
        } catch (Throwable t) {
            // this round reconciles instead, and the next carries on from where this got to
            bootstrapping = false;
            LOG.log(Level.INFO, "Reading the routing log of " + from.id() + " failed: " + t.getMessage());
        } finally {
            quiet.set(false);
        }
        return changed;
    }

    private static byte[] progress(int kind, Reconciliation.Cursor after) {
        Map<String, Cborable> m = new TreeMap<>();
        m.put("k", new CborObject.CborLong(kind));
        m.put("a", after);
        return CborObject.CborMap.build(m).serialize();
    }

    private static boolean isForward(Reconciliation.Cursor from, Reconciliation.Cursor to) {
        return to.slot != from.slot ? to.slot > from.slot : Subject.compareBytes(to.id, from.id) > 0;
    }

    /** A sync peer that didn't answer, or kept failing, this round: after two such rounds it isn't drawn for a while. */
    private void missed(RoutingPeer peer) {
        Multihash id = peer.id().bareMultihash();
        if (missed.merge(id, 1, Integer::sum) >= 2) {
            missed.remove(id);
            unreachable.put(id, clock.getAsLong() + UNREACHABLE_MILLIS);
        }
    }

    /** One round with every sync peer: gather what differs from all of them first, so a state most of them hold is
     *  known to be catch-up, then fetch each subject's most widely held state from one of its holders, servers
     *  first since identities' admissions are checked against them. Bootstrap is the same round from an empty
     *  replica.
     */
    public int syncRound() {
        Map<Subject, Map<ByteArrayWrapper, List<RoutingPeer>>> wanted = new TreeMap<>(
                Comparator.comparingInt((Subject s) -> s.isIdentity() ? 1 : 0).thenComparing(s -> s));
        int read = snapshot();
        if (bootstrapping)
            return read;
        reannounce();
        tallies.clear();
        List<RoutingPeer> peers = new ArrayList<>(syncPeers.values());
        List<RoutingPeer> answered = new ArrayList<>();
        Map<Multihash, Budget> budgets = new HashMap<>();
        for (RoutingPeer peer : peers) {
            boolean ok = true;
            Budget budget = budgets.computeIfAbsent(peer.id().bareMultihash(), p -> new Budget());
            boolean finished = true;
            List<JdbcRoutingStore.State> found = new ArrayList<>();
            for (int kind : new int[]{Subject.SERVER, Subject.IDENTITY}) {
                try {
                    finished &= differences(peer, kind, budget, found);
                } catch (Throwable t) {
                    ok = false;
                    LOG.log(Level.INFO, "Reconciling with " + peer.id() + " failed: " + t.getMessage());
                }
            }
            // What a peer gave before failing or running out of time is still fetched, so a node far behind gains
            // on it each round.
            for (JdbcRoutingStore.State st : found)
                wanted.computeIfAbsent(st.subject, x -> new HashMap<>())
                        .computeIfAbsent(new ByteArrayWrapper(st.hash), h -> new ArrayList<>())
                        .add(peer);
            budget.startFetching();
            // A comparison cut short shows nothing about being synced. Only one that gave nothing in all its
            // time counts against the peer.
            if (ok && finished)
                answered.add(peer);
            else if (! ok || found.isEmpty())
                missed(peer);
        }
        int changed = 0, failed = 0;
        Predicate<RoutingPeer> usable = h -> ! isSuspect(h.id()) && ! budgets.get(h.id().bareMultihash()).spent()
                && rejections.has(h.id().bareMultihash());
        for (int kind : new int[]{Subject.SERVER, Subject.IDENTITY}) {
            List<Claim> claims = new ArrayList<>();
            for (Map.Entry<Subject, Map<ByteArrayWrapper, List<RoutingPeer>>> e : wanted.entrySet()) {
                if (e.getKey().kind != kind)
                    continue;
                List<Map.Entry<ByteArrayWrapper, List<RoutingPeer>>> states = new ArrayList<>(e.getValue().entrySet());
                states.sort(Comparator.comparingInt((Map.Entry<ByteArrayWrapper, List<RoutingPeer>> c) -> c.getValue().size()).reversed());
                // Every claimed state, not only the most widely held: peers that agree on a state may be one
                // operator's hiding a newer one that a single honest peer has.
                for (Map.Entry<ByteArrayWrapper, List<RoutingPeer>> state : states)
                    claims.add(new Claim(e.getKey(), state.getKey().data, state.getValue()));
            }
            // Each claim is asked of its first holder, a batch to a call, and those not delivered of the next.
            while (! claims.isEmpty()) {
                Map<Multihash, List<Claim>> byHolder = new LinkedHashMap<>();
                Map<Multihash, RoutingPeer> holders = new HashMap<>();
                for (Claim c : claims) {
                    if (Arrays.equals(replica.stateHash(c.subject), c.state))
                        continue;
                    Optional<RoutingPeer> holder = c.nextHolder(usable);
                    if (holder.isPresent()) {
                        Multihash id = holder.get().id().bareMultihash();
                        holders.put(id, holder.get());
                        byHolder.computeIfAbsent(id, h -> new ArrayList<>()).add(c);
                        continue;
                    }
                    // What a single drawn peer claims and then can't deliver may not exist, so it doesn't keep
                    // this node from being synced; anything real it alone holds comes in a later round.
                    boolean corroborated = c.holders.size() > 1 || c.holders.stream().anyMatch(h -> configured.contains(h.id().bareMultihash()));
                    if (corroborated && c.holders.stream().anyMatch(h -> ! isSuspect(h.id())))
                        failed++;
                }
                List<Claim> undelivered = new ArrayList<>();
                for (Map.Entry<Multihash, List<Claim>> e : byHolder.entrySet()) {
                    RoutingPeer holder = holders.get(e.getKey());
                    Budget budget = budgets.get(e.getKey());
                    for (int i = 0; i < e.getValue().size(); i += FETCH_BATCH) {
                        List<Claim> batch = e.getValue().subList(i, Math.min(e.getValue().size(), i + FETCH_BATCH));
                        List<Subject> subjects = batch.stream().map(c -> c.subject).distinct().collect(Collectors.toList());
                        Map<Subject, List<SignedRecord>> got = null;
                        try {
                            got = ask(holder, budget, () -> holder.recordsOf(subjects));
                        } catch (Throwable t) {
                            LOG.log(Level.FINE, "Fetching " + subjects.size() + " subjects from " + holder.id() + " failed: " + t.getMessage());
                        }
                        for (Claim c : batch) {
                            List<SignedRecord> records = got == null ? null : got.get(c.subject);
                            if (records == null) {
                                if (got != null)
                                    budget.undelivered++;
                                undelivered.add(c);
                                continue;
                            }
                            try {
                                roundBudget.set(budget);
                                if (accept(holder, c.subject, records, false) == Outcome.Added)
                                    changed++;
                                if (misrepresented(c.subject, c.state, records)) {
                                    suspect(holder.id(), "it served a state of " + c.subject + " other than the one it claimed");
                                    undelivered.add(c);
                                }
                            } catch (Throwable t) {
                                LOG.log(Level.FINE, "Taking " + c.subject + " failed: " + t.getMessage());
                                undelivered.add(c);
                            } finally {
                                roundBudget.remove();
                            }
                        }
                    }
                }
                claims = undelivered;
            }
        }
        for (Iterator<RoutingPeer> it = answered.iterator(); it.hasNext(); ) {
            RoutingPeer peer = it.next();
            if (budgets.get(peer.id().bareMultihash()).failing()) {
                it.remove();
                missed(peer);
            } else
                missed.remove(peer.id().bareMultihash());
        }
        int reached = answered.size();
        boolean reachedConfigured = answered.stream().anyMatch(p -> configured.contains(p.id().bareMultihash()));
        judge(answered);
        boolean enough = reached >= Math.min(SYNCED_PEERS, peers.size())
                && (reachedConfigured || peers.stream().noneMatch(p -> configured.contains(p.id().bareMultihash())));
        changed += read;
        if (! synced && changed == 0 && failed == 0 && enough) {
            synced = true;
            replica.store().setMeta(SNAPSHOT, SNAPSHOT_DONE);
            LOG.info("The routing log is synced, after a round with " + reached + " peers found nothing new");
        }
        return changed;
    }
}
