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

        public CompletableFuture<List<JdbcRoutingStore.State>> states(int kind, Reconciliation.Range range, int limit) {
            return CompletableFuture.completedFuture(target.routing.states(kind, range, limit));
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
    public void announcementsSpreadAndNewInceptionsWaitTheirTurn() throws Exception {
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
        waitFor(() -> b.routing.waitingInceptions() == 2);
        Assert.assertFalse("new inceptions wait for the bucket's rate", b.replica.log(x.subject).exists());

        b.routing.tick();
        Assert.assertTrue(b.replica.log(x.subject).inception().isPresent());
        b.routing.tick();
        Assert.assertTrue(b.replica.log(aliceSubject).inception().isPresent());
        Assert.assertArrayEquals(a.replica.stateHash(aliceSubject), b.replica.stateHash(aliceSubject));
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
    public void withoutAMajorityNewInceptionsAreRateLimitedPerBucket() {
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
        Assert.assertEquals(5, b.routing.waitingInceptions());
        b.routing.tick();
        Assert.assertEquals(3, b.routing.waitingInceptions());
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
}
