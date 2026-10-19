package peergos.server.tests;

import io.libp2p.core.*;
import io.libp2p.core.crypto.*;
import io.libp2p.crypto.keys.*;
import org.junit.*;
import peergos.server.*;
import peergos.server.routing.*;
import peergos.server.sql.*;
import peergos.server.storage.*;
import peergos.server.util.*;
import peergos.shared.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.*;
import peergos.shared.routing.*;
import peergos.shared.storage.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

public class RoutingReplicaTests {
    private static final Crypto crypto = Main.initCrypto();
    private static final CuckooCycle WORK = new CuckooCycle(16, 0);

    private static JdbcRoutingStore store() {
        return new JdbcRoutingStore(Builder.buildEphemeralSqlite(), new SqliteCommands());
    }

    private static RoutingReplica replica(JdbcRoutingStore store) {
        return new RoutingReplica(store, new RecordValidator(crypto.hasher, WORK, WORK), WORK, WORK);
    }

    private static SigningKeyPair key() {
        return SigningKeyPair.random(crypto.random, crypto.signer);
    }

    private static void waitFor(Callable<Boolean> condition) throws Exception {
        long end = System.currentTimeMillis() + 60_000;
        while (! condition.call()) {
            if (System.currentTimeMillis() > end)
                throw new TimeoutException();
            Thread.sleep(50);
        }
    }

    @Test
    public void stateSurvivesARestart() {
        JdbcRoutingStore store = store();
        RoutingReplica replica = replica(store);
        List<SigningKeyPair> keys = List.of(key(), key(), key());
        Multihash server = ServerKeys.peerId(key().publicSigningKey);
        IdentityInception inception = IdentityInception.build(keys.get(0), List.of(server), keys.get(1).publicSigningKey, crypto.hasher).join();
        Subject s = Subject.identity(inception.identityHash());
        IdentityRotation rotation = IdentityRotation.build(keys.get(0).publicSigningKey, keys.get(1), List.of(server), keys.get(2).publicSigningKey, crypto.hasher).join();
        IdentityMove move = IdentityMove.build(keys.get(1), List.of(server), 1).join();

        Assert.assertEquals(Outcome.Unconnected, replica.add(s, rotation));
        Assert.assertEquals(Outcome.Added, replica.add(s, inception));
        Assert.assertEquals(Outcome.Added, replica.add(s, rotation));
        Assert.assertEquals(Outcome.Added, replica.add(s, move));
        Assert.assertEquals(Outcome.Duplicate, replica.add(s, move));
        Assert.assertEquals(Outcome.Rejected, replica.add(Subject.server(server), move));

        RoutingReplica restarted = replica(store);
        IdentityLog log = restarted.identity(s).get();
        Assert.assertEquals(keys.get(1).publicSigningKey, log.currentKey().get());
        Assert.assertEquals(List.of(server), log.providers());
        Assert.assertArrayEquals(replica.stateHash(s), restarted.stateHash(s));
        Assert.assertEquals(1, store.count(Subject.IDENTITY));
        Assert.assertEquals(1, store.states(Subject.IDENTITY, new byte[0], 10).size());
    }

    @Test
    public void aBetterProofReplacesTheInceptionHeld() {
        RoutingReplica replica = replica(store());
        SigningKeyPair server = key();
        ServerInception inception = ServerInception.build(server, key().publicSigningKey, crypto.hasher).join();
        Subject s = Subject.server(inception.peerId());
        Assert.assertEquals(Outcome.Added, replica.add(s, inception));
        Assert.assertFalse(replica.isProven(s, false));

        CuckooCycle.Proof proof = new CuckooSolver(WORK).solve(inception.server.serialize(), 0, 100_000).get();
        ServerInception proven = inception.withProof(proof.serialize());
        Assert.assertEquals(Outcome.Added, replica.add(s, proven));
        Assert.assertTrue(replica.isProven(s, false));
        Assert.assertEquals(Outcome.Duplicate, replica.add(s, inception));
        Assert.assertTrue(replica.isProven(s, false));

        ServerInception invalid = inception.withProof(new CuckooSolver(WORK).solve("other".getBytes(), 0, 100_000).get().serialize());
        Assert.assertEquals(Outcome.Duplicate, replica.add(s, invalid));
        Assert.assertTrue(replica.isProven(s, false));
    }

    @Test
    public void theServerProvesItselfAndAdmitsItsSignups() throws Exception {
        JdbcRoutingStore store = store();
        SigningKeyPair serverKey = key();
        List<Subject> proven = new CopyOnWriteArrayList<>();
        ServerRouting routing = ServerRouting.start(store, serverKey, Optional.empty(), crypto, WORK, WORK, 2, r -> {}, proven::add);
        Subject self = Subject.server(routing.peerId);
        Assert.assertTrue(store.nextKey(routing.peerId).isPresent());

        List<SigningKeyPair> keys = List.of(key(), key());
        IdentityInception signup = IdentityInception.build(keys.get(0), List.of(routing.peerId), keys.get(1).publicSigningKey, crypto.hasher).join();
        Subject user = Subject.identity(signup.identityHash());
        Assert.assertEquals(Outcome.Added, routing.admissions.submit(signup));
        Assert.assertTrue("usable at once", routing.replica.identity(user).isPresent());

        IdentityInception elsewhere = IdentityInception.build(key(), List.of(ServerKeys.peerId(key().publicSigningKey)),
                key().publicSigningKey, crypto.hasher).join();
        Assert.assertEquals(Outcome.Rejected, routing.admissions.submit(elsewhere));

        waitFor(() -> proven.contains(self) && proven.contains(user));
        Assert.assertTrue(routing.replica.isProven(self, false));
        Assert.assertTrue(routing.replica.isProven(user, false));
        Assert.assertTrue(store.pending().isEmpty());
        routing.stop();

        ServerRouting restarted = ServerRouting.start(store, serverKey, Optional.empty(), crypto, WORK, WORK, 2, r -> {}, s -> {});
        Assert.assertArrayEquals(routing.replica.stateHash(self), restarted.replica.stateHash(self));
        Assert.assertTrue(store.pending().isEmpty());
        restarted.stop();
    }

    @Test
    public void pendingWorkResumesAfterARestart() throws Exception {
        JdbcRoutingStore store = store();
        SigningKeyPair serverKey = key();
        RoutingReplica replica = replica(store);
        Multihash peerId = ServerKeys.peerId(serverKey.publicSigningKey);
        IdentityInception signup = IdentityInception.build(key(), List.of(peerId), key().publicSigningKey, crypto.hasher).join();
        Subject user = Subject.identity(signup.identityHash());
        replica.add(user, signup);
        store.addPending(user);

        List<Subject> proven = new CopyOnWriteArrayList<>();
        ServerRouting routing = ServerRouting.start(store, serverKey, Optional.empty(), crypto, WORK, WORK, 2, r -> {}, proven::add);
        waitFor(() -> proven.contains(user));
        Assert.assertTrue(routing.replica.isProven(user, false));
        routing.stop();
    }

    @Test
    public void serverKeysMatchLibp2pPeerIds() {
        PrivKey priv = Ed25519Kt.generateEd25519KeyPair().getFirst();
        SigningKeyPair pair = ServerSigningKeys.fromLibp2p(priv, crypto.signer);
        Multihash peerId = ServerKeys.peerId(pair.publicSigningKey);
        Assert.assertArrayEquals(PeerId.fromPubKey(priv.publicKey()).getBytes(), peerId.toBytes());
        Assert.assertEquals(pair.publicSigningKey, ServerSigningKeys.fromPeerId(peerId, crypto.signer).get());
        Assert.assertEquals(pair, ServerSigningKeys.fromSeed(ServerSigningKeys.seed(pair), crypto.signer));
    }

    @Test
    public void theInceptionCommitsToTheGenNextIdentity() {
        JdbcServerIdentityStore ids = JdbcServerIdentityStore.build(Builder.buildEphemeralSqlite(), new SqliteCommands(), crypto);
        PrivKey current = Ed25519Kt.generateEd25519KeyPair().getFirst();
        PeerId currentId = PeerId.fromPubKey(current.publicKey());
        ids.addIdentity(currentId, ServerIdentity.generateSignedIpnsRecord(current, Optional.empty(), false, 1));
        Assert.assertTrue(ServerRouting.committedNext(ids, currentId, crypto).isEmpty());

        PrivKey next = Ed25519Kt.generateEd25519KeyPair().getFirst();
        PeerId nextId = PeerId.fromPubKey(next.publicKey());
        ids.setRecord(currentId, ServerIdentity.generateSignedIpnsRecord(current, Optional.of(Multihash.decode(nextId.getBytes())), false, 2));
        SigningKeyPair nextPair = ServerSigningKeys.fromLibp2p(next, crypto.signer);
        Assert.assertEquals(nextPair.publicSigningKey, ServerRouting.committedNext(ids, currentId, crypto).get());

        JdbcRoutingStore store = store();
        ServerRouting routing = ServerRouting.start(store, ServerSigningKeys.fromLibp2p(current, crypto.signer),
                ServerRouting.committedNext(ids, currentId, crypto), crypto, WORK, WORK, 1, r -> {}, s -> {});
        ServerLog log = routing.replica.server(Subject.server(routing.peerId)).get();
        Assert.assertArrayEquals(crypto.hasher.sha256(nextPair.publicSigningKey.serialize()).join(),
                log.records.chain.currentEstablishment().get().next());
        Assert.assertTrue(store.nextKey(routing.peerId).isEmpty());
        routing.stop();
    }

    @Test
    public void aPubliclyKnownNextIdentityIsNeverCommittedTo() {
        JdbcServerIdentityStore ids = JdbcServerIdentityStore.build(Builder.buildEphemeralSqlite(), new SqliteCommands(), crypto);
        PrivKey current = Ed25519Kt.generateEd25519KeyPair().getFirst();
        PeerId currentId = PeerId.fromPubKey(current.publicKey());
        PeerId broken = PeerId.fromBase58("12D3KooWFeNXWhKkHecwJZGZEanQbniMs57XhJMvMhbXZkN4hPmF");
        ids.addIdentity(currentId, ServerIdentity.generateSignedIpnsRecord(current, Optional.of(Multihash.decode(broken.getBytes())), false, 1));
        Assert.assertTrue(ServerRouting.committedNext(ids, currentId, crypto).isEmpty());
    }

    @Test
    public void aClientCommitsToTheKeyItsRecoveryKeyDerives() throws Exception {
        JdbcRoutingStore store = store();
        SigningKeyPair serverKey = key();
        ServerRouting routing = ServerRouting.start(store, serverKey, Optional.empty(), crypto, WORK, WORK, 1, r -> {}, s -> {});
        try {
            SigningKeyPair identity = key();
            IdentityInception probe = IdentityInception.build(identity, List.of(routing.peerId), key().publicSigningKey, crypto.hasher).join();
            SigningKeyPair next = RecoveryKey.derive("Abandon-Ability-Able ", probe.identityHash(), crypto).join().nextIdentity;
            Assert.assertEquals(next.publicSigningKey,
                    RecoveryKey.derive("abandon-ability-able", probe.identityHash(), crypto).join().nextIdentity.publicSigningKey);
            Assert.assertNotEquals(next.publicSigningKey,
                    RecoveryKey.derive("abandon-ability-able", ContentAddressedStorage.hashKey(key().publicSigningKey), crypto).join().nextIdentity.publicSigningKey);

            IdentityInception inception = IdentityInception.build(identity, List.of(routing.peerId), next.publicSigningKey, crypto.hasher).join();
            Assert.assertEquals(Outcome.Added, routing.admissions.submit(inception));
            Assert.assertEquals(Outcome.Duplicate, routing.admissions.submit(inception));
            Assert.assertEquals(Outcome.Rejected, routing.admissions.submit(probe));
            Subject s = Subject.identity(inception.identityHash());
            Assert.assertFalse(routing.replica.identity(s).get().hasDuplicityEvidence());
            waitFor(() -> routing.replica.isProven(s, false));
        } finally {
            routing.stop();
        }
    }

    @Test
    public void onlyPublicIpLiteralsArePublished() {
        List<Address> addrs = ServerAddressPublisher.directAddresses(List.of(
                "/ip4/203.0.113.7/tcp/4001/p2p/12D3KooWFeNXWhKkHecwJZGZEanQbniMs57XhJMvMhbXZkN4hPmF",
                "/ip4/203.0.113.7/tcp/4001",
                "/ip6/2001:db8::7/tcp/4001",
                "/dns4/peergos.net/tcp/4001",
                "/ip4/example.com/tcp/4001",
                "/ip4/192.168.1.2/tcp/4001",
                "/ip4/10.0.0.2/tcp/4001",
                "/ip4/100.64.0.1/tcp/4001",
                "/ip4/127.0.0.1/tcp/4001",
                "/ip4/0.0.0.0/tcp/4001",
                "/ip6/::1/tcp/4001",
                "/ip6/fd00::1/tcp/4001",
                "/ip4/203.0.113.8/tcp/4001/p2p/12D3KooWFeNXWhKkHecwJZGZEanQbniMs57XhJMvMhbXZkN4hPmF/p2p-circuit"));
        Assert.assertEquals(List.of(
                new Address(Address.LIBP2P, new MultiAddress("/ip4/203.0.113.7/tcp/4001").getBytes()),
                new Address(Address.LIBP2P, new MultiAddress("/ip6/2001:db8::7/tcp/4001").getBytes())), addrs);
    }

    @Test
    public void serverAddressesArePublishedOnlyWhenTheyChange() {
        JdbcRoutingStore store = store();
        SigningKeyPair serverKey = key();
        ServerRouting routing = ServerRouting.start(store, serverKey, Optional.empty(), crypto, WORK, WORK, 1, r -> {}, s -> {});
        try {
            List<Address> addrs = direct("/ip4/203.0.113.7/tcp/4001");
            ServerAddresses first = ServerAddressPublisher.publish(routing.replica, serverKey, addrs,
                    ServerAddresses.Mode.Archival, () -> 1000).get();
            Assert.assertEquals(1000, first.counter);
            Assert.assertEquals(serverKey.publicSigningKey, first.endpoint);
            Assert.assertTrue(ServerAddressPublisher.publish(routing.replica, serverKey, addrs,
                    ServerAddresses.Mode.Archival, () -> 2000).isEmpty());
            Assert.assertTrue(ServerAddressPublisher.publish(replica(store), serverKey, addrs,
                    ServerAddresses.Mode.Archival, () -> 3000).isEmpty());

            ServerAddresses moved = ServerAddressPublisher.publish(routing.replica, serverKey,
                    direct("/ip4/203.0.113.9/tcp/4001"), ServerAddresses.Mode.Archival, () -> 10).get();
            Assert.assertEquals(1001, moved.counter);
            Subject self = Subject.server(routing.peerId);
            Assert.assertEquals(moved, routing.replica.server(self).get().addresses().get());
        } finally {
            routing.stop();
        }
    }

    @Test
    public void confirmedAddressesAreListedOnceStableForAMonthAndKeptUntilReplaced() {
        JdbcRoutingStore store = store();
        SigningKeyPair serverKey = key();
        ServerRouting routing = ServerRouting.start(store, serverKey, Optional.empty(), crypto, WORK, WORK, 1, r -> {}, s -> {});
        try {
            long day = 24 * 3600_000L;
            long[] now = {day};
            List<String> confirmed = new ArrayList<>(List.of("/ip4/203.0.113.7/tcp/4001"));
            Supplier<ServerAddressPublisher> publisher = () -> new ServerAddressPublisher(routing.replica, serverKey,
                    List.of("/ip4/198.51.100.1/tcp/4001"), () -> confirmed, ServerAddresses.Mode.Archival, () -> now[0]);
            List<Address> announced = direct("/ip4/198.51.100.1/tcp/4001");
            List<Address> first = new ArrayList<>(announced);
            first.addAll(direct("/ip4/203.0.113.7/tcp/4001"));
            List<Address> second = new ArrayList<>(announced);
            second.addAll(direct("/ip4/203.0.113.8/tcp/4001"));

            Assert.assertEquals(announced, publisher.get().refresh().get().addrs);
            now[0] += 29 * day;
            Assert.assertTrue(publisher.get().refresh().isEmpty());
            now[0] += day;
            Assert.assertEquals(first, publisher.get().refresh().get().addrs);

            confirmed.set(0, "/ip4/203.0.113.8/tcp/4001");
            now[0] += day;
            Assert.assertTrue("the old address stays while the new one qualifies", publisher.get().refresh().isEmpty());
            now[0] += 29 * day + day / 2;
            Assert.assertTrue("even once a month unconfirmed, while the new one is still on its way", publisher.get().refresh().isEmpty());
            now[0] += day;
            Assert.assertEquals("replaced in one update", second, publisher.get().refresh().get().addrs);

            confirmed.clear();
            now[0] += 29 * day;
            Assert.assertTrue(publisher.get().refresh().isEmpty());
            now[0] += 2 * day;
            Assert.assertEquals("gone for a month with nothing to replace it", announced, publisher.get().refresh().get().addrs);
        } finally {
            routing.stop();
        }
    }

    @Test
    public void providersComeFromTheLogOnceTheIdentityCommits() {
        RoutingReplica replica = replica(store());
        SigningKeyPair a = key(), endpoint = key(), b = key();
        ServerInception serverA = ServerInception.build(a, key().publicSigningKey, crypto.hasher).join();
        Multihash peerA = serverA.peerId(), peerB = ServerKeys.peerId(b.publicSigningKey);
        Assert.assertEquals(Outcome.Added, replica.add(Subject.server(peerA), serverA));
        Assert.assertEquals(Optional.of(peer(peerA)), RoutedCoreNode.currentPeerId(RoutingLookup.full(replica), peerA));

        SigningKeyPair first = key();
        IdentityInception inception = IdentityInception.build(first, List.of(peerA), key().publicSigningKey, crypto.hasher).join();
        PublicKeyHash owner = inception.identityHash();
        Assert.assertEquals("the PKI answers until the identity commits", Optional.empty(), RoutedCoreNode.providers(RoutingLookup.full(replica), owner));
        Assert.assertEquals(Outcome.Added, replica.add(Subject.identity(owner), inception));
        Assert.assertEquals(Optional.of(List.of(peer(peerA))), RoutedCoreNode.providers(RoutingLookup.full(replica), owner));

        ServerAddresses addrs = ServerAddresses.build(a, endpoint.publicSigningKey, Optional.empty(), direct("/ip4/198.51.100.1/tcp/4001"),
                List.of(), ServerAddresses.Mode.Archival, false, 1).join();
        Assert.assertEquals(Outcome.Added, replica.add(Subject.server(peerA), addrs));
        Multihash dialled = peer(ServerKeys.peerId(endpoint.publicSigningKey));
        Assert.assertEquals("a server is dialled on its current endpoint", Optional.of(List.of(dialled)), RoutedCoreNode.providers(RoutingLookup.full(replica), owner));
        Assert.assertEquals(Optional.of(dialled), RoutedCoreNode.currentPeerId(RoutingLookup.full(replica), peerA));

        IdentityMove move = IdentityMove.build(first, List.of(peerB), 1).join();
        Assert.assertEquals(Outcome.Added, replica.add(Subject.identity(owner), move));
        Assert.assertEquals("a server not in the log is named as the move names it",
                Optional.of(List.of(peer(peerB))), RoutedCoreNode.providers(RoutingLookup.full(replica), owner));
    }

    private static Multihash peer(Multihash m) {
        return new Cid(1, Cid.Codec.LibP2pKey, m.type, m.getHash());
    }

    private static List<Address> direct(String addr) {
        return ServerAddressPublisher.directAddresses(List.of(addr));
    }
}
