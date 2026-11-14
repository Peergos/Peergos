package peergos.server.tests;

import org.junit.*;
import peergos.server.*;
import peergos.server.routing.*;
import peergos.server.sql.*;
import peergos.shared.*;
import peergos.shared.crypto.*;
import peergos.shared.io.ipfs.*;
import peergos.shared.routing.*;

import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class ReplicationTests {
    private static final Crypto crypto = Main.initCrypto();
    private static final byte[] GOOD_PROOF = {1, 2, 3};
    private static final Work TEST_WORK = (subject, proof) -> CompletableFuture.completedFuture(Arrays.equals(proof, GOOD_PROOF));
    private static final ExecutorService work = Executors.newFixedThreadPool(4);

    private static SigningKeyPair key() {
        return SigningKeyPair.random(crypto.random, crypto.signer);
    }

    /** Nodes reach each other directly, each call carrying the caller's id as an authenticated transport would. */
    private static class Network {
        final Map<Multihash, Node> nodes = new ConcurrentHashMap<>();
        final AtomicLong clock = new AtomicLong(1_000_000_000L);

        Node node(int inceptionsPerDay) {
            return node(inceptionsPerDay, ServerKeys.peerId(key().publicSigningKey));
        }

        Node node(int inceptionsPerDay, Multihash id) {
            Node n = new Node(this, inceptionsPerDay, id);
            nodes.put(n.id, n);
            return n;
        }
    }

    private static class Node {
        final Multihash id;
        final RoutingReplica replica;
        final RoutingNode routing;

        Node(Network net, int inceptionsPerDay, Multihash id) {
            this.id = id.bareMultihash();
            JdbcRoutingStore store = new JdbcRoutingStore(Builder.buildEphemeralSqlite(), new SqliteCommands());
            replica = new RoutingReplica(store, new RecordValidator(crypto.hasher, TEST_WORK, TEST_WORK), TEST_WORK, TEST_WORK);
            routing = new RoutingNode(replica, crypto.hasher, net.clock::get, inceptionsPerDay,
                    server -> AddressBucket.UNVERIFIED, target -> new LocalPeer(id, net.nodes.get(target.bareMultihash())), work);
        }

        void syncWith(Node other) {
            routing.addSyncPeer(new LocalPeer(id, other));
        }
    }

    private static class LocalPeer implements RoutingPeer {
        final Multihash caller;
        final Node target;

        LocalPeer(Multihash caller, Node target) {
            this.caller = caller;
            this.target = target;
        }

        public Multihash id() {
            return target.id;
        }

        public CompletableFuture<Boolean> announce(List<JdbcRoutingStore.State> changed) {
            target.routing.announced(caller, changed);
            return CompletableFuture.completedFuture(true);
        }

        public CompletableFuture<List<SignedRecord>> records(Subject s) {
            return CompletableFuture.completedFuture(target.routing.records(s));
        }

        public CompletableFuture<List<Reconciliation.Summary>> summaries(int kind, List<Reconciliation.Range> ranges) {
            return CompletableFuture.completedFuture(target.routing.summaries(kind, ranges));
        }

        public CompletableFuture<List<Map<Integer, Reconciliation.Summary>>> children(int kind, List<Reconciliation.Range> parents) {
            return CompletableFuture.completedFuture(target.routing.children(kind, parents));
        }

        public CompletableFuture<Page> page(int kind, Reconciliation.Cursor after, int limit) {
            return CompletableFuture.completedFuture(target.routing.page(kind, after, limit));
        }

        public CompletableFuture<List<JdbcRoutingStore.State>> states(int kind, Reconciliation.Range range, Reconciliation.Cursor after, int limit) {
            return CompletableFuture.completedFuture(target.routing.states(kind, range, after, limit));
        }

        public CompletableFuture<Outcome> offer(Subject s, List<SignedRecord> records) {
            return CompletableFuture.completedFuture(target.routing.offered(caller, s, records));
        }
    }

    private static class Server {
        final SigningKeyPair key = key(), next = key();
        final ServerInception inception = ServerInception.build(key, next.publicSigningKey, crypto.hasher).join().withProof(GOOD_PROOF);
        final Multihash peerId = inception.peerId();
        final Subject subject = Subject.server(peerId);

        IdentityInception admit(SigningKeyPair identity) {
            IdentityInception inc = IdentityInception.build(identity, List.of(peerId), key().publicSigningKey, crypto.hasher).join();
            return inc.withAdmission(Admission.build(identity.publicSigningKey, GOOD_PROOF, key.secretSigningKey).join());
        }
    }

    private static void waitFor(Callable<Boolean> condition) throws Exception {
        long end = System.currentTimeMillis() + 20_000;
        while (! condition.call()) {
            if (System.currentTimeMillis() > end)
                throw new TimeoutException();
            Thread.sleep(20);
        }
    }

    @Test
    public void announcementsSpreadAndNewInceptionsAreTakenWhileTheirBucketHasTokens() throws Exception {
        Network net = new Network();
        Node a = net.node(100), b = net.node(100), c = net.node(100);
        a.syncWith(b);
        b.syncWith(a);
        b.syncWith(c);
        Server x = new Server();
        IdentityInception alice = x.admit(key());
        Subject aliceSubject = Subject.identity(alice.identityHash());

        a.replica.add(x.subject, x.inception);
        a.replica.add(aliceSubject, alice);
        waitFor(() -> b.replica.log(aliceSubject).inception().isPresent());
        Assert.assertTrue(b.replica.log(x.subject).inception().isPresent());
        Assert.assertArrayEquals(a.replica.stateHash(aliceSubject), b.replica.stateHash(aliceSubject));
        Assert.assertEquals(0, b.routing.waitingInceptions());
    }

    @Test
    public void bootstrapFromOnePeerIsCatchUp() {
        Network net = new Network();
        Node a = net.node(1), b = net.node(1);
        Server x = new Server();
        a.replica.add(x.subject, x.inception);
        List<Subject> identities = new ArrayList<>();
        for (int i = 0; i < 150; i++) {
            IdentityInception inc = x.admit(key());
            Subject s = Subject.identity(inc.identityHash());
            a.replica.add(s, inc);
            identities.add(s);
        }
        b.syncWith(a);
        Assert.assertEquals(151, b.routing.syncRound());
        for (Subject s : identities)
            Assert.assertArrayEquals(a.replica.stateHash(s), b.replica.stateHash(s));
        Assert.assertEquals(0, b.routing.syncRound());
    }

    @Test
    public void reconciliationFindsTheFewThatDiffer() {
        Network net = new Network();
        Node a = net.node(1), b = net.node(1);
        Server x = new Server();
        a.replica.add(x.subject, x.inception);
        b.replica.add(x.subject, x.inception);
        for (int i = 0; i < 300; i++) {
            IdentityInception inc = x.admit(key());
            Subject s = Subject.identity(inc.identityHash());
            a.replica.add(s, inc);
            b.replica.add(s, inc);
        }
        SigningKeyPair moved = key();
        IdentityInception movedInception = x.admit(moved);
        Subject movedSubject = Subject.identity(movedInception.identityHash());
        a.replica.add(movedSubject, movedInception);
        b.replica.add(movedSubject, movedInception);
        a.replica.add(movedSubject, IdentityMove.build(moved, List.of(x.peerId), 1).join());
        IdentityInception extra = x.admit(key());
        Subject extraSubject = Subject.identity(extra.identityHash());
        a.replica.add(extraSubject, extra);

        b.syncWith(a);
        List<JdbcRoutingStore.State> diff = b.routing.differences(new LocalPeer(b.id, a), Subject.IDENTITY);
        Assert.assertEquals(Set.of(movedSubject, extraSubject), new HashSet<>(diff.stream().map(st -> st.subject).toList()));
        Assert.assertEquals(2, b.routing.syncRound());
        Assert.assertEquals(1, b.replica.identity(movedSubject).get().records.update.get().record.counter);
    }

    @Test
    public void aBusySecondsChangesAreAnnouncedAndFetchedInAFewCalls() throws Exception {
        Network net = new Network();
        Node a = net.node(10_000), b = net.node(10_000);
        Server x = new Server();
        a.replica.add(x.subject, x.inception);
        b.replica.add(x.subject, x.inception);
        AtomicInteger announcements = new AtomicInteger(), fetches = new AtomicInteger();
        Node source = a;
        RoutingNode receiver = new RoutingNode(b.replica, crypto.hasher, net.clock::get, 10_000, server -> AddressBucket.UNVERIFIED,
                peer -> new LocalPeer(b.id, source) {
                    @Override
                    public CompletableFuture<Map<Subject, List<SignedRecord>>> recordsOf(List<Subject> subjects) {
                        fetches.incrementAndGet();
                        return CompletableFuture.completedFuture(source.routing.recordsOf(subjects));
                    }
                }, work);
        a.routing.addSyncPeer(new LocalPeer(a.id, b) {
            @Override
            public CompletableFuture<Boolean> announce(List<JdbcRoutingStore.State> changed) {
                announcements.incrementAndGet();
                return CompletableFuture.completedFuture(receiver.announced(caller, changed));
            }
        });
        ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
        a.routing.announceEvery(timer, 500);
        List<Subject> signups = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            IdentityInception inc = x.admit(key());
            signups.add(Subject.identity(inc.identityHash()));
            a.replica.add(signups.get(i), inc);
        }
        waitFor(() -> signups.stream().allMatch(s -> b.replica.log(s).inception().isPresent()));
        Assert.assertTrue("300 changes went in a few announcements: " + announcements.get(), announcements.get() <= 5);
        Assert.assertTrue("and were fetched a batch to a call: " + fetches.get(), fetches.get() <= 10);
        timer.shutdownNow();
    }

    @Test
    public void aNodeStartingFromLittleReadsAConfiguredPeersLogInPages() {
        Network net = new Network();
        Node a = net.node(100), b = net.node(100);
        List<Subject> all = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Server x = new Server();
            a.replica.add(x.subject, x.inception);
            all.add(x.subject);
            for (int j = 0; j < 300; j++) {
                IdentityInception inc = x.admit(key());
                all.add(Subject.identity(inc.identityHash()));
                a.replica.add(all.get(all.size() - 1), inc);
            }
        }
        AtomicInteger pages = new AtomicInteger(), fetches = new AtomicInteger();
        b.routing.addSyncPeer(new LocalPeer(b.id, a) {
            @Override
            public CompletableFuture<Page> page(int kind, Reconciliation.Cursor after, int limit) {
                pages.incrementAndGet();
                return super.page(kind, after, limit);
            }

            @Override
            public CompletableFuture<List<SignedRecord>> records(Subject s) {
                fetches.incrementAndGet();
                return super.records(s);
            }
        });
        b.routing.setConfigured(List.of(a.id));
        b.routing.snapshotBelow(100);

        Assert.assertEquals(all.size(), b.routing.syncRound());
        for (Subject s : all)
            Assert.assertArrayEquals(a.replica.stateHash(s), b.replica.stateHash(s));
        for (int kind : new int[]{Subject.SERVER, Subject.IDENTITY})
            Assert.assertEquals(a.replica.store().summary(kind, Reconciliation.Range.ALL), b.replica.store().summary(kind, Reconciliation.Range.ALL));
        Assert.assertEquals("a page of servers and two of identities", 3, pages.get());
        Assert.assertEquals("nothing is fetched a subject at a time", 0, fetches.get());
        Assert.assertEquals("each admission is checked once, with the rest of its page", 1200, b.replica.admissionChecks());
        Assert.assertEquals("far more than the day's rate, as from any configured peer when starting", 0, b.routing.waitingInceptions());
        Assert.assertEquals(0, b.routing.syncRound());
        Assert.assertTrue(b.routing.isSynced());
        Assert.assertEquals("it isn't read again", 3, pages.get());
    }

    @Test
    public void aCompactPeersLogIsNotReadEndToEndAndAnArchivalOnesStillIs() {
        Network net = new Network();
        Server x = new Server();
        Node provider = net.node(100, x.peerId), compact = net.node(100), archival = net.node(100), b = net.node(100);
        compact.replica.compact(compact.id);
        provider.replica.add(x.subject, x.inception);
        archival.replica.add(x.subject, x.inception);
        for (int j = 0; j < 2500; j++) {
            IdentityInception inc = x.admit(key());
            provider.replica.add(Subject.identity(inc.identityHash()), inc);
            archival.replica.add(Subject.identity(inc.identityHash()), inc);
        }
        compact.syncWith(provider);
        compact.routing.syncRound();
        Reconciliation.Summary all = archival.replica.store().summary(Subject.IDENTITY, Reconciliation.Range.ALL);
        Assert.assertEquals(all, compact.replica.store().summary(Subject.IDENTITY, Reconciliation.Range.ALL));
        net.nodes.remove(provider.id);

        AtomicInteger compactPages = new AtomicInteger(), archivalPages = new AtomicInteger();
        b.routing.addSyncPeer(new LocalPeer(b.id, compact) {
            @Override
            public CompletableFuture<Page> page(int kind, Reconciliation.Cursor after, int limit) {
                compactPages.incrementAndGet();
                return super.page(kind, after, limit);
            }
        });
        b.routing.setConfigured(List.of(compact.id));
        b.routing.snapshotBelow(100);
        b.routing.syncRound();
        Assert.assertEquals("a page with nothing in it is enough to tell", 1, compactPages.get());
        Assert.assertFalse(b.routing.isBootstrapping());
        b.routing.syncRound();
        Assert.assertEquals(1, compactPages.get());

        b.routing.addSyncPeer(new LocalPeer(b.id, archival) {
            @Override
            public CompletableFuture<Page> page(int kind, Reconciliation.Cursor after, int limit) {
                archivalPages.incrementAndGet();
                return super.page(kind, after, limit);
            }
        });
        b.routing.setConfigured(List.of(compact.id, archival.id));
        b.routing.syncRound();
        Assert.assertEquals("a page of servers and three of identities", 4, archivalPages.get());
        Assert.assertEquals(all, b.replica.store().summary(Subject.IDENTITY, Reconciliation.Range.ALL));
    }

    @Test
    public void reconcilingCostsByWhatDiffersNotByWhatIsHeld() {
        Network net = new Network();
        Node a = net.node(1), b = net.node(1);
        Server x = new Server();
        a.replica.add(x.subject, x.inception);
        b.replica.add(x.subject, x.inception);
        for (int i = 0; i < 1500; i++) {
            IdentityInception inc = x.admit(key());
            Subject s = Subject.identity(inc.identityHash());
            a.replica.add(s, inc);
            b.replica.add(s, inc);
        }
        AtomicInteger calls = new AtomicInteger(), listed = new AtomicInteger();
        RoutingPeer counted = new LocalPeer(b.id, a) {
            @Override
            public CompletableFuture<List<Reconciliation.Summary>> summaries(int kind, List<Reconciliation.Range> ranges) {
                calls.incrementAndGet();
                return super.summaries(kind, ranges);
            }

            @Override
            public CompletableFuture<List<Map<Integer, Reconciliation.Summary>>> children(int kind, List<Reconciliation.Range> parents) {
                calls.incrementAndGet();
                return super.children(kind, parents);
            }

            @Override
            public CompletableFuture<List<JdbcRoutingStore.State>> states(int kind, Reconciliation.Range range, Reconciliation.Cursor after, int limit) {
                calls.incrementAndGet();
                return super.states(kind, range, after, limit).thenApply(res -> {
                    listed.addAndGet(res.size());
                    return res;
                });
            }
        };
        Assert.assertEquals(List.of(), b.routing.differences(counted, Subject.IDENTITY));
        Assert.assertEquals("in the same state, one fingerprint is compared", 1, calls.get());
        Assert.assertEquals(0, listed.get());

        Set<Subject> added = new HashSet<>();
        for (int i = 0; i < 3; i++) {
            IdentityInception inc = x.admit(key());
            added.add(Subject.identity(inc.identityHash()));
            a.replica.add(Subject.identity(inc.identityHash()), inc);
        }
        calls.set(0);
        List<JdbcRoutingStore.State> diff = b.routing.differences(counted, Subject.IDENTITY);
        Assert.assertEquals(added, new HashSet<>(diff.stream().map(st -> st.subject).toList()));
        Assert.assertTrue("a call to compare, one to descend, and one for each range that differs: " + calls.get(), calls.get() <= 5);
        Assert.assertTrue("only the ranges that differ are listed: " + listed.get(), listed.get() < 100);
    }

    @Test
    public void aNodeFarBehindListsManyRangesInACall() {
        Network net = new Network();
        Node a = net.node(1), b = net.node(1);
        Server x = new Server();
        a.replica.add(x.subject, x.inception);
        b.replica.add(x.subject, x.inception);
        Set<Subject> lacking = new HashSet<>();
        for (int i = 0; i < 1200; i++) {
            IdentityInception inc = x.admit(key());
            Subject s = Subject.identity(inc.identityHash());
            a.replica.add(s, inc);
            if (i % 2 == 0)
                b.replica.add(s, inc);
            else
                lacking.add(s);
        }
        AtomicInteger calls = new AtomicInteger();
        RoutingPeer counted = new LocalPeer(b.id, a) {
            @Override
            public CompletableFuture<List<Reconciliation.Summary>> summaries(int kind, List<Reconciliation.Range> ranges) {
                calls.incrementAndGet();
                return super.summaries(kind, ranges);
            }

            @Override
            public CompletableFuture<List<Map<Integer, Reconciliation.Summary>>> children(int kind, List<Reconciliation.Range> parents) {
                calls.incrementAndGet();
                return super.children(kind, parents);
            }

            @Override
            public CompletableFuture<List<List<JdbcRoutingStore.State>>> statesIn(int kind, List<Reconciliation.Range> ranges, int limit) {
                calls.incrementAndGet();
                return CompletableFuture.completedFuture(target.routing.statesIn(kind, ranges, limit));
            }

            @Override
            public CompletableFuture<List<JdbcRoutingStore.State>> states(int kind, Reconciliation.Range range, Reconciliation.Cursor after, int limit) {
                calls.incrementAndGet();
                return super.states(kind, range, after, limit);
            }
        };
        List<JdbcRoutingStore.State> diff = b.routing.differences(counted, Subject.IDENTITY);
        Assert.assertEquals(lacking, new HashSet<>(diff.stream().map(st -> st.subject).toList()));
        Assert.assertTrue("nearly every range differs, and they are listed together: " + calls.get(), calls.get() <= 4);

        List<Reconciliation.Range> ranges = new ArrayList<>();
        for (int i = 0; i < Reconciliation.FANOUT; i++)
            ranges.add(Reconciliation.Range.ALL.child(i));
        List<List<JdbcRoutingStore.State>> some = a.routing.statesIn(Subject.IDENTITY, ranges, 100);
        Assert.assertTrue(some.size() < ranges.size());
        Assert.assertTrue("whole ranges, within the limit", some.stream().mapToInt(List::size).sum() <= 100);
        for (int i = 0; i < some.size(); i++)
            Assert.assertEquals(a.replica.store().summary(Subject.IDENTITY, ranges.get(i)).count, some.get(i).size());
    }

    @Test
    public void aPeerWhoseShareOfARoundRunsOutIsStillCaughtUpWith() throws Exception {
        Network net = new Network();
        Node a = net.node(1), b = net.node(1);
        Server x = new Server();
        a.replica.add(x.subject, x.inception);
        b.replica.add(x.subject, x.inception);
        for (int i = 0; i < 800; i++) {
            IdentityInception inc = x.admit(key());
            a.replica.add(Subject.identity(inc.identityHash()), inc);
        }
        b.routing.peerRoundMillis(100);
        // a peer that lists a range to a call, each taking a while
        b.routing.addSyncPeer(new LocalPeer(b.id, a) {
            @Override
            public CompletableFuture<List<List<JdbcRoutingStore.State>>> statesIn(int kind, List<Reconciliation.Range> ranges, int limit) {
                return CompletableFuture.supplyAsync(() -> {
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException e) {}
                    return target.routing.statesIn(kind, ranges.subList(0, 1), limit);
                });
            }
        });
        Reconciliation.Summary all = a.replica.store().summary(Subject.IDENTITY, Reconciliation.Range.ALL);
        int rounds = 0;
        while (! b.replica.store().summary(Subject.IDENTITY, Reconciliation.Range.ALL).equals(all)) {
            Assert.assertTrue("each round brings what it had time to find", b.routing.syncRound() > 0);
            Assert.assertTrue(++rounds < 200);
        }
        Assert.assertTrue("it took more than one round: " + rounds, rounds > 3);
        Assert.assertFalse(b.routing.unreachable().contains(a.id));
    }

    @Test
    public void syncPeersAndRelaysAreDrawnFromARandomStretchOfTheServers() {
        Network net = new Network();
        Node a = net.node(100);
        Set<Subject> all = new HashSet<>();
        for (int i = 0; i < 40; i++) {
            Server x = new Server();
            a.replica.add(x.subject, x.inception);
            all.add(x.subject);
        }
        Set<Subject> drawn = new HashSet<>();
        Random random = new Random(1);
        for (int i = 0; i < 30; i++) {
            List<JdbcRoutingStore.State> some = SyncPeers.someServers(a.replica, random, 10);
            Assert.assertEquals(10, some.size());
            Assert.assertEquals(10, some.stream().map(st -> st.subject).distinct().count());
            some.forEach(st -> drawn.add(st.subject));
        }
        Assert.assertTrue("no ten servers are everyone's choice: " + drawn.size(), drawn.size() > 30);
        List<JdbcRoutingStore.State> every = SyncPeers.someServers(a.replica, new Random(1), 100);
        Assert.assertEquals(40, every.size());
        Assert.assertEquals(all, new HashSet<>(every.stream().map(st -> st.subject).toList()));
    }

    @Test
    public void withoutAMajorityNewInceptionsAreRateLimitedPerBucket() throws Exception {
        Network net = new Network();
        Node a = net.node(2), b = net.node(2), c = net.node(2);
        List<Server> servers = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            Server x = new Server();
            servers.add(x);
            a.replica.add(x.subject, x.inception);
        }
        b.syncWith(a);
        b.syncWith(c);
        Assert.assertEquals(0, b.routing.syncRound());
        // servers are placed in their bucket off the sync thread
        waitFor(() -> servers.stream().filter(x -> b.replica.log(x.subject).exists()).count() == 2
                && b.routing.waitingInceptions() == 3);
        b.routing.tick();
        Assert.assertEquals("no tokens until the clock moves", 3, b.routing.waitingInceptions());
        net.clock.addAndGet(RateLimits.DAY / 2);
        b.routing.tick();
        Assert.assertEquals(2, b.routing.waitingInceptions());
    }

    @Test
    public void earlyUpdatesAreHeldNewestFirstUntilATokenIsDue() {
        Network net = new Network();
        Node a = net.node(100), b = net.node(100);
        Server x = new Server();
        SigningKeyPair id = key();
        IdentityInception inc = x.admit(id);
        Subject s = Subject.identity(inc.identityHash());
        for (Node n : List.of(a, b)) {
            n.replica.add(x.subject, x.inception);
            n.replica.add(s, inc);
        }
        LocalPeer fromA = new LocalPeer(b.id, a);
        for (int counter = 1; counter <= 3; counter++)
            Assert.assertEquals(Outcome.Added, b.routing.offered(a.id, s, List.of(IdentityMove.build(id, List.of(x.peerId), counter).join())));
        Assert.assertEquals(Outcome.Limited, b.routing.offered(a.id, s, List.of(IdentityMove.build(id, List.of(x.peerId), 4).join())));
        Assert.assertEquals(Outcome.Limited, b.routing.offered(a.id, s, List.of(IdentityMove.build(id, List.of(x.peerId), 5).join())));
        Assert.assertEquals(1, b.routing.heldUpdates());
        b.routing.tick();
        Assert.assertEquals(3, b.replica.identity(s).get().records.update.get().record.counter);
        net.clock.addAndGet(RateLimits.MONTH);
        b.routing.tick();
        Assert.assertEquals(0, b.routing.heldUpdates());
        Assert.assertEquals(5, b.replica.identity(s).get().records.update.get().record.counter);
    }

    @Test
    public void anUnprovenPullIsUsedButNotStored() {
        Network net = new Network();
        Node a = net.node(100), b = net.node(100);
        Server x = new Server();
        a.replica.add(x.subject, x.inception);
        IdentityInception signup = IdentityInception.build(key(), List.of(x.peerId), key().publicSigningKey, crypto.hasher).join();
        Subject s = Subject.identity(signup.identityHash());
        a.replica.add(s, signup);

        Optional<SubjectLog> pulled = b.routing.pull(new LocalPeer(b.id, a), s);
        Assert.assertTrue(pulled.isPresent());
        Assert.assertEquals(List.of(x.peerId), ((IdentityLog) pulled.get()).providers());
        Assert.assertTrue(b.replica.log(s).inception().isEmpty());
    }

    /** A chain of rotations, each to the key the one before committed to. */
    private static List<SignedRecord> chain(Server admitter, int rotations) {
        List<SigningKeyPair> keys = new ArrayList<>();
        for (int i = 0; i < rotations + 2; i++)
            keys.add(key());
        List<SignedRecord> res = new ArrayList<>();
        res.add(IdentityInception.build(keys.get(0), List.of(admitter.peerId), keys.get(1).publicSigningKey, crypto.hasher).join()
                .withAdmission(Admission.build(keys.get(0).publicSigningKey, GOOD_PROOF, admitter.key.secretSigningKey).join()));
        for (int i = 1; i <= rotations; i++)
            res.add(IdentityRotation.build(keys.get(i - 1).publicSigningKey, keys.get(i), List.of(admitter.peerId),
                    keys.get(i + 1).publicSigningKey, crypto.hasher).join());
        return res;
    }

    @Test
    public void aLongListOfRecordsHandedInUnaskedIsLeftWithItsSender() {
        Network net = new Network();
        Node a = net.node(100), sender = net.node(100);
        Server x = new Server();
        a.replica.add(x.subject, x.inception);

        List<SignedRecord> unknown = chain(x, RoutingNode.MAX_UNSOLICITED_RECORDS);
        Subject s = Subject.identity(((IdentityInception) unknown.get(0)).identityHash());
        Assert.assertEquals(Outcome.Limited, a.routing.offered(sender.id, s, unknown));
        Assert.assertTrue(a.routing.lookup(s).isEmpty());
        Assert.assertEquals(0, a.routing.waitingInceptions());

        SigningKeyPair owner = key();
        IdentityInception held = x.admit(owner);
        Subject h = Subject.identity(held.identityHash());
        a.replica.add(h, held);
        List<SignedRecord> moves = new ArrayList<>(List.of(held));
        for (int i = 1; i <= RoutingNode.MAX_UNSOLICITED_RECORDS + 1; i++)
            moves.add(IdentityMove.build(owner, List.of(x.peerId), i).join());
        Assert.assertEquals(Outcome.Limited, a.routing.offered(sender.id, h, moves));
        Assert.assertTrue(a.replica.identity(h).get().records.update.isEmpty());
        Assert.assertEquals("records already held don't count", Outcome.Added,
                a.routing.offered(sender.id, h, moves.subList(0, RoutingNode.MAX_UNSOLICITED_RECORDS + 1)));
    }

    @Test
    public void aPullBringsAnOldIdentitysWholeChain() {
        Network net = new Network();
        Node a = net.node(100), b = net.node(100);
        Server x = new Server();
        a.replica.add(x.subject, x.inception);
        b.replica.add(x.subject, x.inception);
        List<SignedRecord> old = chain(x, 100);
        Subject s = Subject.identity(((IdentityInception) old.get(0)).identityHash());
        for (SignedRecord r : old)
            Assert.assertEquals(Outcome.Added, a.replica.add(s, r));

        Optional<SubjectLog> pulled = b.routing.pull(new LocalPeer(b.id, a), s);
        Assert.assertEquals(a.replica.identity(s).get().currentKey(), ((IdentityLog) pulled.get()).currentKey());
    }

    @Test
    public void anIdentityNamingAServerNobodyHoldsIsNotQueued() throws Exception {
        Network net = new Network();
        Node a = net.node(100), sender = net.node(100);
        sender.routing.syncRound();
        Server madeUp = new Server();
        IdentityInception inc = madeUp.admit(key());
        Subject s = Subject.identity(inc.identityHash());
        // it waits only while the sender is asked for the server it names
        Assert.assertNotEquals(Outcome.Added, a.routing.offered(sender.id, s, List.of(inc)));
        waitFor(() -> a.routing.outstandingFetches() == 0);
        Assert.assertEquals(0, a.routing.waitingInceptions());
        a.routing.tick();
        Assert.assertTrue(a.replica.log(s).inception().isEmpty());

        Server real = new Server();
        sender.replica.add(real.subject, real.inception);
        IdentityInception signup = real.admit(key());
        Subject u = Subject.identity(signup.identityHash());
        sender.replica.add(u, signup);
        a.routing.offered(sender.id, u, List.of(signup));
        waitFor(() -> a.replica.log(u).inception().isPresent());
        Assert.assertTrue(a.replica.log(real.subject).inception().isPresent());
    }

    @Test
    public void announcersThatNeverAnswerHoldNoThreadAndFreshKeysShareOneBudget() throws Exception {
        Network net = new Network();
        List<CompletableFuture<Map<Subject, List<SignedRecord>>>> asked = Collections.synchronizedList(new ArrayList<>());
        RoutingReplica replica = new RoutingReplica(new JdbcRoutingStore(Builder.buildEphemeralSqlite(), new SqliteCommands()),
                new RecordValidator(crypto.hasher, TEST_WORK, TEST_WORK), TEST_WORK, TEST_WORK);
        Node placeholder = net.node(100);
        RoutingNode node = new RoutingNode(replica, crypto.hasher, net.clock::get, 100, server -> AddressBucket.UNVERIFIED,
                peer -> new LocalPeer(placeholder.id, placeholder) {
                    @Override
                    public Multihash id() {
                        return peer.bareMultihash();
                    }

                    @Override
                    public CompletableFuture<Map<Subject, List<SignedRecord>>> recordsOf(List<Subject> subjects) {
                        CompletableFuture<Map<Subject, List<SignedRecord>>> never = new CompletableFuture<>();
                        asked.add(never);
                        return never;
                    }
                }, work);
        boolean all = true;
        for (int caller = 0; caller < 5; caller++) {
            List<JdbcRoutingStore.State> states = new ArrayList<>();
            for (int i = 0; i < RoutingNode.MAX_FETCHING_PER_PEER; i++)
                states.add(new JdbcRoutingStore.State(Subject.identity(
                        peergos.shared.storage.ContentAddressedStorage.hashKey(key().publicSigningKey)), new byte[32]));
            all &= node.announced(ServerKeys.peerId(key().publicSigningKey), states);
        }
        Assert.assertFalse("those not taken are announced again later", all);
        Assert.assertEquals(RoutingNode.MAX_FETCHING_PER_PEER, node.outstandingFetches());
        Assert.assertEquals(RoutingNode.MAX_FETCHING_PER_PEER / RoutingNode.FETCH_BATCH, asked.size());
        for (int i = 0; i < 8; i++)
            Assert.assertEquals("free", work.submit(() -> "free").get(5, TimeUnit.SECONDS));
        new ArrayList<>(asked).forEach(f -> f.complete(Map.of()));
        waitFor(() -> node.outstandingFetches() == 0);
    }

    @Test
    public void aSyncPeersClaimsForASubjectAreBoundedInSizeAndNumber() {
        Network net = new Network();
        Node victim = net.node(100), evil = net.node(100);
        victim.syncWith(evil);
        Subject s = Subject.identity(peergos.shared.storage.ContentAddressedStorage.hashKey(key().publicSigningKey));
        for (int i = 0; i < 50; i++) {
            byte[] hash = new byte[32];
            hash[0] = (byte) i;
            victim.routing.announced(evil.id, List.of(new JdbcRoutingStore.State(s, hash)));
        }
        Assert.assertEquals("one claim per sync peer", 1, victim.routing.claimedStates(s));
        try {
            victim.routing.announced(evil.id, List.of(new JdbcRoutingStore.State(s, new byte[1024 * 1024])));
            Assert.fail("a state hash is 32 bytes");
        } catch (IllegalStateException expected) {}
        try {
            Reconciliation.state(Reconciliation.state(new JdbcRoutingStore.State(s, new byte[33])));
            Assert.fail("a state hash is 32 bytes");
        } catch (IllegalStateException expected) {}
        Assert.assertEquals(1, victim.routing.claimedStates(s));
    }

    @Test
    public void aFloodFillsItsOwnBucketsQueueOnly() {
        AtomicLong clock = new AtomicLong(0);
        RateLimits.Inceptions<String> limits = new RateLimits.Inceptions<>(5, 2, clock::get);
        AddressBucket flooded = AddressBucket.of(addr("10.1.2.3")), other = AddressBucket.of(addr("10.2.2.3"));
        int queued = 0;
        for (int i = 0; i < 100; i++)
            if (limits.offer(flooded, ServerKeys.peerId(key().publicSigningKey), "flood " + i))
                queued++;
        Assert.assertEquals(5, queued);
        Assert.assertTrue(limits.offer(other, ServerKeys.peerId(key().publicSigningKey), "signup"));
        Assert.assertTrue(limits.isWaiting("signup"));
        Assert.assertFalse("the same offer twice", limits.offer(other, ServerKeys.peerId(key().publicSigningKey), "signup"));
        Assert.assertEquals(6, limits.take().size() + limits.waiting());
        Assert.assertFalse(limits.isWaiting("flood 0"));
    }

    @Test
    public void aBucketsRateGrowsWithTheLog() {
        AtomicLong clock = new AtomicLong(0);
        RateLimits.Inceptions<String> limits = new RateLimits.Inceptions<>(100, 1000, clock::get);
        Assert.assertEquals("the configured rate while the log is small", 100, limits.perDay(500_000));
        Assert.assertEquals(100, limits.sharedPerDay(100_000));
        Assert.assertEquals(1_000_000, limits.perDay(10_000_000_000L));
        Assert.assertEquals("the bucket unplaced servers share stands for all of them", 4_000_000, limits.sharedPerDay(10_000_000_000L));

        AddressBucket placed = AddressBucket.of(addr("10.1.2.3"));
        for (int i = 0; i < 300; i++) {
            Multihash server = ServerKeys.peerId(key().publicSigningKey);
            limits.offer(placed, server, "placed " + i);
            limits.offer(AddressBucket.UNVERIFIED, server, "shared " + i);
        }
        limits.resize(2_000_000);
        List<String> taken = limits.take();
        Assert.assertEquals(200, taken.stream().filter(o -> o.startsWith("placed")).count());
        Assert.assertEquals(300, taken.stream().filter(o -> o.startsWith("shared")).count());
        clock.addAndGet(RateLimits.DAY / 200 * 10);
        Assert.assertEquals("refilled at the new rate", 10, limits.take().size());
    }

    @Test
    public void roundRobinByAdmittingServerWithinABucket() {
        AtomicLong clock = new AtomicLong(0);
        RateLimits.Inceptions<String> limits = new RateLimits.Inceptions<>(2, 1000, clock::get);
        AddressBucket bucket = AddressBucket.of(addr("10.1.2.3"));
        Multihash flooder = ServerKeys.peerId(key().publicSigningKey), honest = ServerKeys.peerId(key().publicSigningKey);
        for (int i = 0; i < 100; i++)
            limits.offer(bucket, flooder, "flood " + i);
        limits.offer(bucket, honest, "signup");
        Assert.assertEquals(List.of("flood 0", "signup"), limits.take());
    }

    @Test
    public void updateBucketsAreKeptInTheStoreUntilTheyFill() {
        AtomicLong clock = new AtomicLong(1_000_000_000L);
        JdbcRoutingStore store = new JdbcRoutingStore(Builder.buildEphemeralSqlite(), new SqliteCommands());
        RateLimits.Updates updates = new RateLimits.Updates(store, clock::get);
        Subject s = Subject.identity(peergos.shared.storage.ContentAddressedStorage.hashKey(key().publicSigningKey));
        Subject other = Subject.identity(peergos.shared.storage.ContentAddressedStorage.hashKey(key().publicSigningKey));
        for (int i = 0; i < 3; i++)
            Assert.assertTrue(updates.tryTake(s, RecordType.IDENTITY_MOVE));
        Assert.assertFalse(updates.tryTake(s, RecordType.IDENTITY_MOVE));
        Assert.assertTrue("each type has its own", updates.tryTake(s, RecordType.IDENTITY_ROTATION));
        Assert.assertTrue(updates.tryTake(other, RecordType.IDENTITY_MOVE));
        Assert.assertEquals(clock.get() + RateLimits.MONTH, updates.nextToken(s, RecordType.IDENTITY_MOVE));

        RateLimits.Updates restarted = new RateLimits.Updates(store, clock::get);
        Assert.assertFalse("a restart hands out no full buckets", restarted.tryTake(s, RecordType.IDENTITY_MOVE));
        clock.addAndGet(RateLimits.MONTH);
        Assert.assertTrue(restarted.tryTake(s, RecordType.IDENTITY_MOVE));
        Assert.assertFalse(restarted.tryTake(s, RecordType.IDENTITY_MOVE));
        Assert.assertEquals(3, store.countTokens());

        clock.addAndGet(3 * RateLimits.MONTH);
        restarted.prune();
        Assert.assertEquals("only the rotation's, which refills over two quarters", 1, store.countTokens());
        for (int i = 0; i < 3; i++)
            Assert.assertTrue(restarted.tryTake(s, RecordType.IDENTITY_MOVE));
        Assert.assertFalse(restarted.tryTake(s, RecordType.IDENTITY_MOVE));
    }

    @Test
    public void tokenBucketsKeepStateOnlyForRecentUse() {
        AtomicLong clock = new AtomicLong(0);
        RateLimits.TokenBuckets<String> b = new RateLimits.TokenBuckets<>(new RateLimits.Rate(2, 1000), clock::get);
        Assert.assertTrue(b.tryTake("k"));
        Assert.assertTrue(b.tryTake("k"));
        Assert.assertFalse(b.tryTake("k"));
        Assert.assertEquals(1000, b.nextToken("k"));
        clock.set(1000);
        Assert.assertTrue(b.tryTake("k"));
        clock.set(5000);
        b.prune();
        Assert.assertEquals(0, b.size());
    }

    @Test
    public void addressBuckets() {
        Assert.assertEquals(AddressBucket.of(addr("10.1.2.3")), AddressBucket.of(addr("10.1.200.9")));
        Assert.assertNotEquals(AddressBucket.of(addr("10.1.2.3")), AddressBucket.of(addr("10.2.2.3")));
        Assert.assertEquals(AddressBucket.of(addr("2001:db8::1")), AddressBucket.of(addr("2001:db8:ffff::1")));
        Assert.assertNotEquals(AddressBucket.of(addr("2001:db8::1")), AddressBucket.of(addr("2001:db9::1")));
        Assert.assertEquals(AddressBucket.of(addr("10.1.2.3")), AddressBucket.of(addr("::ffff:10.1.9.9")));
    }

    private static InetAddress addr(String s) {
        try {
            return InetAddress.getByName(s);
        } catch (UnknownHostException e) {
            throw new RuntimeException(e);
        }
    }

    private static class FixedRandom extends Random {
        final double next;

        FixedRandom(double next) {
            this.next = next;
        }

        @Override
        public double nextDouble() {
            return next;
        }
    }

    private static LightLookup light(Network net, Node light, Multihash self, double sampling, Node... fullNodes) {
        List<RoutingPeer> peers = new ArrayList<>();
        for (Node f : fullNodes)
            peers.add(new LocalPeer(light.id, f));
        return new LightLookup(light.routing, self, peers, net.clock::get, new FixedRandom(sampling), work);
    }

    @Test
    public void aLightServerResolvesFromItsFullNodeAndCachesWhatItVerified() {
        Network net = new Network();
        Node full = net.node(1), node = net.node(1);
        Server x = new Server();
        SigningKeyPair aliceKey = key();
        IdentityInception alice = x.admit(aliceKey);
        full.replica.add(x.subject, x.inception);
        full.replica.add(Subject.identity(alice.identityHash()), alice);
        LightLookup lookup = light(net, node, ServerKeys.peerId(key().publicSigningKey), 1.0, full);

        Assert.assertEquals(List.of(x.peerId), lookup.identity(alice.identityHash()).get().providers());
        Assert.assertTrue("it holds what it resolved", node.replica.log(Subject.identity(alice.identityHash())).exists());
        Assert.assertTrue(lookup.server(x.peerId).isPresent());

        full.replica.add(Subject.identity(alice.identityHash()), IdentityMove.build(aliceKey, List.of(x.peerId), 5).join());
        Assert.assertTrue("cached while fresh", lookup.identity(alice.identityHash()).get().records.update.isEmpty());
        net.clock.addAndGet(LightLookup.FRESH_MILLIS + 1);
        Assert.assertEquals(5, lookup.identity(alice.identityHash()).get().records.update.get().record.counter);
    }

    @Test
    public void aFullNodeThatWithholdsIsCaughtBySampling() throws Exception {
        Network net = new Network();
        Node honest = net.node(1), withholding = net.node(1), node = net.node(1);
        Server x = new Server();
        SigningKeyPair aliceKey = key();
        IdentityInception alice = x.admit(aliceKey);
        Subject s = Subject.identity(alice.identityHash());
        for (Node f : List.of(honest, withholding)) {
            f.replica.add(x.subject, x.inception);
            f.replica.add(s, alice);
        }
        withholding.replica.add(s, IdentityMove.build(aliceKey, List.of(x.peerId), 1).join());
        honest.replica.add(s, IdentityMove.build(aliceKey, List.of(x.peerId), 2).join());

        Server y = new Server();
        IdentityInception bob = y.admit(key());
        honest.replica.add(y.subject, y.inception);
        honest.replica.add(Subject.identity(bob.identityHash()), bob);
        LightLookup lookup = light(net, node, ServerKeys.peerId(key().publicSigningKey), 0.0, withholding, honest);
        Assert.assertTrue("not found at one full node, so it asks others", lookup.identity(bob.identityHash()).isPresent());

        Assert.assertEquals(1, lookup.identity(alice.identityHash()).get().records.update.get().record.counter);
        waitFor(() -> lookup.identity(alice.identityHash()).get().records.update.get().record.counter == 2);
    }

    @Test
    public void aLightServerHandsItsOwnRecordsAndItsUsersToItsFullNode() throws Exception {
        Network net = new Network();
        Node full = net.node(100), node = net.node(100);
        Server x = new Server();
        IdentityInception bob = x.admit(key());
        Subject bobSubject = Subject.identity(bob.identityHash());
        LightLookup lookup = light(net, node, x.peerId, 1.0, full);
        node.replica.onChange(lookup::publish);

        node.replica.add(x.subject, x.inception);
        node.replica.add(bobSubject, bob);
        full.routing.tick();
        full.routing.tick();
        waitFor(() -> full.replica.log(bobSubject).exists());
        Assert.assertTrue(full.replica.log(x.subject).exists());

        Server other = new Server();
        IdentityInception carol = other.admit(key());
        Subject carolSubject = Subject.identity(carol.identityHash());
        node.replica.add(other.subject, other.inception);
        node.replica.add(carolSubject, carol);
        full.routing.tick();
        full.routing.tick();
        Assert.assertFalse("it passes on only its own and its users'", full.replica.log(carolSubject).exists());
    }


    private static boolean allStripped(Node n, Subject s) {
        List<JdbcRoutingStore.Stored> stored = n.replica.store().get(s);
        return ! stored.isEmpty() && stored.stream().allMatch(r -> r.record.isStripped() && r.hash.isPresent());
    }

    private static boolean noneStripped(Node n, Subject s) {
        List<JdbcRoutingStore.Stored> stored = n.replica.store().get(s);
        return ! stored.isEmpty() && stored.stream().noneMatch(r -> r.record.isStripped() || r.hash.isPresent());
    }

    @Test
    public void aCompactReplicaKeepsNoSignaturesButServesFullRecordsFromTheProvider() throws Exception {
        Network net = new Network();
        Server x = new Server();
        Node provider = net.node(100, x.peerId), compact = net.node(100), archival = net.node(100);
        compact.replica.compact(compact.id);
        SigningKeyPair aliceKey = key();
        IdentityInception alice = x.admit(aliceKey);
        Subject aliceSubject = Subject.identity(alice.identityHash());
        provider.replica.add(x.subject, x.inception);
        provider.replica.add(aliceSubject, alice);

        compact.syncWith(provider);
        compact.routing.syncRound();
        Assert.assertTrue(allStripped(compact, x.subject));
        Assert.assertTrue(allStripped(compact, aliceSubject));
        Assert.assertArrayEquals("compact and archival agree on the state",
                provider.replica.stateHash(aliceSubject), compact.replica.stateHash(aliceSubject));
        Assert.assertEquals(List.of(aliceKey.publicSigningKey), compact.replica.identity(aliceSubject).get().currentKey().stream().toList());
        Assert.assertEquals(List.of(x.peerId), compact.replica.identity(aliceSubject).get().providers());
        Assert.assertTrue(compact.replica.isProven(aliceSubject, false));

        Assert.assertEquals(provider.routing.records(aliceSubject), compact.routing.records(aliceSubject));
        archival.syncWith(compact);
        archival.routing.syncRound();
        Assert.assertTrue("an archival node syncing from a compact one gets full records", noneStripped(archival, aliceSubject));
        Assert.assertTrue(noneStripped(archival, x.subject));
        Assert.assertArrayEquals(provider.replica.stateHash(aliceSubject), archival.replica.stateHash(aliceSubject));

        provider.replica.add(aliceSubject, IdentityMove.build(aliceKey, List.of(x.peerId), 5).join());
        compact.routing.syncRound();
        Assert.assertTrue("a later record is applied to the summary", allStripped(compact, aliceSubject));
        Assert.assertEquals(5, compact.replica.identity(aliceSubject).get().records.update.get().record.counter);
        Assert.assertArrayEquals(provider.replica.stateHash(aliceSubject), compact.replica.stateHash(aliceSubject));

        RoutingReplica restarted = new RoutingReplica(compact.replica.store(),
                new RecordValidator(crypto.hasher, TEST_WORK, TEST_WORK), TEST_WORK, TEST_WORK);
        Assert.assertArrayEquals(compact.replica.stateHash(aliceSubject), restarted.stateHash(aliceSubject));
        Assert.assertTrue(restarted.isProven(aliceSubject, false));

        net.nodes.remove(provider.id);
        try {
            compact.routing.records(aliceSubject);
            Assert.fail("Without the provider the signatures can't be found");
        } catch (IllegalStateException expected) {}
    }

    @Test
    public void aCompactReplicaAsksAProviderOnceForAStateHoweverManyAskIt() throws Exception {
        Network net = new Network();
        Server x = new Server();
        Node provider = net.node(100, x.peerId), compact = net.node(100);
        compact.replica.compact(compact.id);
        IdentityInception alice = x.admit(key()), bob = x.admit(key());
        Subject aliceSubject = Subject.identity(alice.identityHash()), bobSubject = Subject.identity(bob.identityHash());
        provider.replica.add(x.subject, x.inception);
        provider.replica.add(aliceSubject, alice);
        provider.replica.add(bobSubject, bob);
        compact.syncWith(provider);
        compact.routing.syncRound();
        Assert.assertFalse(compact.replica.servesAtOnce(aliceSubject));

        AtomicInteger dials = new AtomicInteger();
        AtomicBoolean offline = new AtomicBoolean();
        java.util.function.Function<Multihash, RoutingPeer> dial = target -> new LocalPeer(compact.id, provider) {
            @Override
            public CompletableFuture<List<SignedRecord>> records(Subject s) {
                dials.incrementAndGet();
                if (offline.get())
                    return CompletableFuture.failedFuture(new IllegalStateException("Connection refused"));
                return CompletableFuture.supplyAsync(() -> {
                    try {
                        Thread.sleep(300);
                    } catch (InterruptedException e) {}
                    return target.routing.records(s);
                });
            }
        };
        List<Future<List<SignedRecord>>> asking = new ArrayList<>();
        ExecutorService askers = Executors.newFixedThreadPool(8);
        for (int i = 0; i < 8; i++)
            asking.add(askers.submit(() -> compact.replica.records(aliceSubject, dial)));
        for (Future<List<SignedRecord>> f : asking)
            Assert.assertEquals(provider.routing.records(aliceSubject), f.get(10, TimeUnit.SECONDS));
        Assert.assertEquals("one fetch for everyone waiting on a state", 1, dials.get());
        Assert.assertTrue(compact.replica.servesAtOnce(aliceSubject));

        offline.set(true);
        for (int i = 0; i < 5; i++) {
            try {
                compact.replica.records(bobSubject, dial);
                Assert.fail("Its provider is offline");
            } catch (IllegalStateException expected) {}
        }
        Assert.assertEquals("a provider that failed isn't asked again at once", 2, dials.get());
        askers.shutdown();
    }

    @Test
    public void aCompactReplicaVerifiesBeforeStrippingAndKeepsItsOwnInFull() {
        Network net = new Network();
        Server x = new Server(), y = new Server();
        Node compact = net.node(100, x.peerId);
        compact.replica.compact(compact.id);
        IdentityInception hosted = x.admit(key()), elsewhere = y.admit(key());
        Subject hostedSubject = Subject.identity(hosted.identityHash()), elsewhereSubject = Subject.identity(elsewhere.identityHash());
        Assert.assertEquals(Outcome.Added, compact.replica.add(x.subject, x.inception));
        Assert.assertEquals(Outcome.Added, compact.replica.add(y.subject, y.inception));
        Assert.assertEquals(Outcome.Rejected, compact.replica.add(hostedSubject, RecordType.stripped(hosted)));
        byte[] forged = hosted.signature.clone();
        forged[0] ^= 1;
        Assert.assertEquals(Outcome.Rejected, compact.replica.add(hostedSubject, RecordType.withSignature(hosted, forged)));
        Assert.assertEquals(Outcome.Added, compact.replica.add(hostedSubject, hosted));
        Assert.assertEquals(Outcome.Added, compact.replica.add(elsewhereSubject, elsewhere));
        Assert.assertTrue("this server", noneStripped(compact, x.subject));
        Assert.assertTrue("a user it hosts", noneStripped(compact, hostedSubject));
        Assert.assertTrue(allStripped(compact, y.subject));
        Assert.assertTrue(allStripped(compact, elsewhereSubject));
    }

    @Test
    public void aSyncPeerClaimingWhatItCantDeliverCostsAFewCallsAndDoesntKeepANodeUnsynced() {
        Network net = new Network();
        Node victim = net.node(100), evil = net.node(100);
        Server x = new Server();
        IdentityInception alice = x.admit(key());
        Subject aliceSubject = Subject.identity(alice.identityHash());
        for (int i = 0; i < 3; i++) {
            Node honest = net.node(100);
            honest.replica.add(x.subject, x.inception);
            honest.replica.add(aliceSubject, alice);
            victim.syncWith(honest);
        }
        List<JdbcRoutingStore.State> madeUp = new ArrayList<>();
        for (int i = 0; i < 10 * RoutingNode.FETCH_BATCH; i++)
            madeUp.add(new JdbcRoutingStore.State(Subject.identity(
                    peergos.shared.storage.ContentAddressedStorage.hashKey(key().publicSigningKey)), new byte[32]));
        madeUp.sort(Comparator.comparingInt((JdbcRoutingStore.State st) -> Reconciliation.slot(st.subject.idBytes()))
                .thenComparing(st -> st.subject));
        AtomicInteger asked = new AtomicInteger();
        victim.routing.addSyncPeer(new LocalPeer(victim.id, evil) {
            @Override
            public CompletableFuture<List<Reconciliation.Summary>> summaries(int kind, List<Reconciliation.Range> ranges) {
                if (kind != Subject.IDENTITY)
                    return super.summaries(kind, ranges);
                byte[] fingerprint = new byte[32];
                fingerprint[0] = 1;
                return CompletableFuture.completedFuture(Collections.nCopies(ranges.size(), new Reconciliation.Summary(1, fingerprint)));
            }

            @Override
            public CompletableFuture<List<JdbcRoutingStore.State>> states(int kind, Reconciliation.Range range, Reconciliation.Cursor after, int limit) {
                if (kind != Subject.IDENTITY)
                    return super.states(kind, range, after, limit);
                return CompletableFuture.completedFuture(madeUp.stream()
                        .filter(st -> range.contains(st.subject.idBytes()) && after.isBefore(st.subject))
                        .limit(limit)
                        .toList());
            }

            @Override
            public CompletableFuture<Map<Subject, List<SignedRecord>>> recordsOf(List<Subject> subjects) {
                asked.incrementAndGet();
                return CompletableFuture.failedFuture(new IllegalStateException("Timed out"));
            }
        });

        Assert.assertTrue(victim.routing.syncRound() > 0);
        Assert.assertEquals(RoutingNode.MAX_FAILURES_PER_PEER, asked.get());
        Assert.assertTrue(victim.replica.log(aliceSubject).inception().isPresent());
        Assert.assertEquals(0, victim.routing.syncRound());
        Assert.assertTrue("what one drawn peer alone claims doesn't keep a node unsynced", victim.routing.isSynced());
        Assert.assertEquals(2 * RoutingNode.MAX_FAILURES_PER_PEER, asked.get());
        Assert.assertTrue("it failed two rounds running", victim.routing.unreachable().contains(evil.id));
    }

    @Test
    public void identitiesNamingServersTheirSenderWontServeCostItsBudgetNotTheRound() {
        Network net = new Network();
        Node victim = net.node(100), evil = net.node(100);
        Server x = new Server();
        for (int i = 0; i < 3; i++) {
            Node honest = net.node(100);
            honest.replica.add(x.subject, x.inception);
            victim.syncWith(honest);
        }
        Map<Subject, List<SignedRecord>> orphans = new HashMap<>();
        for (int i = 0; i < 40; i++) {
            IdentityInception orphan = new Server().admit(key());
            orphans.put(Subject.identity(orphan.identityHash()), List.of(orphan));
        }
        List<JdbcRoutingStore.State> claimed = orphans.entrySet().stream()
                .map(e -> new JdbcRoutingStore.State(e.getKey(), victim.replica.stateHash(e.getValue())))
                .sorted(Comparator.comparingInt((JdbcRoutingStore.State st) -> Reconciliation.slot(st.subject.idBytes()))
                        .thenComparing(st -> st.subject))
                .toList();
        AtomicInteger askedForServers = new AtomicInteger();
        victim.routing.addSyncPeer(new LocalPeer(victim.id, evil) {
            @Override
            public CompletableFuture<List<Reconciliation.Summary>> summaries(int kind, List<Reconciliation.Range> ranges) {
                if (kind != Subject.IDENTITY)
                    return super.summaries(kind, ranges);
                byte[] fingerprint = new byte[32];
                fingerprint[0] = 1;
                return CompletableFuture.completedFuture(Collections.nCopies(ranges.size(), new Reconciliation.Summary(1, fingerprint)));
            }

            @Override
            public CompletableFuture<List<JdbcRoutingStore.State>> states(int kind, Reconciliation.Range range, Reconciliation.Cursor after, int limit) {
                if (kind != Subject.IDENTITY)
                    return super.states(kind, range, after, limit);
                return CompletableFuture.completedFuture(claimed.stream()
                        .filter(st -> range.contains(st.subject.idBytes()) && after.isBefore(st.subject))
                        .limit(limit)
                        .toList());
            }

            @Override
            public CompletableFuture<Map<Subject, List<SignedRecord>>> recordsOf(List<Subject> subjects) {
                Map<Subject, List<SignedRecord>> res = new HashMap<>();
                for (Subject s : subjects)
                    if (orphans.containsKey(s))
                        res.put(s, orphans.get(s));
                return CompletableFuture.completedFuture(res);
            }

            @Override
            public CompletableFuture<List<SignedRecord>> records(Subject s) {
                askedForServers.incrementAndGet();
                return CompletableFuture.failedFuture(new IllegalStateException("Timed out"));
            }
        });

        victim.routing.syncRound();
        Assert.assertEquals(RoutingNode.MAX_FAILURES_PER_PEER, askedForServers.get());
        Assert.assertTrue(victim.replica.log(x.subject).inception().isPresent());
        victim.routing.syncRound();
        Assert.assertEquals(2 * RoutingNode.MAX_FAILURES_PER_PEER, askedForServers.get());
        Assert.assertTrue("it failed two rounds running", victim.routing.unreachable().contains(evil.id));
        Assert.assertTrue(victim.routing.isSynced());
    }

    @Test
    public void aNodeAnswersNotSyncedYetUntilARoundFindsNothingNew() throws Exception {
        Network net = new Network();
        Node a = net.node(100), b = net.node(100), lonely = net.node(100);
        Server x = new Server();
        IdentityInception alice = x.admit(key());
        Subject aliceSubject = Subject.identity(alice.identityHash()), unknown = Subject.identity(x.admit(key()).identityHash());
        a.replica.add(x.subject, x.inception);
        a.replica.add(aliceSubject, alice);
        b.syncWith(a);
        b.routing.setConfigured(List.of(a.id));

        Assert.assertFalse(b.routing.isSynced());
        try {
            b.routing.records(unknown);
            Assert.fail("Absent before syncing is not yet known");
        } catch (IllegalStateException e) {
            Assert.assertEquals(RoutingNode.NOT_SYNCED, e.getMessage());
        }
        Assert.assertTrue(b.routing.syncRound() > 0);
        Assert.assertFalse("it was behind", b.routing.isSynced());
        Assert.assertEquals(0, b.routing.syncRound());
        Assert.assertTrue(b.routing.isSynced());
        Assert.assertEquals(List.of(), b.routing.records(unknown));
        Assert.assertEquals("it holds what it had", a.routing.records(aliceSubject), b.routing.records(aliceSubject));

        lonely.routing.syncRound();
        Assert.assertTrue("with no peers there is nothing to wait for", lonely.routing.isSynced());

        Node c = net.node(100), gone = net.node(100);
        c.syncWith(a);
        c.routing.addSyncPeer(new LocalPeer(c.id, gone) {
            @Override
            public CompletableFuture<List<Reconciliation.Summary>> summaries(int kind, List<Reconciliation.Range> ranges) {
                return CompletableFuture.failedFuture(new RuntimeException("Connection refused"));
            }
        });
        c.routing.setConfigured(List.of(gone.id));
        c.routing.syncRound();
        c.routing.syncRound();
        Assert.assertFalse("its configured peer took no part", c.routing.isSynced());
    }

    @Test
    public void aServerAskedForByItsEndpointIsAnsweredAndKeptUnderItsFirstPeerid() {
        Network net = new Network();
        Node a = net.node(100), b = net.node(100);
        Server x = new Server();
        SigningKeyPair endpoint = key();
        a.replica.add(x.subject, x.inception);
        a.replica.add(x.subject, ServerAddresses.build(x.key, endpoint.publicSigningKey, Optional.empty(), List.of(), List.of(),
                ServerAddresses.Mode.Archival, false, 1).join());
        Multihash endpointPeer = ServerKeys.peerId(endpoint.publicSigningKey);
        b.routing.fetch(new LocalPeer(b.id, a), Subject.server(endpointPeer), true);
        Assert.assertTrue(b.replica.log(x.subject).exists());
        Assert.assertEquals(Optional.of(x.peerId.bareMultihash()), b.replica.serverForPeer(endpointPeer).map(l -> l.peerId.bareMultihash()));
    }

    @Test
    public void aServerRecordWithAddressesSurvivesBeingStrippedAndRestored() {
        Server x = new Server();
        ServerAddresses addrs = ServerAddresses.build(x.key, x.key.publicSigningKey, Optional.empty(),
                ServerAddressPublisher.directAddresses(List.of("/ip4/198.51.100.1/tcp/4001")), List.of(x.peerId),
                ServerAddresses.Mode.Compact, true, 3).join();
        SignedRecord stripped = RecordType.stripped(addrs);
        Assert.assertTrue(stripped.isStripped());
        Assert.assertEquals(addrs.addrs, ((ServerAddresses) stripped).addrs);
        Assert.assertEquals(addrs, RecordType.withSignature(stripped, addrs.signature));
    }
}
