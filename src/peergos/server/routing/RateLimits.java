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

    public static final int DEFAULT_INCEPTIONS_PER_DAY = 10_000;

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
        private final Rate rate;
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

        public synchronized int size() {
            return buckets.size();
        }

        public synchronized void prune() {
            long now = clock.getAsLong();
            for (K k : new ArrayList<>(buckets.keySet()))
                current(k, now);
        }
    }

    /** Per identity or server: rotations, moves and address updates. */
    public static final class Updates {
        private final Map<RecordType, TokenBuckets<Subject>> buckets = new EnumMap<>(RecordType.class);

        public Updates(LongSupplier clock) {
            for (Map.Entry<RecordType, Rate> e : UPDATES.entrySet())
                buckets.put(e.getKey(), new TokenBuckets<>(e.getValue(), clock));
        }

        public boolean isLimited(RecordType type) {
            return buckets.containsKey(type);
        }

        public boolean tryTake(Subject s, RecordType type) {
            TokenBuckets<Subject> b = buckets.get(type);
            return b == null || b.tryTake(s);
        }

        public long nextToken(Subject s, RecordType type) {
            return buckets.get(type).nextToken(s);
        }

        public void prune() {
            buckets.values().forEach(TokenBuckets::prune);
        }
    }

    /** New inceptions, per address bucket of the server that admitted them. Offers wait with whoever made them;
     *  this only decides which to take next: within a bucket, round-robin by admitting server, so an honest home
     *  server's few signups get a turn however many the servers beside it offer.
     */
    public static final class Inceptions<O> {
        private final TokenBuckets<AddressBucket> rate;
        private final Map<AddressBucket, LinkedHashMap<Multihash, ArrayDeque<O>>> offers = new HashMap<>();
        private final int maxOffersPerServer;
        private final LongSupplier clock;
        private final Map<AddressBucket, Long> busyUntil = new HashMap<>();
        private int waiting;

        public Inceptions(int perDay, int maxOffersPerServer, LongSupplier clock) {
            this.rate = new TokenBuckets<>(new Rate(perDay, DAY / perDay), clock);
            this.maxOffersPerServer = maxOffersPerServer;
            this.clock = clock;
        }

        /** Whether this bucket has had inceptions waiting for its rate within the last day, so that honest nodes
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

        /** Queues an offer; false if the admitting server already has as many waiting as are kept. */
        public synchronized boolean offer(AddressBucket bucket, Multihash admittingServer, O offer) {
            if (waiting() >= MAX_WAITING)
                return false;
            ArrayDeque<O> queue = offers.computeIfAbsent(bucket, b -> new LinkedHashMap<>())
                    .computeIfAbsent(admittingServer.bareMultihash(), s -> new ArrayDeque<>());
            if (queue.size() >= maxOffersPerServer || queue.contains(offer))
                return false;
            queue.add(offer);
            waiting++;
            return true;
        }

        /** Across every bucket: an inception naming an admitting server no one holds yet waits under that name, and
         *  names cost nothing, so the total is bounded too. Nothing is lost: the sender keeps it and offers it again.
         */
        public static final int MAX_WAITING = 100_000;

        /** The offers that may be taken now, each charged to its bucket. */
        public synchronized List<O> take() {
            List<O> res = new ArrayList<>();
            for (Iterator<Map.Entry<AddressBucket, LinkedHashMap<Multihash, ArrayDeque<O>>>> it = offers.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<AddressBucket, LinkedHashMap<Multihash, ArrayDeque<O>>> e = it.next();
                LinkedHashMap<Multihash, ArrayDeque<O>> servers = e.getValue();
                while (! servers.isEmpty() && rate.tryTake(e.getKey())) {
                    Multihash next = servers.keySet().iterator().next();
                    ArrayDeque<O> queue = servers.remove(next);
                    res.add(queue.poll());
                    waiting--;
                    if (! queue.isEmpty())
                        servers.put(next, queue);
                }
                if (servers.isEmpty())
                    it.remove();
                else
                    busyUntil.put(e.getKey(), clock.getAsLong() + DAY);
            }
            return res;
        }

        public synchronized int waiting() {
            return waiting;
        }
    }
}
