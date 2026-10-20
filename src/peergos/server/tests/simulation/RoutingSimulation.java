package peergos.server.tests.simulation;

import peergos.server.*;
import peergos.server.routing.*;
import peergos.server.sql.*;
import peergos.shared.*;
import peergos.shared.crypto.*;
import peergos.shared.io.ipfs.*;
import peergos.shared.routing.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.stream.*;

/** Many routing nodes in one process, running the real replication, peer choice and rate limits, with simulated time
 *  and a deterministic work queue, so floods and partitions can be measured rather than argued about.
 */
public class RoutingSimulation {
    static final Crypto crypto = Main.initCrypto();
    static final byte[] GOOD_PROOF = {1, 2, 3};
    static final Work TEST_WORK = (subject, proof) -> CompletableFuture.completedFuture(Arrays.equals(proof, GOOD_PROOF));
    static final long MINUTE = 60_000, HOUR = 60 * MINUTE, DAY = 24 * HOUR;

    static SigningKeyPair key() {
        return SigningKeyPair.random(crypto.random, crypto.signer);
    }

    /** Runs submitted tasks only when drained, in order, on the caller's thread. */
    static class Queue extends AbstractExecutorService {
        final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        long run;

        public synchronized void execute(Runnable r) {
            tasks.add(r);
        }

        synchronized Runnable next() {
            return tasks.poll();
        }

        synchronized int size() {
            return tasks.size();
        }

        void drain() {
            Runnable r;
            while ((r = next()) != null) {
                run++;
                try {
                    r.run();
                } catch (Throwable t) {}
            }
        }

        public void shutdown() {}

        public List<Runnable> shutdownNow() {
            return List.of();
        }

        public boolean isShutdown() {
            return false;
        }

        public boolean isTerminated() {
            return false;
        }

        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }
    }

    static final ExecutorService DIRECT = new AbstractExecutorService() {
        public void execute(Runnable r) {
            r.run();
        }

        public void shutdown() {}

        public List<Runnable> shutdownNow() {
            return List.of();
        }

        public boolean isShutdown() {
            return false;
        }

        public boolean isTerminated() {
            return false;
        }

        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }
    };

    enum Behaviour {
        HONEST,
        /** Serves only what it held when it started withholding, and takes in nothing from others. */
        WITHHOLD,
        OFFLINE,
        /** Syncs like an honest node, but answers for its frozen subjects as if it had never seen anything newer:
         *  consistently, so it looks like a node that is behind on them. */
        CENSOR,
        /** Like a censor, but claims a made-up state for its frozen subjects, and serves the old one. */
        LIAR;

        boolean syncs() {
            return this == HONEST || this == CENSOR || this == LIAR;
        }
    }

    static class Net {
        final AtomicLong clock = new AtomicLong(1_000_000_000_000L);
        final Queue work = new Queue();
        final Map<Multihash, SimNode> nodes = new LinkedHashMap<>();
        final Random random;
        final int inceptionsPerDay;
        int addressCounter = 1;
        /** Announcements lost, so only reconciliation carries records. */
        boolean dropAnnouncements;
        /** Nodes on different sides can't reach each other. */
        final Map<Multihash, Integer> side = new HashMap<>();

        Net(long seed, int inceptionsPerDay) {
            this.random = new Random(seed);
            this.inceptionsPerDay = inceptionsPerDay;
        }

        /** A server whose direct address is in the given /16, or none, and which runs a full or light replica. */
        SimNode node(Optional<Integer> bucket, ServerAddresses.Mode mode, Behaviour b) {
            Optional<String> addr = bucket.map(i -> "/ip4/" + (20 + i / 250) + "." + (i % 250) + "." + (addressCounter / 250) + "."
                    + (1 + addressCounter++ % 250) + "/tcp/4001");
            SimNode n = new SimNode(this, addr, mode, b);
            nodes.put(n.id, n);
            return n;
        }

        List<SimNode> full() {
            return nodes.values().stream().filter(n -> n.mode != ServerAddresses.Mode.Light).collect(Collectors.toList());
        }

        List<SimNode> honestFull() {
            return full().stream().filter(n -> n.behaviour == Behaviour.HONEST).collect(Collectors.toList());
        }

        /** Advances the clock, then every full node takes what its limits allow and reconciles. */
        void step(long millis) {
            clock.addAndGet(millis);
            for (SimNode n : full()) {
                if (! n.behaviour.syncs())
                    continue;
                n.routing.tick();
                work.drain();
                n.routing.syncRound();
                work.drain();
            }
        }

        /** Time passing between reconciliation rounds: queued inceptions and held updates are taken, nothing else. */
        void tick(long millis) {
            clock.addAndGet(millis);
            for (SimNode n : full())
                if (n.behaviour.syncs()) {
                    n.routing.tick();
                    work.drain();
                }
        }

        void choosePeers() {
            for (SimNode n : nodes.values())
                if (n.behaviour.syncs() && n.peers != null)
                    n.peers.refresh();
            work.drain();
        }
    }

    static class SimNode {
        final Net net;
        final SigningKeyPair key = key();
        final ServerInception inception = ServerInception.build(key, key().publicSigningKey, crypto.hasher).join().withProof(GOOD_PROOF);
        final Multihash id = inception.peerId().bareMultihash();
        final Subject subject = Subject.server(id);
        final Optional<String> addr;
        final ServerAddresses.Mode mode;
        Behaviour behaviour;
        final RoutingReplica replica;
        final RoutingNode routing;
        final BucketVerifier buckets;
        final List<RoutingPeer> configured = new ArrayList<>();
        SyncPeers peers;
        LightLookup light;
        final AtomicLong recordRequests = new AtomicLong(), recordBytes = new AtomicLong();
        /** What a censor or liar answers for a subject: the records it had when it froze it, and the state it claims. */
        final Map<Subject, List<SignedRecord>> frozen = new HashMap<>();
        final Map<Subject, byte[]> claimed = new HashMap<>();

        SimNode(Net net, Optional<String> addr, ServerAddresses.Mode mode, Behaviour behaviour) {
            this.net = net;
            this.addr = addr;
            this.mode = mode;
            this.behaviour = behaviour;
            replica = new RoutingReplica(new JdbcRoutingStore(Builder.buildEphemeralSqlite(), new SqliteCommands()),
                    new RecordValidator(crypto.hasher, TEST_WORK, TEST_WORK), TEST_WORK, TEST_WORK);
            if (mode == ServerAddresses.Mode.Compact)
                replica.compact(id);
            buckets = new BucketVerifier(RoutingLookup.full(replica), (peer, addrs) -> {
                SimNode target = net.nodes.get(peer.bareMultihash());
                if (target == null || target.behaviour == Behaviour.OFFLINE || target.addr.isEmpty())
                    return Futures.of(Optional.empty());
                return Futures.of(BucketVerifier.ip(target.addr.get()));
            }, net.clock::get, DIRECT);
            routing = new RoutingNode(replica, crypto.hasher, net.clock::get, net.inceptionsPerDay, buckets::bucket,
                    target -> new SimPeer(id, target, net), net.work);
            replica.add(subject, inception);
            replica.add(subject, ServerAddresses.build(key, key.publicSigningKey, Optional.empty(),
                    addr.map(a -> ServerAddressPublisher.directAddresses(List.of(a))).orElse(List.of()),
                    List.of(), mode, false, 1).join());
        }

        SimNode configure(SimNode... seeds) {
            for (SimNode s : seeds)
                configured.add(new SimPeer(id, s.id, net));
            boolean isLight = mode == ServerAddresses.Mode.Light;
            peers = new SyncPeers(routing, buckets, configured, id, isLight, net.random);
            if (isLight) {
                light = new LightLookup(routing, id, configured, net.clock::get, net.random, DIRECT);
                light.sampleFrom(peers::drawn);
            } else {
                routing.setSelf(subject);
                configured.forEach(routing::addSyncPeer);
                routing.setConfigured(configured.stream().map(RoutingPeer::id).collect(Collectors.toList()));
            }
            return this;
        }

        /** An identity whose home server this is, admitted by it. */
        Subject admit(SigningKeyPair identity) {
            IdentityInception inc = IdentityInception.build(identity, List.of(id), key().publicSigningKey, crypto.hasher).join()
                    .withAdmission(Admission.build(identity.publicSigningKey, GOOD_PROOF, key.secretSigningKey).join());
            Subject s = Subject.identity(inc.identityHash());
            replica.add(s, inc);
            return s;
        }

        boolean holds(Subject s) {
            return replica.log(s).inception().isPresent();
        }

        /** From now on answers for this subject with what it holds now. */
        void freeze(Subject s) {
            frozen.put(s, routing.records(s));
            byte[] claim = replica.stateHash(frozen.get(s));
            if (behaviour == Behaviour.LIAR) {
                claim = new byte[claim.length];
                net.random.nextBytes(claim);
            }
            claimed.put(s, claim);
        }

        JdbcRoutingStore.State answer(JdbcRoutingStore.State st) {
            byte[] claim = claimed.get(st.subject);
            return claim == null ? st : new JdbcRoutingStore.State(st.subject, claim);
        }

        boolean frozenIn(int kind, Reconciliation.Range r) {
            return frozen.keySet().stream().anyMatch(s -> s.kind == kind && r.contains(s.idBytes()));
        }

        Reconciliation.Summary summary(int kind, Reconciliation.Range r) {
            Reconciliation.Accumulator acc = new Reconciliation.Accumulator(crypto.hasher);
            byte[] after = r.after;
            while (true) {
                List<JdbcRoutingStore.State> page = replica.store().states(kind, after, r.upTo, 1000);
                page.forEach(st -> acc.add(answer(st)));
                if (page.size() < 1000)
                    return acc.summary();
                after = page.get(page.size() - 1).subject.idBytes();
            }
        }
    }

    /** One node calling another, as the authenticated transport would, or failing if the target is offline. */
    static class SimPeer implements RoutingPeer {
        final Multihash caller, target;
        final Net net;

        SimPeer(Multihash caller, Multihash target, Net net) {
            this.caller = caller;
            this.target = target.bareMultihash();
            this.net = net;
        }

        SimNode node() {
            SimNode n = net.nodes.get(target);
            if (n == null || n.behaviour == Behaviour.OFFLINE)
                throw new IllegalStateException("Connection refused");
            Integer from = net.side.get(caller), to = net.side.get(target);
            if (from != null && to != null && ! from.equals(to))
                throw new IllegalStateException("No route to host");
            return n;
        }

        public Multihash id() {
            return target;
        }

        private <T> CompletableFuture<T> call(Callable<T> c) {
            try {
                return Futures.of(c.call());
            } catch (Exception e) {
                return Futures.errored(e);
            }
        }

        public CompletableFuture<Boolean> announce(List<JdbcRoutingStore.State> changed) {
            return call(() -> {
                SimNode n = node();
                SimNode from = net.nodes.get(caller);
                List<JdbcRoutingStore.State> sent = changed.stream()
                        .filter(st -> from == null || ! from.frozen.containsKey(st.subject))
                        .limit(RoutingHandler.MAX_ITEMS)
                        .collect(Collectors.toList());
                if (n.behaviour.syncs() && ! sent.isEmpty() && ! net.dropAnnouncements)
                    n.routing.announced(caller, sent);
                return true;
            });
        }

        public CompletableFuture<List<SignedRecord>> records(Subject s) {
            return call(() -> {
                SimNode n = node();
                List<SignedRecord> res = n.frozen.containsKey(s) ? n.frozen.get(s) : n.routing.records(s);
                n.recordRequests.incrementAndGet();
                n.recordBytes.addAndGet(res.stream().mapToLong(r -> r.serialize().length).sum());
                return res;
            });
        }

        public CompletableFuture<List<Reconciliation.Summary>> summaries(int kind, List<Reconciliation.Range> ranges) {
            return call(() -> {
                SimNode n = node();
                if (n.frozen.isEmpty())
                    return n.routing.summaries(kind, ranges);
                return ranges.stream()
                        .map(r -> n.frozenIn(kind, r) ? n.summary(kind, r) : n.routing.summaries(kind, List.of(r)).get(0))
                        .collect(Collectors.toList());
            });
        }

        public CompletableFuture<List<JdbcRoutingStore.State>> states(int kind, Reconciliation.Range range, int limit) {
            return call(() -> {
                SimNode n = node();
                return n.routing.states(kind, range, limit).stream().map(n::answer).collect(Collectors.toList());
            });
        }

        public CompletableFuture<Outcome> offer(Subject s, List<SignedRecord> records) {
            return call(() -> {
                SimNode n = node();
                return n.behaviour.syncs() ? n.routing.offered(caller, s, records) : Outcome.Ignored;
            });
        }
    }
}
