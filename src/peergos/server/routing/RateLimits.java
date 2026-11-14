package peergos.server.routing;

import peergos.shared.io.ipfs.*;
import peergos.shared.routing.*;

import java.util.*;
import java.util.function.*;

/** The limits a full node applies to what it accepts, on its own clock (remove-pki §4.5). They protect this node's
 *  bandwidth and storage, so they need no agreement between nodes; nothing is dropped, only delayed, so replicas
 *  lag rather than diverge.
 */
public class RateLimits {
    public static final long DAY = 24 * 3600 * 1000L;
    public static final long MONTH = 30 * DAY;
    public static final long QUARTER = 91 * DAY;

    /** Burst and refill of each update type, counted per identity or server, not per key. */
    public static final Map<RecordType, Rate> UPDATES = Map.of(
            RecordType.IDENTITY_ROTATION, new Rate(2, QUARTER),
            RecordType.SERVER_ROTATION, new Rate(2, QUARTER),
            RecordType.IDENTITY_MOVE, new Rate(3, MONTH),
            RecordType.SERVER_ADDRESSES, new Rate(3, MONTH));

    /** What a duplicate inception kept as evidence may be replaced by, per subject: whoever holds a stolen first key
     *  can sign any number, each with a lower hash than the last, and each would otherwise replace it everywhere.
     */
    public static final Rate EVIDENCE = new Rate(2, QUARTER);
    private static final int EVIDENCE_CODE = 100;

    public static final int DEFAULT_INCEPTIONS_PER_DAY = 50;
    /** An address range's rate is at least the configured one, and grows with the log: each day a range may add
     *  this fraction of the identities a node holds. A fixed number that suits a large network would let one range
     *  multiply a small one in a day.
     */
    public static final int LOG_FRACTION_PER_DAY = 200;
    /** New identities taken from every range together are held to this many ranges' worth, with the ranges taking
     *  turns. So the whole log grows by a bounded fraction a day however many ranges a flood comes from, and a
     *  flood delays an honest range in proportion to the ranges it holds.
     */
    public static final int NETWORK_RANGES = 4;

    static int inceptionsPerDay(int configured, long identities, int ranges) {
        return (int) Math.min(DAY, ranges * Math.max((long) configured, identities / LOG_FRACTION_PER_DAY));
    }

    public static final class Rate {
        public final int burst;
        public final long refillMillis;

        public Rate(int burst, long refillMillis) {
            this.burst = burst;
            this.refillMillis = refillMillis;
        }
    }

    /** Token buckets by key. A full bucket needs no entry, so state is bounded by recent use, not by the number of
     *  keys: every bucket starts full when first seen.
     */
    public static final class TokenBuckets<K> {
        private volatile Rate rate;
        private final LongSupplier clock;
        private final Map<K, long[]> buckets = new HashMap<>();

        public TokenBuckets(Rate rate, LongSupplier clock) {
            this.rate = rate;
            this.clock = clock;
        }

        /** Takes a token if one is available. */
        public synchronized boolean tryTake(K key) {
            return tryTake(key, 1);
        }

        public synchronized boolean tryTake(K key, long tokens) {
            long now = clock.getAsLong();
            long[] b = current(key, now);
            if (b[0] < tokens)
                return false;
            b[0] -= tokens;
            buckets.put(key, b);
            return true;
        }

        /** Buckets in use keep their tokens, up to the new burst, and refill at the new rate. */
        public synchronized void setRate(Rate rate) {
            this.rate = rate;
        }

        /** Takes a token only if more than a floor are left. */
        public synchronized boolean tryTakeAbove(K key, long floor) {
            long[] b = current(key, clock.getAsLong());
            if (b[0] <= floor)
                return false;
            b[0]--;
            buckets.put(key, b);
            return true;
        }

        public int burst() {
            return rate.burst;
        }

        public synchronized boolean has(K key) {
            return current(key, clock.getAsLong())[0] > 0;
        }

        /** When the next token is due, or now if one is available. */
        public synchronized long nextToken(K key) {
            long now = clock.getAsLong();
            long[] b = current(key, now);
            return b[0] > 0 ? now : b[1] + rate.refillMillis;
        }

        /** [tokens, time the last token was credited] */
        private long[] current(K key, long now) {
            long[] b = buckets.get(key);
            if (b == null)
                return new long[]{rate.burst, now};
            long refills = (now - b[1]) / rate.refillMillis;
            if (refills <= 0)
                return b;
            long tokens = Math.min(rate.burst, b[0] + refills);
            if (tokens == rate.burst) {
                buckets.remove(key);
                return new long[]{rate.burst, now};
            }
            return new long[]{tokens, b[1] + refills * rate.refillMillis};
        }

        public synchronized long tokens(K key) {
            return current(key, clock.getAsLong())[0];
        }

        public synchronized int size() {
            return buckets.size();
        }

        public synchronized void prune() {
            long now = clock.getAsLong();
            for (K k : new ArrayList<>(buckets.keySet()))
                current(k, now);
        }
    }

    /** Per identity or server: rotations, moves and address updates. A bucket that isn't full is kept in the routing
     *  store, beside the subject it limits: there is one for every subject updated in the last few months, which
     *  at scale is more than fits in memory, and a restart must not hand every subject a full one.
     */
    public static final class Updates {
        private final JdbcRoutingStore store;
        private final LongSupplier clock;

        public Updates(JdbcRoutingStore store, LongSupplier clock) {
            this.store = store;
            this.clock = clock;
        }

        public boolean isLimited(RecordType type) {
            return UPDATES.containsKey(type);
        }

        /** [tokens, time the last token was credited] */
        private long[] current(Subject s, RecordType type, long now) {
            return current(s, UPDATES.get(type), type.code, now);
        }

        private long[] current(Subject s, Rate rate, int code, long now) {
            long[] b = store.tokens(s, code).orElse(null);
            if (b == null)
                return new long[]{rate.burst, now};
            long refills = (now - b[1]) / rate.refillMillis;
            if (refills <= 0)
                return b;
            long tokens = Math.min(rate.burst, b[0] + refills);
            return tokens == rate.burst ? new long[]{rate.burst, now} : new long[]{tokens, b[1] + refills * rate.refillMillis};
        }

        /** Called with the subject's lock held, as records are added, so a bucket is read and written by one thread. */
        public boolean tryTake(Subject s, RecordType type) {
            if (! isLimited(type))
                return true;
            long[] b = current(s, type, clock.getAsLong());
            if (b[0] < 1)
                return false;
            store.setTokens(s, type.code, b[0] - 1, b[1]);
            return true;
        }

        /** For a duplicate inception replacing the one kept as evidence. Called with the subject's lock held. */
        public boolean tryTakeEvidence(Subject s) {
            long[] b = current(s, EVIDENCE, EVIDENCE_CODE, clock.getAsLong());
            if (b[0] < 1)
                return false;
            store.setTokens(s, EVIDENCE_CODE, b[0] - 1, b[1]);
            return true;
        }

        public long nextToken(Subject s, RecordType type) {
            long now = clock.getAsLong();
            long[] b = current(s, type, now);
            return b[0] > 0 ? now : b[1] + UPDATES.get(type).refillMillis;
        }

        /** Drops the buckets that have filled: one delete per type, by when each was last credited. */
        public void prune() {
            long now = clock.getAsLong();
            UPDATES.forEach((type, rate) -> store.removeTokens(type.code, now - rate.burst * rate.refillMillis));
            store.removeTokens(EVIDENCE_CODE, now - EVIDENCE.burst * EVIDENCE.refillMillis);
        }
    }

    /** New inceptions, per address bucket of the server that admitted them, and for all buckets together. Offers wait
     *  with whoever made them; this only decides which to take next. Buckets take turns at the rate for all of
     *  them, and within a bucket admitting servers take turns, so an honest home server's few signups get a turn
     *  however many the servers beside it offer, and an honest range however many ranges a flood comes from.
     *
     *  What waits is bounded the same way: when a bucket's queue is full, room is made by the server with the most
     *  waiting, and when all of it is, by the bucket with the most. So a flood fills its own share and no more.
     */
    public static final class Inceptions<O> {
        private static final String ALL = "";
        private final TokenBuckets<AddressBucket> rate;
        private final TokenBuckets<String> network;
        private final int configuredPerDay;
        private final LinkedHashMap<AddressBucket, LinkedHashMap<Multihash, ArrayDeque<O>>> offers = new LinkedHashMap<>();
        private final int maxOffersPerServer;
        private final LongSupplier clock;
        private final Map<AddressBucket, Long> busyUntil = new HashMap<>();
        private final Map<AddressBucket, Integer> waitingIn = new HashMap<>();
        private final Set<O> all = new HashSet<>();
        private int maxPerBucket;
        private int waiting;

        public Inceptions(int perDay, int maxOffersPerServer, LongSupplier clock) {
            this.configuredPerDay = perDay;
            this.rate = new TokenBuckets<>(rate(perDay(0)), clock);
            this.network = new TokenBuckets<>(rate(networkPerDay(0)), clock);
            this.maxOffersPerServer = maxOffersPerServer;
            this.maxPerBucket = Math.max(perDay, maxOffersPerServer);
            this.clock = clock;
        }

        private static Rate rate(int perDay) {
            return new Rate(perDay, DAY / perDay);
        }

        /** Sets the rates for a log holding this many identities. */
        public synchronized void resize(long identities) {
            rate.setRate(rate(perDay(identities)));
            network.setRate(rate(networkPerDay(identities)));
            maxPerBucket = Math.max(perDay(identities), maxOffersPerServer);
        }

        public int perDay(long identities) {
            return inceptionsPerDay(configuredPerDay, identities, 1);
        }

        public int networkPerDay(long identities) {
            return inceptionsPerDay(configuredPerDay, identities, NETWORK_RANGES);
        }

        /** Whether this bucket has had inceptions waiting for a rate within the last day, so that honest nodes
         *  may hold different ones of them for a while.
         */
        public synchronized boolean congested(AddressBucket bucket) {
            Long until = busyUntil.get(bucket);
            if (until == null)
                return false;
            if (until < clock.getAsLong()) {
                busyUntil.remove(bucket);
                return false;
            }
            return true;
        }

        /** Queues an offer; false if it is waiting already or there is no room for it. Nothing is lost by that, or
         *  by making room: the sender keeps what it offered and offers it again.
         */
        public synchronized boolean offer(AddressBucket bucket, Multihash admittingServer, O offer) {
            if (all.contains(offer))
                return false;
            Multihash server = admittingServer.bareMultihash();
            LinkedHashMap<Multihash, ArrayDeque<O>> servers = offers.get(bucket);
            ArrayDeque<O> queue = servers == null ? null : servers.get(server);
            int mine = queue == null ? 0 : queue.size();
            if (mine >= maxOffersPerServer)
                return false;
            if (waitingIn.getOrDefault(bucket, 0) >= maxPerBucket && ! makeRoom(bucket, mine))
                return false;
            if (waiting >= MAX_WAITING) {
                AddressBucket fullest = Collections.max(waitingIn.entrySet(), Map.Entry.comparingByValue()).getKey();
                if (waitingIn.get(fullest) <= waitingIn.getOrDefault(bucket, 0) + 1 || ! makeRoom(fullest, -1))
                    return false;
            }
            servers = offers.computeIfAbsent(bucket, b -> new LinkedHashMap<>());
            servers.computeIfAbsent(server, x -> new ArrayDeque<>()).add(offer);
            all.add(offer);
            waitingIn.merge(bucket, 1, Integer::sum);
            waiting++;
            return true;
        }

        /** Drops the newest offer of the server with the most waiting in a bucket, if that is more than the server
         *  now offering would have.
         */
        private boolean makeRoom(AddressBucket bucket, int offering) {
            LinkedHashMap<Multihash, ArrayDeque<O>> servers = offers.get(bucket);
            if (servers == null || servers.isEmpty())
                return false;
            Map.Entry<Multihash, ArrayDeque<O>> longest = Collections.max(servers.entrySet(),
                    Comparator.comparingInt(e -> e.getValue().size()));
            if (longest.getValue().size() <= offering + 1)
                return false;
            all.remove(longest.getValue().pollLast());
            if (longest.getValue().isEmpty())
                servers.remove(longest.getKey());
            if (servers.isEmpty())
                offers.remove(bucket);
            waitingIn.computeIfPresent(bucket, (b, n) -> n > 1 ? n - 1 : null);
            waiting--;
            return true;
        }

        /** Takes tokens for an inception that needn't then queue: from a bucket with nothing waiting and more than
         *  half its tokens left, while more than half of those for all buckets are left too. Below that they go
         *  by turns, so a burst from one server or one range leaves some for the others.
         */
        public synchronized boolean tryTakeNow(AddressBucket bucket) {
            if (waitingIn.getOrDefault(bucket, 0) > 0
                    || rate.tokens(bucket) <= rate.burst() / 2
                    || network.tokens(ALL) <= network.burst() / 2)
                return false;
            return network.tryTake(ALL) && rate.tryTake(bucket);
        }

        public synchronized boolean isWaiting(O offer) {
            return all.contains(offer);
        }

        /** Across every bucket, to bound memory. */
        public static final int MAX_WAITING = 200_000;

        /** The offers that may be taken now, each charged to its bucket and to the rate for all of them. Buckets
         *  take turns, one offer each, and a bucket served goes to the back.
         */
        public synchronized List<O> take() {
            List<O> res = new ArrayList<>();
            long busy = clock.getAsLong() + DAY;
            boolean progress = true;
            while (progress) {
                progress = false;
                for (AddressBucket bucket : new ArrayList<>(offers.keySet())) {
                    LinkedHashMap<Multihash, ArrayDeque<O>> servers = offers.get(bucket);
                    if (! rate.has(bucket)) {
                        busyUntil.put(bucket, busy);
                        continue;
                    }
                    if (! network.tryTake(ALL)) {
                        for (AddressBucket left : offers.keySet())
                            busyUntil.put(left, busy);
                        return res;
                    }
                    rate.tryTake(bucket);
                    Multihash next = servers.keySet().iterator().next();
                    ArrayDeque<O> queue = servers.remove(next);
                    O taken = queue.poll();
                    res.add(taken);
                    all.remove(taken);
                    waitingIn.computeIfPresent(bucket, (b, n) -> n > 1 ? n - 1 : null);
                    waiting--;
                    if (! queue.isEmpty())
                        servers.put(next, queue);
                    offers.remove(bucket);
                    if (! servers.isEmpty())
                        offers.put(bucket, servers);
                    progress = true;
                }
            }
            return res;
        }

        public synchronized int waiting() {
            return waiting;
        }
    }
}
