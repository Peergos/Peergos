package peergos.server.tests;

import io.libp2p.core.*;
import io.libp2p.core.crypto.*;
import io.libp2p.crypto.keys.*;
import org.junit.*;
import peergos.server.*;
import peergos.server.routing.*;
import peergos.server.sql.*;
import peergos.server.storage.*;
import peergos.shared.*;
import peergos.shared.cbor.*;
import peergos.shared.corenode.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.*;
import peergos.shared.resolution.*;
import peergos.shared.routing.*;
import peergos.shared.storage.*;

import java.time.*;
import java.util.*;

public class LegacyImportTests {
    private static final Crypto crypto = Main.initCrypto();
    private static final CuckooCycle WORK = new CuckooCycle(16, 0);
    private static final ContentAddressedStorage KEYS = new RAMStorage(crypto.hasher);
    private static final Cid PKI_ROOT = Cid.buildCidV1(Cid.Codec.DagCbor, Multihash.Type.sha2_256, filled(0));

    private static byte[] filled(int b) {
        byte[] res = new byte[32];
        Arrays.fill(res, (byte) b);
        return res;
    }

    private static SigningKeyPair key() {
        return SigningKeyPair.random(crypto.random, crypto.signer);
    }

    private static SigningPrivateKeyAndPublicHash signer(SigningKeyPair pair) {
        return new SigningPrivateKeyAndPublicHash(ContentAddressedStorage.hashKey(pair.publicSigningKey), pair.secretSigningKey);
    }

    private static Multihash peerId() {
        return Multihash.decode(PeerId.fromPubKey(Ed25519Kt.generateEd25519KeyPair().getSecond()).getBytes());
    }

    /** Claims name providers as cids, as signup writes them. */
    private static Cid provider(Multihash peerId) {
        return new Cid(1, Cid.Codec.LibP2pKey, peerId.type, peerId.getHash());
    }

    private static List<UserPublicKeyLink> chain(String username, Multihash provider, SigningKeyPair... keys) {
        List<UserPublicKeyLink> res = new ArrayList<>(UserPublicKeyLink.createInitial(signer(keys[0]), username,
                LocalDate.now().plusYears(1), keys.length == 1 ? List.of(provider(provider)) : List.of()).join());
        for (int i = 1; i < keys.length; i++) {
            List<UserPublicKeyLink> step = UserPublicKeyLink.createChain(signer(keys[i - 1]), signer(keys[i]), username,
                    LocalDate.now().plusYears(1), i == keys.length - 1 ? List.of(provider(provider)) : List.of()).join();
            res.set(res.size() - 1, step.get(0));
            res.add(step.get(1));
        }
        return res;
    }

    private static RoutingReplica replica() {
        JdbcRoutingStore store = new JdbcRoutingStore(Builder.buildEphemeralSqlite(), new SqliteCommands());
        return new RoutingReplica(store, new RecordValidator(crypto.hasher, WORK, WORK), WORK, WORK);
    }

    private static byte[] raw(LegacyState state) {
        return state.serialize();
    }

    @Test
    public void validChainsBecomeLegacyIdentities() {
        Multihash provider = peerId();
        SigningKeyPair a = key(), b = key(), c = key();
        LegacyIdentity single = LegacyImportValidate.of("alice", chain("alice", provider, a)).get();
        Assert.assertEquals(List.of(ContentAddressedStorage.hashKey(a.publicSigningKey)), single.owners);
        Assert.assertEquals(List.of(provider), single.providers.stream().map(Multihash::bareMultihash).toList());

        LegacyIdentity rotated = LegacyImportValidate.of("bob", chain("bob", provider, a, b, c)).get();
        Assert.assertEquals(3, rotated.owners.size());
        Assert.assertEquals(c.publicSigningKey, rotated.currentKey);
        Assert.assertEquals(ContentAddressedStorage.hashKey(a.publicSigningKey), rotated.identity());
    }

    @Test
    public void invalidChainsAreSkipped() {
        Multihash provider = peerId();
        SigningKeyPair a = key(), b = key();
        Assert.assertTrue("another username", LegacyImportValidate.of("mallory", chain("alice", provider, a)).isEmpty());
        List<UserPublicKeyLink> spliced = new ArrayList<>(chain("alice", provider, a, b));
        spliced.set(1, chain("alice", provider, key()).get(0));
        Assert.assertTrue("a key change to another key", LegacyImportValidate.of("alice", spliced).isEmpty());
    }

    @Test
    public void importIsPinnedAndHappensOnce() {
        RoutingReplica replica = replica();
        Multihash provider = peerId();
        Map<String, List<UserPublicKeyLink>> chains = Map.of("alice", chain("alice", provider, key()));
        byte[] state = raw(new LegacyState(PKI_ROOT, List.of()));
        byte[] other = raw(new LegacyState(Cid.buildCidV1(Cid.Codec.DagCbor, Multihash.Type.sha2_256, filled(1)), List.of()));
        Cid pinned = LegacyImport.hash(state, crypto);

        assertFails(() -> LegacyImport.run(other, pinned, chains, KEYS, replica, crypto));
        LegacyImport.Result res = LegacyImport.run(state, pinned, chains, KEYS, replica, crypto).get();
        Assert.assertEquals(1, res.identities);
        Assert.assertTrue(LegacyImport.run(state, pinned, chains, KEYS, replica, crypto).isEmpty());
        assertFails(() -> LegacyImport.run(other, LegacyImport.hash(other, crypto), chains, KEYS, replica, crypto));
    }

    @Test
    public void aLegacyIdentityResolvesByAnyOwnerAndWritesItsFirstCommitment() throws Exception {
        JdbcRoutingStore store = new JdbcRoutingStore(Builder.buildEphemeralSqlite(), new SqliteCommands());
        SigningKeyPair serverKey = key();
        ServerRouting routing = ServerRouting.start(store, serverKey, Optional.empty(), crypto, WORK, WORK, 1, r -> {}, s -> {});
        RoutingReplica replica = routing.replica;
        Multihash oldProvider = peerId();
        SigningKeyPair a = key(), b = key(), next = key();
        Map<String, List<UserPublicKeyLink>> chains = Map.of("bob", chain("bob", oldProvider, a, b));
        byte[] state = raw(new LegacyState(PKI_ROOT, List.of()));
        LegacyImport.run(state, LegacyImport.hash(state, crypto), chains, KEYS, replica, crypto);

        Subject first = Subject.identity(ContentAddressedStorage.hashKey(a.publicSigningKey));
        Subject later = Subject.identity(ContentAddressedStorage.hashKey(b.publicSigningKey));
        Assert.assertEquals(first, replica.canonical(later));
        IdentityLog log = replica.identity(later).get();
        Assert.assertTrue(log.isLegacy());
        Assert.assertEquals(b.publicSigningKey, log.currentKey().get());
        Assert.assertEquals(List.of(oldProvider), log.providers().stream().map(Multihash::bareMultihash).toList());

        IdentityInception byOldKey = IdentityInception.build(a, List.of(routing.peerId), next.publicSigningKey, crypto.hasher).join();
        Assert.assertEquals(Outcome.Rejected, routing.admissions.submit(byOldKey));

        IdentityInception commitment = IdentityInception.build(b, List.of(routing.peerId), next.publicSigningKey, crypto.hasher).join();
        Assert.assertEquals(Outcome.Added, routing.admissions.submit(commitment));
        long end = System.currentTimeMillis() + 10_000;
        while (! replica.isProven(first, false) && System.currentTimeMillis() < end)
            Thread.sleep(20);
        Assert.assertTrue("admitted without proof of work", replica.isProven(first, false));
        IdentityInception held = (IdentityInception) replica.identity(first).get().inception().get().record;
        Assert.assertEquals(0, held.admission.get().proof.length);
        Assert.assertEquals(List.of(routing.peerId), replica.identity(first).get().providers());

        SigningKeyPair fresh = key();
        IdentityInception unproven = IdentityInception.build(fresh, List.of(routing.peerId), key().publicSigningKey, crypto.hasher).join();
        Subject freshSubject = Subject.identity(unproven.identityHash());
        Admission noWork = Admission.build(fresh.publicSigningKey, new byte[0], serverKey.secretSigningKey).join();
        replica.add(freshSubject, unproven.withAdmission(noWork));
        Assert.assertFalse("a new identity still needs proof of work", replica.isProven(freshSubject, false));
        routing.stop();
    }

    @Test
    public void serverRotationsAreFollowedAndPinned() {
        Multihash a = peerId(), b = peerId(), c = peerId(), d = peerId(), e = peerId(), f = peerId();
        PrivKey gPriv = Ed25519Kt.generateEd25519KeyPair().getFirst();
        Multihash g = Multihash.decode(PeerId.fromPubKey(gPriv.publicKey()).getBytes()), h = peerId(), lie = peerId();
        IpnsEntry gRecord = entry(ServerIdentity.generateSignedIpnsRecord(gPriv, Optional.of(h), true, 2));
        LegacyState state = new LegacyState(PKI_ROOT, List.of(
                new LegacyState.ServerRotation(b, c, Optional.empty()),
                new LegacyState.ServerRotation(a, b, Optional.empty()),
                new LegacyState.ServerRotation(d, e, Optional.empty()),
                new LegacyState.ServerRotation(d, f, Optional.empty()),
                new LegacyState.ServerRotation(g, lie, Optional.of(gRecord))));
        List<LegacyServer> servers = LegacyImport.servers(state, crypto);
        Assert.assertEquals(1, servers.size());
        Assert.assertEquals(List.of(a, b, c), servers.get(0).peerIds);

        RoutingReplica replica = replica();
        byte[] raw = raw(state);
        LegacyImport.run(raw, LegacyImport.hash(raw, crypto), Map.of(), KEYS, replica, crypto);
        Assert.assertEquals(Subject.server(a), replica.canonical(Subject.server(c)));
        Assert.assertTrue(replica.server(Subject.server(b)).get().legacy.isPresent());
    }

    @Test
    public void aRotatedLegacyServerWritesItsInceptionForItsCurrentKey() {
        RoutingReplica replica = replica();
        Multihash old = peerId();
        SigningKeyPair current = key();
        Multihash currentId = ServerKeys.peerId(current.publicSigningKey);
        byte[] raw = raw(new LegacyState(PKI_ROOT, List.of(new LegacyState.ServerRotation(old, currentId, Optional.empty()))));
        LegacyImport.run(raw, LegacyImport.hash(raw, crypto), Map.of(), KEYS, replica, crypto);

        ServerInception inception = ServerInception.build(current, key().publicSigningKey, crypto.hasher).join();
        Assert.assertEquals(Outcome.Added, replica.add(Subject.server(currentId), inception));
        ServerLog log = replica.server(Subject.server(old)).get();
        Assert.assertEquals(current.publicSigningKey, log.currentKey().get());

        SigningKeyPair impostor = key();
        RoutingReplica fresh = replica();
        LegacyImport.run(raw, LegacyImport.hash(raw, crypto), Map.of(), KEYS, fresh, crypto);
        Assert.assertEquals(Outcome.Rejected, fresh.add(Subject.server(old),
                ServerInception.build(impostor, key().publicSigningKey, crypto.hasher).join()));
    }

    @Test
    public void aServerThatRotatedByIpnsWritesItsInceptionUnderItsFirstPeerId() {
        JdbcRoutingStore store = new JdbcRoutingStore(Builder.buildEphemeralSqlite(), new SqliteCommands());
        Multihash old = peerId();
        SigningKeyPair current = key();
        Multihash currentId = ServerKeys.peerId(current.publicSigningKey);
        byte[] raw = raw(new LegacyState(PKI_ROOT, List.of(new LegacyState.ServerRotation(old, currentId, Optional.empty()))));
        ServerRouting routing = ServerRouting.start(store, current, Optional.empty(), crypto, WORK, WORK, 1,
                r -> LegacyImport.run(raw, LegacyImport.hash(raw, crypto), Map.of(), KEYS, r, crypto), s -> {});
        Assert.assertTrue(routing.replica.log(Subject.server(old)).inception().isPresent());
        Assert.assertEquals(1, store.count(Subject.SERVER));
        Assert.assertEquals(Subject.server(old), store.states(Subject.SERVER, new byte[0], 10).get(0).subject);
        routing.stop();
    }

    @Test
    public void exportFollowsRotationsOfProviders() {
        PrivKey aPriv = Ed25519Kt.generateEd25519KeyPair().getFirst();
        Multihash a = Multihash.decode(PeerId.fromPubKey(aPriv.publicKey()).getBytes()), b = peerId();
        Map<String, List<UserPublicKeyLink>> chains = Map.of("alice", chain("alice", a, key()));
        IpnsEntry record = entry(ServerIdentity.generateSignedIpnsRecord(aPriv, Optional.of(b), true, 2));
        LegacyState state = LegacyExport.build(PKI_ROOT, chains, p -> p.equals(a) ? Optional.of(record) : Optional.empty());
        Assert.assertEquals(1, state.servers.size());
        Assert.assertEquals(b, state.servers.get(0).to);
        LegacyState parsed = LegacyState.fromCbor(CborObject.fromByteArray(state.serialize()));
        Assert.assertEquals(List.of(new LegacyServer(List.of(a, b)).peerIds), LegacyImport.servers(parsed, crypto).stream().map(s -> s.peerIds).toList());
    }

    private static void assertFails(Runnable r) {
        try {
            r.run();
        } catch (IllegalStateException e) {
            return;
        }
        Assert.fail("expected a failure");
    }

    private static IpnsEntry entry(byte[] signedRecord) {
        try {
            org.peergos.protocol.ipns.pb.Ipns.IpnsEntry e = org.peergos.protocol.ipns.pb.Ipns.IpnsEntry.parseFrom(signedRecord);
            return new IpnsEntry(e.getSignatureV2().toByteArray(), e.getData().toByteArray());
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    private static class LegacyImportValidate {
        static Optional<LegacyIdentity> of(String username, List<UserPublicKeyLink> chain) {
            return LegacyImport.validate(username, chain, KEYS);
        }
    }
}
