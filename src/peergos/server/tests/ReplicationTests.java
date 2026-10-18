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
            Node n = new Node(this, inceptionsPerDay);
            nodes.put(n.id, n);
            return n;
        }
    }

    private static class Node {
        final Multihash id = ServerKeys.peerId(key().publicSigningKey);
        final RoutingReplica replica;
        final RoutingNode routing;

        Node(Network net, int inceptionsPerDay) {
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
}
