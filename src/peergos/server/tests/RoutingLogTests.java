package peergos.server.tests;

import org.junit.*;
import peergos.server.*;
import peergos.shared.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.io.ipfs.*;
import peergos.shared.routing.*;
import peergos.shared.storage.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

public class RoutingLogTests {
    private static final Crypto crypto = Main.initCrypto();
    private static final byte[] GOOD_PROOF = {1, 2, 3};
    private static final Work TEST_WORK = (subject, proof) -> CompletableFuture.completedFuture(Arrays.equals(proof, GOOD_PROOF));
    private static final RecordValidator validator = new RecordValidator(crypto.hasher, TEST_WORK, TEST_WORK);

    private static SigningKeyPair key() {
        return SigningKeyPair.random(crypto.random, crypto.signer);
    }

    private static Multihash server() {
        return ServerKeys.peerId(key().publicSigningKey);
    }

    private static <R extends SignedRecord> Verified<R> verified(R r) {
        return validator.verify(r).join();
    }

    private static boolean valid(SignedRecord r) {
        try {
            validator.verify(r).join();
            return true;
        } catch (CompletionException e) {
            return false;
        }
    }

    /** Offers every record, then offers the unconnected ones again until nothing changes, as a replica receiving
     *  them in this order and retrying would. */
    private static IdentityLog apply(IdentityLog log, List<Verified<? extends SignedRecord>> records) {
        List<Verified<? extends SignedRecord>> pending = new ArrayList<>(records);
        boolean progress = true;
        while (progress && ! pending.isEmpty()) {
            progress = false;
            List<Verified<? extends SignedRecord>> retry = new ArrayList<>();
            for (Verified<? extends SignedRecord> v : pending) {
                RecordLog.Change<IdentityLog> c = log.add(v);
                if (c.outcome == Outcome.Unconnected)
                    retry.add(v);
                else
                    progress = true;
                log = c.log;
            }
            pending = retry;
        }
        return log;
    }

    private static List<byte[]> hashes(IdentityLog log) {
        return log.records.records().stream().map(v -> v.hash).collect(Collectors.toList());
    }

    private static void assertSameRecords(IdentityLog a, IdentityLog b) {
        List<byte[]> x = hashes(a), y = hashes(b);
        Assert.assertEquals(x.size(), y.size());
        for (int i = 0; i < x.size(); i++)
            Assert.assertArrayEquals(x.get(i), y.get(i));
    }

    private static class Identity {
        final List<SigningKeyPair> keys = new ArrayList<>();

        Identity(int n) {
            for (int i = 0; i < n; i++)
                keys.add(key());
        }

        IdentityInception inception(List<Multihash> providers) {
            return IdentityInception.build(keys.get(0), providers, keys.get(1).publicSigningKey, crypto.hasher).join();
        }

        IdentityRotation rotation(int to, List<Multihash> providers) {
            return IdentityRotation.build(keys.get(to - 1).publicSigningKey, keys.get(to), providers,
                    keys.get(to + 1).publicSigningKey, crypto.hasher).join();
        }

        IdentityMove move(int key, List<Multihash> providers, long counter) {
            return IdentityMove.build(keys.get(key), providers, counter).join();
        }

        IdentityLog empty() {
            return IdentityLog.empty(ContentAddressedStorage.hashKey(keys.get(0).publicSigningKey));
        }
    }

    @Test
    public void rotationsAndMovesGiveTheCurrentState() {
        Identity id = new Identity(4);
        Multihash a = server(), b = server(), c = server(), d = server();
        IdentityLog log = apply(id.empty(), Arrays.asList(
                verified(id.inception(Arrays.asList(a))),
                verified(id.rotation(1, Arrays.asList(b))),
                verified(id.move(1, Arrays.asList(c), 1))));
        Assert.assertEquals(id.keys.get(1).publicSigningKey, log.currentKey().get());
        Assert.assertEquals(Arrays.asList(c), log.providers());

        RecordLog.Change<IdentityLog> old = log.add(verified(id.move(0, Arrays.asList(d), 99)));
        Assert.assertEquals("a move by a rotated-away key is ignored", Outcome.Ignored, old.outcome);
        Assert.assertEquals(Arrays.asList(c), old.log.providers());

        IdentityLog rotated = log.add(verified(id.rotation(2, Arrays.asList(d)))).log;
        Assert.assertEquals("a rotation supersedes the old key's move", Arrays.asList(d), rotated.providers());
        Assert.assertFalse(rotated.isFrozen());
    }

    @Test
    public void stateIsIndependentOfArrivalOrder() {
        Identity id = new Identity(5);
        Verified<IdentityInception> inception = verified(id.inception(Arrays.asList(server())));
        List<Verified<? extends SignedRecord>> rest = new ArrayList<>(Arrays.asList(
                verified(id.rotation(1, Arrays.asList(server()))),
                verified(id.rotation(2, Arrays.asList(server()))),
                verified(id.rotation(3, Arrays.asList(server()))),
                verified(id.move(1, Arrays.asList(server()), 7)),
                verified(id.move(3, Arrays.asList(server()), 1)),
                verified(id.move(3, Arrays.asList(server()), 2)),
                verified(id.move(3, Arrays.asList(server()), 2)),
                verified(id.inception(Arrays.asList(server(), server())))));
        IdentityLog reference = null;
        Random rnd = new Random(1);
        for (int i = 0; i < 200; i++) {
            Collections.shuffle(rest, rnd);
            List<Verified<? extends SignedRecord>> order = new ArrayList<>();
            order.add(inception);
            order.addAll(rest);
            IdentityLog log = apply(id.empty(), order);
            Assert.assertEquals(id.keys.get(3).publicSigningKey, log.currentKey().get());
            if (reference == null)
                reference = log;
            else {
                assertSameRecords(reference, log);
                Assert.assertEquals(reference.providers(), log.providers());
            }
        }
        Assert.assertEquals(2, reference.records.update.get().record.counter);
    }

    @Test
    public void equalCountersResolveToTheLowerHash() {
        Identity id = new Identity(2);
        Verified<IdentityInception> inception = verified(id.inception(Arrays.asList(server())));
        Verified<IdentityMove> x = verified(id.move(0, Arrays.asList(server()), 3));
        Verified<IdentityMove> y = verified(id.move(0, Arrays.asList(server()), 3));
        Verified<IdentityMove> lower = Verified.byHash(x, y) < 0 ? x : y;
        IdentityLog one = apply(id.empty(), Arrays.asList(inception, x, y));
        IdentityLog two = apply(id.empty(), Arrays.asList(inception, y, x));
        Assert.assertEquals(lower, one.records.update.get());
        Assert.assertEquals(lower, two.records.update.get());
    }

    @Test
    public void rotationToAnUncommittedKeyIsRejected() {
        Identity id = new Identity(3);
        IdentityLog log = apply(id.empty(), Arrays.asList(verified(id.inception(Arrays.asList(server())))));
        SigningKeyPair thief = key();
        IdentityRotation forged = IdentityRotation.build(id.keys.get(0).publicSigningKey, thief, Arrays.asList(server()),
                key().publicSigningKey, crypto.hasher).join();
        Assert.assertEquals(Outcome.Rejected, log.add(verified(forged)).outcome);
    }

    @Test
    public void conflictingRotationsFreezeAndKeepTwo() {
        Identity id = new Identity(3);
        Verified<IdentityInception> inception = verified(id.inception(Arrays.asList(server())));
        List<Verified<? extends SignedRecord>> conflicts = new ArrayList<>();
        for (int i = 0; i < 30; i++)
            conflicts.add(verified(id.rotation(1, Arrays.asList(server()))));
        Verified<IdentityMove> move = verified(id.move(1, Arrays.asList(server()), 1));
        List<byte[]> lowest = conflicts.stream()
                .sorted(Verified::byHash)
                .limit(RecordLog.MAX_ROTATIONS_PER_KEY)
                .map(v -> v.hash)
                .collect(Collectors.toList());
        Random rnd = new Random(2);
        for (int i = 0; i < 20; i++) {
            Collections.shuffle(conflicts, rnd);
            List<Verified<? extends SignedRecord>> order = new ArrayList<>();
            order.add(inception);
            order.addAll(conflicts);
            order.add(move);
            IdentityLog log = apply(id.empty(), order);
            Assert.assertTrue(log.isFrozen());
            Assert.assertTrue("moves are ignored once frozen", log.records.update.isEmpty());
            List<byte[]> kept = log.records.rotationsFrom(id.keys.get(0).publicSigningKey).stream()
                    .map(v -> v.hash)
                    .collect(Collectors.toList());
            Assert.assertEquals(lowest.size(), kept.size());
            for (int j = 0; j < kept.size(); j++)
                Assert.assertArrayEquals(lowest.get(j), kept.get(j));
        }
    }

    @Test
    public void aSecondInceptionIsKeptAsBoundedEvidence() {
        Identity id = new Identity(2);
        Verified<IdentityInception> first = verified(id.inception(Arrays.asList(server())));
        List<Verified<? extends SignedRecord>> others = new ArrayList<>();
        for (int i = 0; i < 30; i++)
            others.add(verified(id.inception(Arrays.asList(server()))));
        Verified<? extends SignedRecord> lowest = others.stream().sorted(Verified::byHash).findFirst().get();
        Random rnd = new Random(3);
        for (int i = 0; i < 20; i++) {
            Collections.shuffle(others, rnd);
            List<Verified<? extends SignedRecord>> order = new ArrayList<>();
            order.add(first);
            order.addAll(others);
            IdentityLog log = apply(id.empty(), order);
            Assert.assertEquals("the first inception stays", first, log.records.inception.get());
            Assert.assertEquals(lowest, log.records.evidence.get());
            Assert.assertTrue(log.hasDuplicityEvidence());
            Assert.assertEquals(2, log.records.records().size());
        }
    }

    @Test
    public void anInceptionForAnotherIdentityIsRejected() {
        Identity id = new Identity(2), other = new Identity(2);
        Assert.assertEquals(Outcome.Rejected, id.empty().add(verified(other.inception(Arrays.asList(server())))).outcome);
    }

    @Test
    public void recordsBeforeTheirPredecessorsAreUnconnected() {
        Identity id = new Identity(4);
        IdentityLog empty = id.empty();
        Assert.assertEquals(Outcome.Unconnected, empty.add(verified(id.rotation(1, Arrays.asList(server())))).outcome);
        IdentityLog log = apply(empty, Arrays.asList(verified(id.inception(Arrays.asList(server())))));
        Assert.assertEquals(Outcome.Unconnected, log.add(verified(id.rotation(2, Arrays.asList(server())))).outcome);
        Assert.assertEquals(Outcome.Unconnected, log.add(verified(id.move(2, Arrays.asList(server()), 1))).outcome);
    }

    @Test
    public void boundsAreEnforced() {
        Identity id = new Identity(2);
        Assert.assertTrue(valid(id.inception(Arrays.asList(server(), server(), server(), server()))));
        Assert.assertFalse("five providers", valid(id.inception(Arrays.asList(server(), server(), server(), server(), server()))));
        Assert.assertFalse("no providers", valid(id.inception(Collections.emptyList())));
        Multihash s = server();
        Assert.assertFalse("duplicate providers", valid(id.move(0, Arrays.asList(s, s), 1)));
        Assert.assertFalse("negative counter", valid(id.move(0, Arrays.asList(s), -1)));

        SigningKeyPair srv = key();
        Address ok = new Address(Address.IROH, new byte[6]);
        Assert.assertTrue(valid(addresses(srv, Collections.nCopies(8, ok), Collections.emptyList(), true)));
        Assert.assertFalse("nine addresses", valid(addresses(srv, Collections.nCopies(9, ok), Collections.emptyList(), false)));
        Assert.assertFalse("a 65 byte address", valid(addresses(srv, Arrays.asList(new Address(Address.IROH, new byte[65])), Collections.emptyList(), false)));
        Assert.assertFalse("four relays", valid(addresses(srv, Collections.emptyList(), Arrays.asList(server(), server(), server(), server()), false)));
        Assert.assertFalse("a relay without a direct address", valid(addresses(srv, Collections.emptyList(), Collections.emptyList(), true)));
    }

    private static ServerAddresses addresses(SigningKeyPair server, List<Address> addrs, List<Multihash> relays, boolean relaying) {
        return ServerAddresses.build(server, server.publicSigningKey, Optional.empty(), addrs, relays,
                ServerAddresses.Mode.Light, relaying, 1).join();
    }

    @Test
    public void tamperedRecordsFailTheirSignature() {
        Identity id = new Identity(2);
        IdentityMove move = id.move(0, Arrays.asList(server()), 1);
        Assert.assertFalse(valid(new IdentityMove(move.key, Arrays.asList(server()), move.counter, move.signature)));
        Assert.assertFalse(valid(new IdentityMove(move.key, move.providers, move.counter + 1, move.signature)));
        Assert.assertFalse("signed by another key", valid(new IdentityMove(id.keys.get(1).publicSigningKey, move.providers, move.counter, move.signature)));
    }

    @Test
    public void cborRoundTrips() {
        Identity id = new Identity(3);
        SigningKeyPair srv = key(), next = key(), after = key();
        IdentityInception inception = id.inception(Arrays.asList(server()))
                .withAdmission(Admission.build(id.keys.get(0).publicSigningKey, GOOD_PROOF, srv.secretSigningKey).join());
        List<SignedRecord> records = Arrays.asList(
                inception,
                id.rotation(1, Arrays.asList(server())),
                id.move(1, Arrays.asList(server()), 4),
                ServerInception.build(srv, next.publicSigningKey, crypto.hasher).join().withProof(GOOD_PROOF),
                ServerRotation.build(srv.publicSigningKey, next, after.publicSigningKey, crypto.hasher).join(),
                ServerAddresses.build(srv, key().publicSigningKey, Optional.of(new byte[1184]),
                        Arrays.asList(new Address(Address.IROH, new byte[18]), new Address(Address.LIBP2P, new byte[40])),
                        Arrays.asList(server(), server()), ServerAddresses.Mode.Compact, true, 9).join());
        for (SignedRecord r : records) {
            byte[] raw = r.serialize();
            SignedRecord parsed = parse(r, raw);
            Assert.assertEquals(r, parsed);
            Assert.assertArrayEquals(raw, parsed.serialize());
            Assert.assertTrue(valid(parsed));
        }
    }

    private static SignedRecord parse(SignedRecord like, byte[] raw) {
        peergos.shared.cbor.CborObject cbor = peergos.shared.cbor.CborObject.fromByteArray(raw);
        if (like instanceof IdentityInception) return IdentityInception.fromCbor(cbor);
        if (like instanceof IdentityRotation) return IdentityRotation.fromCbor(cbor);
        if (like instanceof IdentityMove) return IdentityMove.fromCbor(cbor);
        if (like instanceof ServerInception) return ServerInception.fromCbor(cbor);
        if (like instanceof ServerRotation) return ServerRotation.fromCbor(cbor);
        return ServerAddresses.fromCbor(cbor);
    }

    @Test
    public void admissionChecksTheServerKeyAndTheWork() {
        Identity id = new Identity(2);
        SigningKeyPair srv = key();
        PublicSigningKey identity = id.keys.get(0).publicSigningKey;
        IdentityInception base = id.inception(Arrays.asList(ServerKeys.peerId(srv.publicSigningKey)));
        Assert.assertFalse("no admission yet", validator.hasValidAdmission(base, srv.publicSigningKey).join());

        IdentityInception admitted = base.withAdmission(Admission.build(identity, GOOD_PROOF, srv.secretSigningKey).join());
        Assert.assertTrue(validator.hasValidAdmission(admitted, srv.publicSigningKey).join());
        Assert.assertFalse("another server's key", validator.hasValidAdmission(admitted, key().publicSigningKey).join());
        IdentityInception badWork = base.withAdmission(Admission.build(identity, new byte[]{9}, srv.secretSigningKey).join());
        Assert.assertFalse(validator.hasValidAdmission(badWork, srv.publicSigningKey).join());

        Verified<IdentityInception> a = verified(admitted), b = verified(base);
        Assert.assertArrayEquals("an admission is not part of the record's hash", a.hash, b.hash);
    }

    @Test
    public void serverRotationAndAddresses() {
        SigningKeyPair s0 = key(), s1 = key(), s2 = key();
        ServerInception inception = ServerInception.build(s0, s1.publicSigningKey, crypto.hasher).join();
        ServerLog log = ServerLog.empty(inception.peerId());
        Assert.assertEquals(Outcome.Rejected, log.add(verified(ServerInception.build(key(), key().publicSigningKey, crypto.hasher).join())).outcome);
        log = log.add(verified(inception)).log;
        Assert.assertFalse(validator.hasValidProof(inception).join());
        Assert.assertTrue(validator.hasValidProof(inception.withProof(GOOD_PROOF)).join());

        Address a1 = new Address(Address.IROH, new byte[]{1}), a2 = new Address(Address.IROH, new byte[]{2});
        log = log.add(verified(addresses(s0, Arrays.asList(a1), Collections.emptyList(), false))).log;
        Assert.assertEquals(Arrays.asList(a1), log.addresses().get().addrs);

        log = log.add(verified(ServerRotation.build(s0.publicSigningKey, s1, s2.publicSigningKey, crypto.hasher).join())).log;
        Assert.assertEquals(s1.publicSigningKey, log.currentKey().get());
        Assert.assertTrue("the old key's addresses no longer count", log.addresses().isEmpty());
        Assert.assertEquals(Outcome.Ignored, log.add(verified(ServerAddresses.build(s0, s0.publicSigningKey, Optional.empty(),
                Arrays.asList(a2), Collections.emptyList(), ServerAddresses.Mode.Light, false, 5).join())).outcome);
        log = log.add(verified(ServerAddresses.build(s1, s0.publicSigningKey, Optional.empty(),
                Arrays.asList(a2), Collections.emptyList(), ServerAddresses.Mode.Light, false, 1).join())).log;
        Assert.assertEquals("the endpoint key can stay when the server key rotates", s0.publicSigningKey, log.addresses().get().endpoint);
        Assert.assertEquals(Arrays.asList(a2), log.addresses().get().addrs);
    }

    @Test
    public void signaturesDoNotTransferBetweenRecordTypes() {
        SigningKeyPair k = key();
        byte[] body = new byte[]{1, 2, 3};
        byte[] sig = Signing.sign(k.secretSigningKey, Signing.IDENTITY_MOVE, body).join();
        Assert.assertTrue(Signing.verify(k.publicSigningKey, Signing.IDENTITY_MOVE, body, sig).join());
        Assert.assertFalse(Signing.verify(k.publicSigningKey, Signing.SERVER_ADDRESSES, body, sig).join());
    }
}
