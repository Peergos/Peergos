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
    private final Function<Multihash, RoutingPeer> dial;
    private final Map<Multihash, RoutingPeer> syncPeers = new ConcurrentHashMap<>();
    private final Map<Subject, Map<ByteArrayWrapper, Set<Multihash>>> seen = Collections.synchronizedMap(new LRUCache<>(100_000));
    private final Map<Subject, Held> held = new ConcurrentHashMap<>();
    private final Map<Subject, SubjectLog> unproven = Collections.synchronizedMap(new LRUCache<>(UNPROVEN_CACHE));
    private final Map<Multihash, AtomicInteger> fetching = new ConcurrentHashMap<>();
    private final Set<JdbcRoutingStore.State> inFlight = ConcurrentHashMap.newKeySet();
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
    /** When this node's state of each recently changed subject changed, and the states it moved on from. */
    private final Map<Subject, Past> past = Collections.synchronizedMap(new LRUCache<>(100_000));
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
                past.put(s, new Past(now, superseded, new ByteArrayWrapper(hash)));
            }
            if (replica.isProven(s, true))
                announce(new JdbcRoutingStore.State(s, hash));
        });
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
            JdbcRoutingStore.State wanted = new JdbcRoutingStore.State(s, st.hash);
            AtomicInteger outstanding = fetching.computeIfAbsent(caller.bareMultihash(), c -> new AtomicInteger());
            if (outstanding.incrementAndGet() > MAX_FETCHING_PER_PEER || ! inFlight.add(wanted)) {
                outstanding.decrementAndGet();
                continue;
            }
            work.submit(() -> {
                try {
                    fetch(from, s, false);
                } catch (Throwable t) {
                    LOG.log(Level.FINE, "Fetching " + s + " from " + caller + " failed: " + t.getMessage());
                } finally {
                    inFlight.remove(wanted);
                    if (outstanding.decrementAndGet() == 0)
                        fetching.remove(caller.bareMultihash(), outstanding);
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
            work.submit(() -> send(peer, List.of(st)));
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
        synchronized (seen) {
            seen.computeIfAbsent(s, x -> new HashMap<>())
                    .computeIfAbsent(new ByteArrayWrapper(hash), h -> new HashSet<>())
                    .add(peer.bareMultihash());
        }
    }

    /** Most of the sync peers this node chose already hold this state, so it passed every honest node's limits;
     *  and one its operator configured, if any is a sync peer, so that peers drawn from an attacker's many address
     *  ranges can't outvote the rest and pass a flood off as catch-up.
     */
    private boolean isCatchUp(Subject s, byte[] hash) {
        Set<Multihash> holders;
        synchronized (seen) {
            holders = new HashSet<>(seen.getOrDefault(s, Map.of()).getOrDefault(new ByteArrayWrapper(hash), Set.of()));
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
        Multihash sender = from.id().bareMultihash();
        if (! rejections.has(sender))
            return Outcome.Rejected;
        Subject s = ownSubject(replica.canonical(requested), records);
        boolean catchUp = isCatchUp(s, replica.stateHash(records));
        SubjectLog log = replica.log(s);
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
                fetch(from, Subject.server(admitter.get()), fromQueue);
                if (replica.log(Subject.server(admitter.get())).inception().isEmpty()) {
                    unproven.put(s, replica.preview(s, records));
                    if (! catchUp)
                        inceptions.offer(buckets.apply(chargedTo), turn, new Offer(from, s));
                    return Outcome.Limited;
                }
            }
            if (! replica.isProven(s, inception.get(), catchUp)) {
                unproven.put(s, replica.preview(s, records));
                return Outcome.Ignored;
            }
            if (! catchUp && ! fromQueue) {
                if (pulled)
                    unproven.put(s, replica.preview(s, records));
                if (inceptions.offer(buckets.apply(chargedTo), turn, new Offer(from, s)))
                    takeSoon();
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
        held.merge(s, new Held(from, record), (old, update) ->
                ((Update) update.record).counter() >= ((Update) old.record).counter() ? update : old);
    }

    /** Takes the inceptions whose turn has come and the held updates whose token is due. Run periodically. */
    public void tick() {
        takeInceptions();
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

    private void takeInceptions() {
        for (Offer o : inceptions.take()) {
            try {
                fetch(o.from, o.subject, true);
            } catch (Throwable t) {
                LOG.log(Level.FINE, "Fetching " + o.subject + " failed: " + t.getMessage());
            }
        }
    }

    public int waitingInceptions() {
        return inceptions.waiting();
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
                if (remote.get(i).count <= ITEMS || local.count < 2L * SPLIT) {
                    res.addAll(differing(peer, kind, range, MAX_DIFFERENCES - res.size()));
                    if (res.size() >= MAX_DIFFERENCES)
                        return res;
                } else
                    todo.addAll(split(kind, range, local.count));
            }
        }
        return res;
    }

    /** A peer's states in a range that differ from ours. A peer can answer anything, so each page must move forward
     *  within the range and be no longer than asked for, and a round takes at most {@link #MAX_DIFFERENCES} from one
     *  peer; the rest wait for the next round.
     */
    private List<JdbcRoutingStore.State> differing(RoutingPeer peer, int kind, Reconciliation.Range range, int budget) {
        List<JdbcRoutingStore.State> res = new ArrayList<>();
        Map<ByteArrayWrapper, byte[]> theirs = new HashMap<>();
        byte[] after = range.after;
        while (res.size() < budget) {
            List<JdbcRoutingStore.State> page = peer.states(kind, new Reconciliation.Range(after, range.upTo), PAGE).join();
            if (page.size() > PAGE)
                throw new IllegalStateException("Peer " + peer.id() + " sent more states than asked for");
            for (JdbcRoutingStore.State st : page) {
                byte[] id = st.subject.idBytes();
                if (st.subject.kind != kind || order(id, after) <= 0 || range.upTo.map(u -> order(id, u) > 0).orElse(false))
                    throw new IllegalStateException("Peer " + peer.id() + " sent states out of order or range");
                after = id;
                saw(st.subject, st.hash, peer.id());
                theirs.put(new ByteArrayWrapper(id), st.hash);
                if (! Arrays.equals(replica.stateHash(st.subject), st.hash))
                    res.add(st);
            }
            if (page.size() < PAGE) {
                tally(peer, kind, range, theirs);
                return res;
            }
        }
        return res;
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
        byte[] after = range.after;
        int scanned = 0;
        boolean complete = false;
        while (scanned < LACK_SCAN) {
            List<JdbcRoutingStore.State> page = replica.store().states(kind, after, range.upTo, PAGE);
            for (JdbcRoutingStore.State st : page) {
                ByteArrayWrapper id = new ByteArrayWrapper(st.subject.idBytes());
                ours.add(id);
                byte[] held = theirs.get(id);
                Past p = past.get(st.subject);
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
            after = page.get(page.size() - 1).subject.idBytes();
        }
        if (complete)
            for (ByteArrayWrapper id : theirs.keySet())
                if (! ours.contains(id))
                    t.extra++;
    }

    public static final int MAX_DIFFERENCES = 100_000;

    /** The store's order of subject ids: bytes unsigned, and a prefix first. */
    static int order(byte[] a, byte[] b) {
        int c = ArrayOps.compare(a, b);
        return c != 0 ? c : Integer.compare(a.length, b.length);
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
        reannounce();
        tallies.clear();
        List<RoutingPeer> peers = new ArrayList<>(syncPeers.values());
        int reached = 0;
        boolean reachedConfigured = false;
        List<RoutingPeer> answered = new ArrayList<>();
        for (RoutingPeer peer : peers) {
            boolean ok = true;
            for (int kind : new int[]{Subject.SERVER, Subject.IDENTITY}) {
                try {
                    for (JdbcRoutingStore.State st : differences(peer, kind))
                        wanted.computeIfAbsent(st.subject, x -> new HashMap<>())
                                .computeIfAbsent(new ByteArrayWrapper(st.hash), h -> new ArrayList<>())
                                .add(peer);
                } catch (Throwable t) {
                    ok = false;
                    LOG.log(Level.INFO, "Reconciling with " + peer.id() + " failed: " + t.getMessage());
                }
            }
            Multihash pid = peer.id().bareMultihash();
            if (! ok && missed.merge(pid, 1, Integer::sum) >= 2) {
                missed.remove(pid);
                unreachable.put(pid, clock.getAsLong() + UNREACHABLE_MILLIS);
            }
            if (ok) {
                missed.remove(pid);
                reached++;
                answered.add(peer);
                reachedConfigured |= configured.contains(peer.id().bareMultihash());
            }
        }
        int changed = 0, failed = 0;
        for (Map.Entry<Subject, Map<ByteArrayWrapper, List<RoutingPeer>>> e : wanted.entrySet()) {
            Subject s = e.getKey();
            List<Map.Entry<ByteArrayWrapper, List<RoutingPeer>>> claims = new ArrayList<>(e.getValue().entrySet());
            claims.sort(Comparator.comparingInt((Map.Entry<ByteArrayWrapper, List<RoutingPeer>> c) -> c.getValue().size()).reversed());
            // Every claimed state, not only the most widely held: peers that agree on a state may be one operator's
            // hiding a newer one that a single honest peer has.
            for (Map.Entry<ByteArrayWrapper, List<RoutingPeer>> claim : claims) {
                byte[] claimed = claim.getKey().data;
                if (Arrays.equals(replica.stateHash(s), claimed))
                    continue;
                List<RoutingPeer> holders = claim.getValue();
                for (RoutingPeer holder : holders) {
                    if (isSuspect(holder.id()))
                        continue;
                    try {
                        List<SignedRecord> records = holder.records(s).join();
                        if (accept(holder, s, records, false) == Outcome.Added)
                            changed++;
                        if (misrepresented(s, claimed, records)) {
                            suspect(holder.id(), "it served a state of " + s + " other than the one it claimed");
                            continue;
                        }
                        break;
                    } catch (Throwable t) {
                        if (holder == holders.get(holders.size() - 1))
                            failed++;
                        LOG.log(Level.FINE, "Fetching " + s + " failed: " + t.getMessage());
                    }
                }
            }
        }
        judge(answered);
        boolean enough = reached >= Math.min(SYNCED_PEERS, peers.size())
                && (reachedConfigured || peers.stream().noneMatch(p -> configured.contains(p.id().bareMultihash())));
        if (! synced && changed == 0 && failed == 0 && enough) {
            synced = true;
            LOG.info("The routing log is synced, after a round with " + reached + " peers found nothing new");
        }
        return changed;
    }
}
