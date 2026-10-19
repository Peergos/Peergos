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

        PeerId target = libp2p(proven), dialler = PeerId.random();
        for (int i = 0; i < Relays.DIALS_PER_INITIATOR_PER_MINUTE; i++)
            Assert.assertTrue(relays.mayConnect(target, dialler));
        Assert.assertFalse(relays.mayConnect(target, dialler));
        Assert.assertTrue("another dialler", relays.mayConnect(target, PeerId.random()));
        now[0] += 60_001;
        Assert.assertTrue(relays.mayConnect(target, dialler));
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
        now[0] += ServerAddressPublisher.RELAY_FORGET_MILLIS;
        Assert.assertTrue(publisher.refresh().get().relays.isEmpty());
        Assert.assertFalse("no direct address, so it doesn't relay", replica.server(self).get().addresses().get().relaying);
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
}
