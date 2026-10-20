package peergos.server.tests;

import io.libp2p.core.PeerId;
import io.libp2p.core.multiformats.Multiaddr;
import io.libp2p.protocol.circuit.RelayTransport.CandidateRelay;
import org.junit.*;
import peergos.server.*;
import peergos.server.routing.*;
import peergos.server.sql.*;
import peergos.shared.*;
import peergos.shared.crypto.*;
import peergos.shared.io.ipfs.*;
import peergos.shared.routing.*;

import java.util.*;
import java.util.stream.*;

public class RelayTests {
    private static final Crypto crypto = Main.initCrypto();
    private static final CuckooCycle WORK = new CuckooCycle(16, 0);

    private static RoutingReplica replica() {
        return new RoutingReplica(new JdbcRoutingStore(Builder.buildEphemeralSqlite(), new SqliteCommands()),
                new RecordValidator(crypto.hasher, WORK, WORK), WORK, WORK);
    }

    private static SigningKeyPair key() {
        return SigningKeyPair.random(crypto.random, crypto.signer);
    }

    /** A server in the replica, its proof of work done if proven, with the addresses it signed. */
    private static SigningKeyPair server(RoutingReplica replica, boolean proven, String addr, boolean relaying, List<Multihash> relays) {
        SigningKeyPair k = key();
        ServerInception inception = ServerInception.build(k, key().publicSigningKey, crypto.hasher).join();
        if (proven)
            inception = inception.withProof(new CuckooSolver(WORK).solve(inception.server.serialize(), 0, 100_000).get().serialize());
        Subject s = Subject.server(inception.peerId());
        Assert.assertEquals(Outcome.Added, replica.add(s, inception));
        List<Address> direct = addr == null ? List.of() : ServerAddressPublisher.directAddresses(List.of(addr));
        Assert.assertEquals(Outcome.Added, replica.add(s, ServerAddresses.build(k, k.publicSigningKey, Optional.empty(), direct,
                relays, ServerAddresses.Mode.Archival, relaying, 1).join()));
        return k;
    }

    private static Multihash peer(SigningKeyPair k) {
        return ServerKeys.peerId(k.publicSigningKey);
    }

    private static PeerId libp2p(SigningKeyPair k) {
        return PeerId.fromBase58(peer(k).toBase58());
    }

    @Test
    public void relaysAreRelayingProvenServersWithADirectAddressSpreadOverBuckets() {
        RoutingReplica replica = replica();
        SigningKeyPair a = server(replica, true, "/ip4/198.51.100.1/tcp/4001", true, List.of());
        SigningKeyPair sameBucket = server(replica, true, "/ip4/198.51.200.2/tcp/4001", true, List.of());
        SigningKeyPair otherBucket = server(replica, true, "/ip4/203.0.113.3/tcp/4001", true, List.of());
        server(replica, true, "/ip4/192.0.2.4/tcp/4001", false, List.of());
        server(replica, false, "/ip4/192.0.2.5/tcp/4001", true, List.of());
        server(replica, true, null, false, List.of());
        SigningKeyPair self = server(replica, true, "/ip4/192.0.2.6/tcp/4001", true, List.of());

        Relays relays = new Relays(List.of(sameBucket.publicSigningKey).stream().map(ServerKeys::peerId).collect(Collectors.toList()),
                System::currentTimeMillis, new Random(1));
        Assert.assertTrue("nothing before the log starts", relays.candidates(null).isEmpty());
        relays.start(RoutingLookup.full(replica), peer(self));
        List<PeerId> chosen = relays.candidates(null).stream().map(c -> c.id).collect(Collectors.toList());
        Assert.assertEquals(Set.of(libp2p(a), libp2p(sameBucket), libp2p(otherBucket)), new HashSet<>(chosen));
        Assert.assertEquals("the configured relay comes first", libp2p(sameBucket), chosen.get(0));
        Assert.assertEquals("then one in another bucket", libp2p(otherBucket), chosen.get(1));
    }

    @Test
    public void aRelayVouchesOnlyForItsOwnServersAndThoseWhoseUsersItMirrors() throws Exception {
        RoutingReplica replica = replica();
        SigningKeyPair self = server(replica, true, "/ip4/192.0.2.6/tcp/4001", true, List.of());
        Multihash own = peer(server(replica, true, null, false, List.of(peer(self))));
        Multihash mirrored = peer(server(replica, true, null, false, List.of(peer(self))));
        Multihash later = peer(server(replica, true, null, false, List.of(peer(self))));
        Multihash stranger = peer(server(replica, true, null, false, List.of(peer(self))));
        Subject before = user(replica, List.of(mirrored, peer(self)));
        user(replica, List.of(stranger));
        user(replica, List.of(peer(self), stranger));
        Relays relays = new Relays(List.of(), List.of(own), true, Relays.TOTAL_BYTES_PER_SECOND, System::currentTimeMillis, new Random(1));
        relays.start(RoutingLookup.full(replica), peer(self));
        Assert.assertTrue(relays.vouchesFor(own));
        SigningKeyPair starting = key();
        Relays withStarting = new Relays(List.of(), List.of(peer(starting)), true, Relays.TOTAL_BYTES_PER_SECOND,
                System::currentTimeMillis, new Random(1));
        withStarting.start(RoutingLookup.full(replica), peer(self));
        Assert.assertTrue("a first-tier server gets a slot before its proof of work is done", withStarting.mayReserve(libp2p(starting)));
        Assert.assertEquals(Optional.of(Relays.RESERVATION), withStarting.reserve(libp2p(starting), Multiaddr.fromString("/ip4/198.51.100.9/tcp/4001")));
        for (int i = 0; i < 100 && ! relays.vouchesFor(mirrored); i++)
            Thread.sleep(50);
        Assert.assertTrue("a server whose user lists us as a mirror, found in the replica", relays.vouchesFor(mirrored));
        Assert.assertFalse("listing us as a relay vouches for nothing", relays.vouchesFor(stranger));
        Assert.assertFalse(relays.vouchesFor(later));
        user(replica, List.of(later, peer(self)));
        Assert.assertTrue("as a user's providers change", relays.vouchesFor(later));
        Assert.assertTrue(relays.vouchesFor(mirrored));
    }

    private static Subject user(RoutingReplica replica, List<Multihash> providers) {
        IdentityInception inception = IdentityInception.build(key(), providers, key().publicSigningKey, crypto.hasher).join();
        Subject s = Subject.identity(inception.identityHash());
        Assert.assertEquals(Outcome.Added, replica.add(s, inception));
        return s;
    }

    @Test
    public void aRelayTakesOnlyProvenServersAndLimitsDials() {
        RoutingReplica replica = replica();
        SigningKeyPair proven = server(replica, true, null, false, List.of());
        SigningKeyPair unproven = server(replica, false, null, false, List.of());
        long[] now = {1_000_000};
        Relays relays = new Relays(List.of(), () -> now[0], new Random(1));
        Assert.assertFalse(relays.mayReserve(libp2p(proven)));
        relays.start(RoutingLookup.full(replica), peer(key()));
        Assert.assertTrue(relays.mayReserve(libp2p(proven)));
        Assert.assertFalse(relays.mayReserve(libp2p(unproven)));
        Assert.assertFalse("a server not in the log", relays.mayReserve(PeerId.random()));

        PeerId target = libp2p(proven), dialler = libp2p(server(replica, true, null, false, List.of()));
        for (int i = 0; i < Relays.DIALS_PER_INITIATOR_PER_MINUTE; i++)
            Assert.assertTrue(relays.mayConnect(target, dialler));
        Assert.assertFalse(relays.mayConnect(target, dialler));
        Assert.assertTrue("another dialler", relays.mayConnect(target, libp2p(server(replica, true, null, false, List.of()))));
        now[0] += 60_001;
        Assert.assertTrue(relays.mayConnect(target, dialler));
    }

    @Test
    public void diallersWithoutProofOfWorkShareASmallPool() {
        RoutingReplica replica = replica();
        PeerId target = libp2p(server(replica, true, null, false, List.of()));
        PeerId provenDialler = libp2p(server(replica, true, null, false, List.of()));
        PeerId unproven = libp2p(server(replica, false, null, false, List.of()));
        long[] now = {1_000_000};
        Relays relays = new Relays(List.of(), () -> now[0], new Random(1));
        relays.start(RoutingLookup.full(replica), peer(key()));

        for (int i = 0; i < Relays.UNPROVEN_DIALS_PER_INITIATOR_PER_MINUTE; i++)
            Assert.assertTrue(relays.mayConnect(target, unproven));
        Assert.assertFalse("an unproven server gets a few dials", relays.mayConnect(target, unproven));
        int fresh = 0;
        while (relays.mayConnect(target, PeerId.random()))
            fresh++;
        Assert.assertEquals("fresh keys share what's left of the pool",
                Relays.UNPROVEN_DIALS_PER_MINUTE - Relays.UNPROVEN_DIALS_PER_INITIATOR_PER_MINUTE, fresh);
        Assert.assertTrue("a proven dialler is unaffected", relays.mayConnect(target, provenDialler));
        now[0] += 60_001;
        Assert.assertTrue(relays.mayConnect(target, PeerId.random()));
    }

    @Test
    public void aServerOnlyNowInTheReplicaIsProvenOnceTheRefusalLapses() {
        RoutingReplica replica = replica();
        long[] now = {1_000_000};
        Relays relays = new Relays(List.of(), () -> now[0], new Random(1));
        relays.start(RoutingLookup.full(replica), peer(key()));
        SigningKeyPair k = key();
        ServerInception inception = ServerInception.build(k, key().publicSigningKey, crypto.hasher).join()
                .withProof(new CuckooSolver(WORK).solve(k.publicSigningKey.serialize(), 0, 100_000).get().serialize());
        PeerId late = PeerId.fromBase58(inception.peerId().toBase58());
        Assert.assertFalse(relays.mayReserve(late));
        Assert.assertEquals(Outcome.Added, replica.add(Subject.server(inception.peerId()), inception));
        Assert.assertFalse("the refusal is cached for a minute", relays.mayReserve(late));
        now[0] += 60_001;
        Assert.assertTrue(relays.mayReserve(late));
    }

    @Test
    public void aServerIsDialledThroughTheRelaysItNames() {
        RoutingReplica replica = replica();
        SigningKeyPair relay = server(replica, true, "/ip4/198.51.100.1/tcp/4001", true, List.of());
        SigningKeyPair notRelaying = server(replica, true, "/ip4/198.51.100.2/tcp/4001", false, List.of());
        SigningKeyPair target = server(replica, true, null, false, List.of(peer(relay), peer(notRelaying)));
        ServerLog log = replica.server(Subject.server(peer(target))).get();
        Assert.assertEquals(List.of(new Multiaddr("/ip4/198.51.100.1/tcp/4001/p2p/" + peer(relay).toBase58()
                        + "/p2p-circuit/p2p/" + peer(target).toBase58())),
                Relays.relayed(replica, log));
        Assert.assertEquals("the address book has them", Relays.relayed(replica, log), Hints.logged(replica, libp2p(target)));
    }

    @Test
    public void aRelayIsNamedOnceHeldAWhileAndKeptThroughShortFailures() {
        RoutingReplica replica = replica();
        SigningKeyPair serverKey = key();
        ServerInception inception = ServerInception.build(serverKey, key().publicSigningKey, crypto.hasher).join();
        Subject self = Subject.server(inception.peerId());
        replica.add(self, inception);
        Multihash r1 = peer(key());
        List<Multihash> held = new ArrayList<>();
        long[] now = {1_000_000_000L};
        ServerAddressPublisher publisher = new ServerAddressPublisher(replica, serverKey, List.of(), List::of,
                ServerAddresses.Mode.Archival, () -> held, true, () -> now[0]);
        Assert.assertTrue(publisher.refresh().get().relays.isEmpty());
        held.add(r1);
        Assert.assertTrue(publisher.refresh().isEmpty());
        now[0] += ServerAddressPublisher.RELAY_STABLE_MILLIS;
        Assert.assertEquals(List.of(r1), publisher.refresh().get().relays);
        held.clear();
        now[0] += 3600_000L;
        Assert.assertTrue("kept through a short failure", publisher.refresh().isEmpty());
        now[0] += ServerAddressPublisher.RELAY_REPLACE_MILLIS;
        Assert.assertTrue("gone, but with nothing to replace it, it stays", publisher.refresh().isEmpty());
        Assert.assertEquals(List.of(r1), replica.server(self).get().addresses().get().relays);
        Assert.assertFalse("no direct address, so it doesn't relay", replica.server(self).get().addresses().get().relaying);
    }

    private static class Named {
        final RoutingReplica replica = replica();
        final SigningKeyPair serverKey = key();
        final List<Multihash> held = new ArrayList<>();
        final long[] now = {1_000_000_000L};
        final ServerAddressPublisher publisher;
        final List<SigningKeyPair> relays = new ArrayList<>();

        Named(int relayCount) {
            ServerInception inception = ServerInception.build(serverKey, key().publicSigningKey, crypto.hasher).join();
            replica.add(Subject.server(inception.peerId()), inception);
            for (int i = 0; i < relayCount; i++)
                relays.add(server(replica, true, "/ip4/198.51." + i + ".1/tcp/4001", true, List.of()));
            publisher = new ServerAddressPublisher(replica, serverKey, List.of(), List::of,
                    ServerAddresses.Mode.Archival, () -> held, true, () -> now[0]);
        }

        Multihash relay(int i) {
            return peer(relays.get(i)).bareMultihash();
        }

        void hold(int... indices) {
            held.clear();
            for (int i : indices)
                held.add(relay(i));
        }

        Optional<ServerAddresses> after(long millis) {
            now[0] += millis;
            return publisher.refresh();
        }

        List<Multihash> named() {
            return bare(replica.server(Subject.server(peer(serverKey))).get().addresses().get().relays);
        }
    }

    private static List<Multihash> bare(List<Multihash> ids) {
        return ids.stream().map(Multihash::bareMultihash).collect(Collectors.toList());
    }

    @Test
    public void restartsAndNewRelaysDontChangeTheRelaysNamed() {
        Named n = new Named(5);
        n.hold(3, 4);
        n.after(0);
        List<Multihash> named = bare(n.after(ServerAddressPublisher.RELAY_STABLE_MILLIS).get().relays);
        Assert.assertEquals(Set.of(n.relay(3), n.relay(4)), new HashSet<>(named));
        n.hold(0, 1, 2);
        Assert.assertTrue("others for an hour after a restart", n.after(3600_000L).isEmpty());
        n.hold(3, 4, 0);
        Assert.assertTrue("back on those named, with a spare", n.after(3600_000L).isEmpty());
        for (int i = 0; i < 10; i++)
            Assert.assertTrue(n.after(3600_000L).isEmpty());
        Assert.assertEquals(named, n.named());
    }

    @Test
    public void aGoneRelayIsReplacedInItsSlotAndNotUsedAgain() {
        Named n = new Named(4);
        n.hold(0, 1, 2);
        n.after(0);
        n.after(ServerAddressPublisher.RELAY_STABLE_MILLIS);
        List<Multihash> named = n.named();
        List<Multihash> all = n.relays.stream().map(k -> peer(k).bareMultihash()).collect(Collectors.toList());
        int gone = all.indexOf(named.get(0)), kept = all.indexOf(named.get(1));
        int spare = 3 - gone - kept;
        n.hold(kept, spare);
        Assert.assertTrue(n.after(ServerAddressPublisher.RELAY_REPLACE_MILLIS).isEmpty());
        Assert.assertEquals("replaced in its slot", List.of(n.relay(spare), n.relay(kept)), bare(n.after(60_000).get().relays));

        Relays relays = new Relays(List.of(n.relay(gone)), () -> n.now[0], new Random(1));
        relays.start(RoutingLookup.full(n.replica), peer(n.serverKey));
        List<PeerId> chosen = relays.candidates(null).stream().map(c -> c.id).collect(Collectors.toList());
        Assert.assertEquals("those named first, in order", List.of(libp2p(n.relays.get(spare)), libp2p(n.relays.get(kept))),
                chosen.subList(0, 2));
        Assert.assertFalse("the gone one isn't reserved on again, even if configured", chosen.contains(libp2p(n.relays.get(gone))));
        n.now[0] += ServerAddressPublisher.RELAY_FAILED_MILLIS + 1;
        Assert.assertTrue("after a week it may be", relays.candidates(null).stream().anyMatch(c -> c.id.equals(libp2p(n.relays.get(gone)))));
    }

    @Test
    public void aGoneRelayIsntReplacedWhileTheOtherIsMissing() {
        Named n = new Named(4);
        n.hold(0, 1);
        n.after(0);
        n.after(ServerAddressPublisher.RELAY_STABLE_MILLIS);
        List<Multihash> named = n.named();
        n.hold(2, 3);
        Assert.assertTrue("both missing, but not for long", n.after(ServerAddressPublisher.RELAY_REPLACE_MILLIS - 60_000).isEmpty());
        n.hold(2, 3, n.relays.stream().map(k -> peer(k).bareMultihash()).collect(Collectors.toList()).indexOf(named.get(1)));
        Assert.assertTrue(n.after(120_000).isEmpty());
        n.hold(2, 3);
        Assert.assertTrue("the first is gone, and the other missing a minute", n.after(60_000).isEmpty());
        List<Multihash> one = bare(n.after(ServerAddressPublisher.RELAY_REPLACE_MILLIS).get().relays);
        Assert.assertEquals("both gone, so one at a time", named.get(1), one.get(1));
        List<Multihash> two = bare(n.after(60_000).get().relays);
        Assert.assertEquals(Set.of(n.relay(2), n.relay(3)), new HashSet<>(two));
        Assert.assertEquals(one.get(0), two.get(0));
    }

    @Test
    public void aServerWithADirectAddressRelaysUnlessTheOperatorSaysNot() {
        for (boolean mayRelay : List.of(true, false)) {
            RoutingReplica replica = replica();
            SigningKeyPair serverKey = key();
            ServerInception inception = ServerInception.build(serverKey, key().publicSigningKey, crypto.hasher).join();
            replica.add(Subject.server(inception.peerId()), inception);
            ServerAddressPublisher publisher = new ServerAddressPublisher(replica, serverKey, List.of("/ip4/198.51.100.1/tcp/4001"),
                    List::of, ServerAddresses.Mode.Archival, () -> List.of(peer(key())), mayRelay, System::currentTimeMillis);
            ServerAddresses published = publisher.refresh().get();
            Assert.assertEquals(mayRelay, published.relaying);
            Assert.assertTrue("a server with a direct address names no relays", published.relays.isEmpty());
        }
    }

    @Test
    public void aServerWhoseAddressWasntAnnouncedNamesRelaysToo() {
        RoutingReplica replica = replica();
        SigningKeyPair serverKey = key();
        ServerInception inception = ServerInception.build(serverKey, key().publicSigningKey, crypto.hasher).join();
        replica.add(Subject.server(inception.peerId()), inception);
        Multihash relay = peer(key());
        long[] now = {1_000_000_000L};
        ServerAddressPublisher publisher = new ServerAddressPublisher(replica, serverKey, List.of(),
                () -> List.of("/ip4/203.0.113.7/tcp/4001"), ServerAddresses.Mode.Archival, () -> List.of(relay), true, () -> now[0]);
        publisher.refresh();
        now[0] += ServerAddressPublisher.STABLE_MILLIS;
        ServerAddresses published = publisher.refresh().get();
        Assert.assertEquals(ServerAddressPublisher.directAddresses(List.of("/ip4/203.0.113.7/tcp/4001")), published.addrs);
        Assert.assertEquals(List.of(relay), bare(published.relays));
        Assert.assertTrue(published.relaying);
    }

    @Test
    public void updatesBeyondWhatPeersTakeAreDeferred() {
        Named n = new Named(6);
        n.hold(0);
        Assert.assertTrue("the first record is free", n.after(0).isPresent());
        Assert.assertTrue(n.after(ServerAddressPublisher.RELAY_STABLE_MILLIS).isPresent());
        n.hold(0, 1, 2);
        Assert.assertTrue(n.after(0).isEmpty());
        Assert.assertTrue(n.after(ServerAddressPublisher.RELAY_STABLE_MILLIS).isPresent());
        int x = n.named().get(1).equals(n.relay(1)) ? 1 : 2, y = 3 - x;
        n.hold(x, y);
        Assert.assertEquals(List.of(n.relay(y), n.relay(x)),
                bare(n.after(ServerAddressPublisher.RELAY_REPLACE_MILLIS + 60_000).get().relays));
        n.hold(y, 3);
        Assert.assertTrue("a fourth update so soon is deferred", n.after(ServerAddressPublisher.RELAY_REPLACE_MILLIS + 60_000).isEmpty());
        Assert.assertEquals(List.of(n.relay(y), n.relay(x)), n.named());
        Assert.assertEquals(List.of(n.relay(y), n.relay(3)), bare(n.after(RateLimits.MONTH).get().relays));
    }

    @Test
    public void relayedBandwidthIsMeteredPerServerAndInTotal() {
        org.peergos.protocol.circuit.RelayBandwidth bw = new Relays(List.of(), false, 1_000_000, System::currentTimeMillis, new Random(1))
                .policy().bandwidth.get();
        Assert.assertEquals(Relays.PER_SERVER_BYTES_PER_SECOND, bw.perTargetBytesPerSecond);
        Assert.assertEquals(1_000_000, bw.totalBytesPerSecond);
        PeerId target = PeerId.random();
        Assert.assertEquals("a burst for setup and hole-punching", 0, bw.charge(target, Relays.PER_SERVER_BURST));
        Assert.assertTrue("then it is slowed", bw.charge(target, 100_000) >= 1_000);
    }

    @Test
    public void aServerStillComputingItsProofOfWorkGetsAShortLease() {
        RoutingReplica replica = replica();
        PeerId provenServer = libp2p(server(replica, true, null, false, List.of()));
        long[] now = {1_000_000};
        Relays relays = new Relays(List.of(), () -> now[0], new Random(1));
        relays.start(RoutingLookup.full(replica), peer(key()));
        Multiaddr range = new Multiaddr("/ip4/198.51.100.1/tcp/4001"), otherRange = new Multiaddr("/ip4/203.0.113.1/tcp/4001");

        Assert.assertEquals(Optional.of(Relays.RESERVATION), relays.reserve(provenServer, range));
        PeerId first = PeerId.random(), second = PeerId.random(), third = PeerId.random();
        Assert.assertEquals(Optional.of(Relays.LEASE), relays.reserve(first, range));
        Assert.assertEquals(Optional.of(Relays.LEASE), relays.reserve(second, range));
        Assert.assertEquals("a few per address bucket", Optional.empty(), relays.reserve(third, range));
        Assert.assertEquals(Optional.of(Relays.LEASE), relays.reserve(third, otherRange));
        Assert.assertEquals("renewing a lease isn't a new one", Optional.of(Relays.LEASE), relays.reserve(first, range));

        now[0] += Relays.LEASE.toMillis() + 1;
        Assert.assertEquals("an expired lease frees its place", Optional.of(Relays.LEASE), relays.reserve(PeerId.random(), range));
    }

    @Test
    public void aServerIsRecognisedByItsCurrentKeyAndEndpoint() {
        RoutingReplica replica = replica();
        long[] now = {1_000_000};
        Relays relays = new Relays(List.of(), () -> now[0], new Random(1));
        relays.start(RoutingLookup.full(replica), peer(key()));

        SigningKeyPair first = key(), second = key(), third = key(), endpoint = key(), later = key();
        ServerInception inception = ServerInception.build(first, second.publicSigningKey, crypto.hasher).join();
        inception = inception.withProof(new CuckooSolver(WORK).solve(inception.server.serialize(), 0, 100_000).get().serialize());
        Subject s = Subject.server(inception.peerId());
        Assert.assertEquals(Outcome.Added, replica.add(s, inception));
        Assert.assertEquals(Outcome.Added, replica.add(s, ServerRotation.build(first.publicSigningKey, second, third.publicSigningKey, crypto.hasher).join()));
        Assert.assertEquals(Outcome.Added, replica.add(s, ServerAddresses.build(second, endpoint.publicSigningKey, Optional.empty(),
                List.of(), List.of(), ServerAddresses.Mode.Archival, false, 1).join()));

        Assert.assertEquals("its current key", Optional.of(Relays.RESERVATION), relays.reserve(libp2p(second), new Multiaddr("/ip4/198.51.100.1/tcp/4001")));
        Assert.assertEquals("its endpoint", Optional.of(Relays.RESERVATION), relays.reserve(libp2p(endpoint), new Multiaddr("/ip4/198.51.100.2/tcp/4001")));
        Assert.assertEquals("a key it rotated away from only gets a lease", Optional.of(Relays.LEASE),
                relays.reserve(libp2p(first), new Multiaddr("/ip4/198.51.100.3/tcp/4001")));

        Assert.assertEquals(Outcome.Added, replica.add(s, ServerAddresses.build(second, later.publicSigningKey, Optional.empty(),
                List.of(), List.of(), ServerAddresses.Mode.Archival, false, 2).join()));
        now[0] += 10 * 60_000 + 1;
        Assert.assertTrue(replica.serverForPeer(peer(later)).isPresent());
        Assert.assertTrue("an endpoint it no longer names", replica.serverForPeer(peer(endpoint)).isEmpty());

        SigningKeyPair claimant = key();
        ServerInception unproven = ServerInception.build(claimant, key().publicSigningKey, crypto.hasher).join();
        replica.add(Subject.server(unproven.peerId()), unproven);
        replica.add(Subject.server(unproven.peerId()), ServerAddresses.build(claimant, second.publicSigningKey, Optional.empty(),
                List.of(), List.of(), ServerAddresses.Mode.Archival, false, 1).join());
        Assert.assertEquals("claiming another server's key as an endpoint doesn't take it over",
                Optional.of(s.id.bareMultihash()), replica.serverForPeer(peer(second)).map(l -> l.peerId.bareMultihash()));
    }
}
