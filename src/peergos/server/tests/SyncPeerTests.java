package peergos.server.tests;

import org.junit.*;
import peergos.server.*;
import peergos.server.routing.*;
import peergos.server.sql.*;
import peergos.shared.*;
import peergos.shared.crypto.*;
import peergos.shared.io.ipfs.*;
import peergos.shared.routing.*;
import peergos.shared.util.*;

import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

public class SyncPeerTests {
    private static final Crypto crypto = Main.initCrypto();
    private static final CuckooCycle WORK = new CuckooCycle(16, 0);
    private static final ExecutorService background = Executors.newFixedThreadPool(2);

    private static RoutingReplica replica() {
        return new RoutingReplica(new JdbcRoutingStore(Builder.buildEphemeralSqlite(), new SqliteCommands()),
                new RecordValidator(crypto.hasher, WORK, WORK), WORK, WORK);
    }

    private static SigningKeyPair key() {
        return SigningKeyPair.random(crypto.random, crypto.signer);
    }

    private static Multihash server(RoutingReplica replica, boolean proven, String addr, ServerAddresses.Mode mode) {
        SigningKeyPair k = key();
        ServerInception inception = ServerInception.build(k, key().publicSigningKey, crypto.hasher).join();
        if (proven)
            inception = inception.withProof(new CuckooSolver(WORK).solve(inception.server.serialize(), 0, 100_000).get().serialize());
        Subject s = Subject.server(inception.peerId());
        Assert.assertEquals(Outcome.Added, replica.add(s, inception));
        List<Address> direct = addr == null ? List.of() : ServerAddressPublisher.directAddresses(List.of(addr));
        Assert.assertEquals(Outcome.Added, replica.add(s, ServerAddresses.build(k, k.publicSigningKey, Optional.empty(), direct,
                List.of(), mode, false, 1).join()));
        return inception.peerId().bareMultihash();
    }

    /** Reaches a server at the first address it lists, unless told it is somewhere else. */
    private static BucketVerifier.Dialler dialler(Map<Multihash, String> elsewhere) {
        return (peer, addrs) -> {
            if (elsewhere.containsKey(peer.bareMultihash()))
                return Futures.of(BucketVerifier.ip(elsewhere.get(peer.bareMultihash())));
            return Futures.of(addrs.isEmpty() ? Optional.empty() : BucketVerifier.ip(addrs.get(0)));
        };
    }

    /** A full node answering in process. */
    private static class Full extends Peer {
        final RoutingNode target;

        Full(Multihash id, RoutingNode target) {
            super(id);
            this.target = target;
        }

        public CompletableFuture<List<SignedRecord>> records(Subject s) {
            return Futures.of(target.records(s));
        }

        public CompletableFuture<List<JdbcRoutingStore.State>> states(int kind, Reconciliation.Range range, int limit) {
            return Futures.of(target.states(kind, range, limit));
        }
    }

    private static class Peer implements RoutingPeer {
        final Multihash id;

        Peer(Multihash id) {
            this.id = id.bareMultihash();
        }

        public Multihash id() {
            return id;
        }

        public CompletableFuture<Boolean> announce(List<JdbcRoutingStore.State> changed) {
            return Futures.of(true);
        }

        public CompletableFuture<List<SignedRecord>> records(Subject s) {
            return Futures.of(List.of());
        }

        public CompletableFuture<List<Reconciliation.Summary>> summaries(int kind, List<Reconciliation.Range> ranges) {
            return Futures.errored(new IllegalStateException());
        }

        public CompletableFuture<List<JdbcRoutingStore.State>> states(int kind, Reconciliation.Range range, int limit) {
            return Futures.of(List.of());
        }

        public CompletableFuture<Outcome> offer(Subject s, List<SignedRecord> records) {
            return Futures.of(Outcome.Ignored);
        }
    }

    @Test
    public void aServerIsPlacedInABucketOnlyWhereItIsReached() throws Exception {
        RoutingReplica replica = replica();
        Multihash here = server(replica, true, "/ip4/198.51.100.1/tcp/4001", ServerAddresses.Mode.Archival);
        Multihash lying = server(replica, true, "/ip4/203.0.113.1/tcp/4001", ServerAddresses.Mode.Archival);
        Multihash relayed = server(replica, true, null, ServerAddresses.Mode.Archival);
        long[] now = {1_000_000};
        BucketVerifier v = new BucketVerifier(RoutingLookup.full(replica),
                dialler(Map.of(lying, "/ip4/192.0.2.9/tcp/4001")), () -> now[0], background);

        Assert.assertEquals("never dials while asked", AddressBucket.UNVERIFIED, v.bucket(here));
        for (int i = 0; i < 100 && v.verified(here).isEmpty(); i++)
            Thread.sleep(20);
        Assert.assertEquals("198.51/16", v.bucket(here).toString());
        Assert.assertEquals("reached somewhere it doesn't list", Optional.empty(), v.verify(lying));
        Assert.assertEquals("no direct address", Optional.empty(), v.verify(relayed));
        Assert.assertEquals(AddressBucket.UNVERIFIED, v.bucket(relayed));
    }

    @Test
    public void syncPeersAreTheConfiguredOnesAndOneFullNodePerBucket() {
        RoutingReplica replica = replica();
        List<Multihash> sameRange = new ArrayList<>();
        for (int i = 1; i <= 5; i++)
            sameRange.add(server(replica, true, "/ip4/198.51." + i + ".1/tcp/4001", ServerAddresses.Mode.Archival));
        Multihash other = server(replica, true, "/ip4/203.0.113.1/tcp/4001", ServerAddresses.Mode.Compact);
        Multihash light = server(replica, true, "/ip4/192.0.2.1/tcp/4001", ServerAddresses.Mode.Light);
        Multihash unproven = server(replica, false, "/ip4/192.0.2.77/tcp/4001", ServerAddresses.Mode.Archival);
        Multihash relayed = server(replica, true, null, ServerAddresses.Mode.Archival);
        Multihash configured = ServerKeys.peerId(key().publicSigningKey);
        Multihash self = server(replica, true, "/ip4/198.18.0.1/tcp/4001", ServerAddresses.Mode.Archival);

        RoutingNode node = new RoutingNode(replica, crypto.hasher, System::currentTimeMillis, 100,
                id -> AddressBucket.UNVERIFIED, Peer::new, background);
        BucketVerifier v = new BucketVerifier(RoutingLookup.full(replica), dialler(Map.of()), System::currentTimeMillis, background);
        SyncPeers peers = new SyncPeers(node, v, List.of(new Peer(configured)), self, false, new Random(1));
        peers.refresh();

        Set<Multihash> chosen = node.syncPeers().stream().map(p -> p.id().bareMultihash()).collect(Collectors.toSet());
        Assert.assertTrue(chosen.contains(configured));
        Assert.assertTrue(chosen.contains(other));
        Assert.assertEquals("five sybils in one /16 get one slot", 1, sameRange.stream().filter(chosen::contains).count());
        Assert.assertEquals(3, chosen.size());
        for (Multihash excluded : List.of(light, unproven, relayed, self))
            Assert.assertFalse(chosen.contains(excluded));

        Set<Multihash> seen = new HashSet<>();
        for (int i = 0; i < 20; i++) {
            peers.refresh();
            Set<Multihash> now = node.syncPeers().stream().map(p -> p.id().bareMultihash()).collect(Collectors.toSet());
            Assert.assertEquals("one per bucket, every round", 3, now.size());
            Assert.assertTrue(now.contains(configured));
            seen.addAll(now);
        }
        Assert.assertTrue("drawn peers rotate", sameRange.stream().filter(seen::contains).count() > 1);
    }

    @Test
    public void aLightServerDiscoversFullNodesAndSamplesThemByBucket() {
        RoutingReplica full = replica();
        List<Multihash> nodes = new ArrayList<>();
        for (int i = 1; i <= 4; i++)
            nodes.add(server(full, true, "/ip4/" + (190 + i) + ".0.2.1/tcp/4001", ServerAddresses.Mode.Archival));
        RoutingNode fullNode = new RoutingNode(full, crypto.hasher, System::currentTimeMillis, 100,
                id -> AddressBucket.UNVERIFIED, Peer::new, background);

        RoutingReplica lightReplica = replica();
        Multihash self = server(lightReplica, true, null, ServerAddresses.Mode.Light);
        RoutingNode light = new RoutingNode(lightReplica, crypto.hasher, System::currentTimeMillis, 100,
                id -> AddressBucket.UNVERIFIED, Peer::new, background);
        Full configured = new Full(ServerKeys.peerId(key().publicSigningKey), fullNode);
        BucketVerifier v = new BucketVerifier(RoutingLookup.full(lightReplica), dialler(Map.of()), System::currentTimeMillis, background);
        SyncPeers peers = new SyncPeers(light, v, List.of(configured), self, true, new Random(1));
        peers.refresh();

        Set<Multihash> drawn = peers.drawn().stream().map(p -> p.id().bareMultihash()).collect(Collectors.toSet());
        Assert.assertEquals(new HashSet<>(nodes), drawn);
        Assert.assertTrue("a light server doesn't reconcile", light.syncPeers().isEmpty());
    }
}
