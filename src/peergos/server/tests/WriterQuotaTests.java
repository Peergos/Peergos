package peergos.server.tests;

import org.junit.*;
import peergos.server.*;
import peergos.server.mutable.MutableEvent;
import peergos.server.space.SpaceCheckingKeyFilter;
import peergos.server.space.UsageStore;
import peergos.server.space.WriterQuotas;
import peergos.server.space.WriterUsage;
import peergos.server.space.WriterUsageStore;
import peergos.server.storage.BulkCommitStorage;
import peergos.server.storage.DeletableContentAddressedStorage;
import peergos.server.storage.admin.QuotaAdmin;
import peergos.server.util.*;
import peergos.shared.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.Cid;
import peergos.shared.mutable.*;
import peergos.shared.storage.*;
import peergos.shared.user.*;
import peergos.shared.user.fs.*;
import peergos.shared.user.fs.cryptree.*;
import peergos.shared.util.*;

import java.nio.file.*;
import java.util.*;

public class WriterQuotaTests {

    private static Args args = UserTests.useMemoryDbs(UserTests.buildArgs())
            .with("enable-gc", "false");
    private static UserService service;
    private static final Crypto crypto = Main.initCrypto();
    private final Random random = new Random();
    private final NetworkAccess network;

    private static final int KiB = 1024;

    public WriterQuotaTests() {
        this.network = NetworkAccess.buildBuffered(new CachingStorage(service.storage, 1_000, 50 * 1024),
                service.bats, service.coreNode, service.account, service.mutable, 0, service.social,
                service.controller, service.usage, service.serverMessages, crypto.hasher, Arrays.asList("peergos"), false);
    }

    @BeforeClass
    public static void init() {
        service = Main.PKI_INIT.main(args).localApi;
    }

    private UserContext signUp() {
        return PeergosNetworkUtils.ensureSignedUp("q" + Math.abs(random.nextInt() % 1_000_000), "password", network.clear(), crypto);
    }

    private static void upload(UserContext user, Path dir, String filename, int size) {
        byte[] data = new byte[size];
        new Random().nextBytes(data);
        user.getByPath(dir).join().get()
                .uploadOrReplaceFile(filename, AsyncReader.build(data), data.length, user.network, crypto, () -> false, x -> {})
                .join();
    }

    private static void assertUploadRejected(UserContext user, Path dir, String filename, int size) {
        try {
            upload(user, dir, filename, size);
            Assert.fail("Writer quota wasn't enforced");
        } catch (Exception e) {
            String message = peergos.shared.util.Exceptions.getRootCause(e).getMessage();
            if (message == null || ! message.contains("Storage quota reached")) {
                java.io.StringWriter trace = new java.io.StringWriter();
                e.printStackTrace(new java.io.PrintWriter(trace));
                Assert.fail(trace.toString());
            }
        }
    }

    private static void delete(UserContext user, Path file) {
        FileWrapper parent = user.getByPath(file.getParent()).join().get();
        user.getByPath(file).join().get().remove(parent, file, user).join();
    }

    private static void awaitUsageUpdate() {
        Threads.sleep(2_000);
    }

    private static Path sharedDir(UserContext owner, String name, UserContext... sharees) {
        owner.getUserRoot().join().mkdir(name, owner.network, false, owner.mirrorBatId(), crypto).join();
        Path dir = PathUtil.get(owner.username, name);
        Set<String> usernames = new HashSet<>();
        for (UserContext sharee : sharees)
            usernames.add(sharee.username);
        owner.shareWriteAccessWith(dir, usernames).join();
        return dir;
    }

    @Test
    public void capIsEnforcedAndCanBeChanged() {
        UserContext owner = signUp();
        UserContext sharee = signUp();
        PeergosNetworkUtils.friendBetweenGroups(List.of(owner), List.of(sharee));
        Path dir = sharedDir(owner, "team", sharee);

        owner.setWriteShareQuota(dir, Optional.of(1024L * KiB)).join();

        upload(sharee, dir, "small", 400 * KiB);
        awaitUsageUpdate();
        assertUploadRejected(sharee, dir, "big", 800 * KiB);
        // the owner's own writes count towards the cap, which allows 1 MiB of slack once a write has been rejected
        assertUploadRejected(owner, dir, "big", 1800 * KiB);

        WriterUsageInfo ownerView = owner.getWriteShareQuota(dir).join();
        Assert.assertEquals(Optional.of(1024L * KiB), ownerView.quota);
        Assert.assertTrue(ownerView.used >= 400 * KiB);
        WriterUsageInfo shareeView = sharee.getWriteUsageInfo(sharee.getByPath(dir).join().get()).join();
        Assert.assertTrue(shareeView.available.isPresent());
        Assert.assertTrue(shareeView.available.get() <= 624 * KiB);
        Assert.assertEquals(1, owner.getWriteShareQuotas().join().size());

        // lowering the cap below usage blocks growth, but not deletes
        owner.setWriteShareQuota(dir, Optional.of(100L * KiB)).join();
        assertUploadRejected(sharee, dir, "tiny", 10 * KiB);
        delete(sharee, dir.resolve("small"));
        awaitUsageUpdate();

        owner.setWriteShareQuota(dir, Optional.empty()).join();
        upload(sharee, dir, "big", 2048 * KiB);
        Assert.assertTrue(owner.getWriteShareQuotas().join().isEmpty());
    }

    @Test
    public void deletesWorkWhenACapIsLoweredBelowUsage() {
        UserContext owner = signUp();
        UserContext sharee = signUp();
        PeergosNetworkUtils.friendBetweenGroups(List.of(owner), List.of(sharee));
        Path dir = sharedDir(owner, "team", sharee);
        for (int i = 0; i < 4; i++)
            upload(sharee, dir, "file" + i, 200 * KiB);
        awaitUsageUpdate();

        // no write has been refused, so nothing has marked the space as over its cap
        owner.setWriteShareQuota(dir, Optional.of(100L * KiB)).join();
        for (int i = 0; i < 4; i++) {
            delete(sharee, dir.resolve("file" + i));
            awaitUsageUpdate();
        }
        Assert.assertTrue(sharee.getByPath(dir).join().get().getChildren(crypto.hasher, sharee.network).join().isEmpty());
    }

    @Test
    public void canRevokeFromAFullCappedFolder() {
        UserContext owner = signUp();
        UserContext kept = signUp();
        UserContext revoked = signUp();
        PeergosNetworkUtils.friendBetweenGroups(List.of(owner), List.of(kept, revoked));
        Path dir = sharedDir(owner, "team", kept, revoked);
        upload(kept, dir, "file", 400 * KiB);
        awaitUsageUpdate();
        owner.setWriteShareQuota(dir, Optional.of(200L * KiB)).join();

        owner.unShareWriteAccessWith(dir, Set.of(revoked.username)).join();
        Assert.assertEquals(Optional.of(200L * KiB), owner.getWriteShareQuota(dir).join().quota);
        Assert.assertEquals(400 * KiB, read(owner, dir.resolve("file")).length);
    }

    /** Revoking inside a folder over its cap moves the subfolder into a new writer, which adds metadata under the
     *  cap: refused like any other growth, until the owner raises the cap. */
    @Test
    public void revokingInsideAFullCappedFolderWaitsForRoom() {
        UserContext owner = signUp();
        UserContext outer = signUp();
        UserContext revoked = signUp();
        PeergosNetworkUtils.friendBetweenGroups(List.of(owner), List.of(outer, revoked));
        Path dir = sharedDir(owner, "team", outer);
        owner.getByPath(dir).join().get().mkdir("inner", owner.network, false, owner.mirrorBatId(), crypto).join();
        Path inner = dir.resolve("inner");
        owner.shareWriteAccessWith(inner, Set.of(revoked.username)).join();
        upload(revoked, inner, "file", 400 * KiB);
        awaitUsageUpdate();
        owner.setWriteShareQuota(dir, Optional.of(200L * KiB)).join();

        assertRefusedForTheCap(() -> owner.unShareWriteAccessWith(inner, Set.of(revoked.username)).join());
        Assert.assertEquals(400 * KiB, read(revoked, inner.resolve("file")).length);

        owner.setWriteShareQuota(dir, Optional.of(1024L * KiB)).join();
        owner.unShareWriteAccessWith(inner, Set.of(revoked.username)).join();
        Assert.assertEquals(400 * KiB, read(owner, inner.resolve("file")).length);
    }

    @Test
    public void sharingInsideAFullCappedFolderWaitsForRoom() {
        UserContext owner = signUp();
        UserContext outer = signUp();
        UserContext innerSharee = signUp();
        PeergosNetworkUtils.friendBetweenGroups(List.of(owner), List.of(outer, innerSharee));
        Path dir = sharedDir(owner, "team", outer);
        owner.getByPath(dir).join().get().mkdir("inner", owner.network, false, owner.mirrorBatId(), crypto).join();
        Path inner = dir.resolve("inner");
        upload(outer, inner, "file", 400 * KiB);
        awaitUsageUpdate();
        owner.setWriteShareQuota(dir, Optional.of(200L * KiB)).join();

        // moving the folder into its own writing space copies it under a new key and frees the old copy, which
        // still leaves the capped space a little bigger than before
        assertRefusedForTheCap(() -> owner.shareWriteAccessWith(inner, Set.of(innerSharee.username)).join());
        Assert.assertTrue(innerSharee.getByPath(inner.resolve("file")).join().isEmpty());

        owner.setWriteShareQuota(dir, Optional.of(1024L * KiB)).join();
        owner.shareWriteAccessWith(inner, Set.of(innerSharee.username)).join();
        Assert.assertEquals(400 * KiB, read(innerSharee, inner.resolve("file")).length);
    }

    private static void assertRefusedForTheCap(Runnable action) {
        try {
            action.run();
            Assert.fail("Writer quota wasn't enforced");
        } catch (Exception e) {
            String message = peergos.shared.util.Exceptions.getRootCause(e).getMessage();
            Assert.assertTrue(String.valueOf(message), message != null && message.contains("Storage quota reached"));
        }
    }

    private static byte[] read(UserContext user, Path file) {
        FileWrapper f = user.getByPath(file).join().get();
        return Serialize.readFully(f.getInputStream(user.network, crypto, x -> {}).join(), f.getSize()).join();
    }

    @Test
    public void nestedWritingSpacesCountTowardsCap() {
        UserContext owner = signUp();
        UserContext sharee = signUp();
        UserContext nestedSharee = signUp();
        PeergosNetworkUtils.friendBetweenGroups(List.of(owner), List.of(sharee, nestedSharee));
        Path dir = sharedDir(owner, "team", sharee);
        owner.getByPath(dir).join().get().mkdir("nested", owner.network, false, owner.mirrorBatId(), crypto).join();
        Path nested = dir.resolve("nested");
        owner.shareWriteAccessWith(nested, Set.of(nestedSharee.username)).join();

        owner.setWriteShareQuota(dir, Optional.of(1024L * KiB)).join();
        upload(nestedSharee, nested, "file", 600 * KiB);
        awaitUsageUpdate();
        assertUploadRejected(sharee, dir, "file", 600 * KiB);

        // a nested sharee sees how much space is left, but not how much the enclosing folder holds
        WriterUsageInfo nestedView = nestedSharee.getWriteUsageInfo(nestedSharee.getByPath(nested).join().get()).join();
        Assert.assertTrue(nestedView.quota.isEmpty());
        Assert.assertEquals(0, nestedView.used);
        Assert.assertTrue(nestedView.available.isPresent());
        Assert.assertTrue(nestedView.available.get() <= 424 * KiB);
    }

    /** A child writing space made in the same commit as the blocks written into it counts towards the cap of the
     *  space that owns it from its first block, not only once the server has processed the parent's new pointer. */
    @Test
    public void aChildWritingSpaceMadeInTheSameCommitCountsTowardsTheCap() {
        UserContext owner = signUp();
        UserContext sharee = signUp();
        PeergosNetworkUtils.friendBetweenGroups(List.of(owner), List.of(sharee));
        Path dir = sharedDir(owner, "team", sharee);
        owner.setWriteShareQuota(dir, Optional.of(256L * KiB)).join();

        FileWrapper shared = sharee.getByPath(dir).join().get();
        PublicKeyHash ownerKey = shared.owner();
        SigningPrivateKeyAndPublicHash parent = shared.signingPair();
        // buffered, as a browser's is, so the whole computation goes to the server as one bulk commit
        NetworkAccess net = network.clear();
        List<byte[]> blocks = new ArrayList<>();
        // small enough to travel inline in the commit: larger ones are written ahead of it, which already
        // fails for a writer the server doesn't know yet
        for (int i = 0; i < 6; i++) {
            byte[] block = new byte[90 * KiB];
            random.nextBytes(block);
            blocks.add(block);
        }
        SigningPrivateKeyAndPublicHash[] made = new SigningPrivateKeyAndPublicHash[1];
        try {
            // the child's new root links the blocks, so they travel in the same call as the pointers that create it
            net.synchronizer.applyComplexUpdate(ownerKey, parent, (s, committer) ->
                    CryptreeNode.initAndAuthoriseSigner(ownerKey, parent, SigningKeyPair.random(crypto.random, crypto.signer), net, s, committer)
                            .thenApply(child -> { made[0] = child.right; return child; })
                            .thenCompose(child -> IpfsTransaction.call(ownerKey,
                                    tid -> signed(child.right, blocks).thenCompose(sigs -> net.dhtClient.putRaw(ownerKey,
                                                    child.right.publicKeyHash, sigs, blocks, tid, x -> {}))
                                            .thenCompose(raw -> {
                                                byte[] links = new CborObject.CborList(raw.stream().map(CborObject.CborMerkleLink::new).toList()).serialize();
                                                return signed(child.right, List.of(links)).thenCompose(sigs -> net.dhtClient.put(ownerKey,
                                                        child.right.publicKeyHash, sigs, List.of(links), tid));
                                            })
                                            .thenCompose(links -> {
                                                CommittedWriterData current = child.left.get(child.right);
                                                return committer.commit(ownerKey, child.right, current.props.get().withChamp(links.get(0)), current, tid);
                                            }),
                                    net.dhtClient))).join();
            Assert.fail("A new child writing space was written past its parent's cap");
        } catch (Exception e) {
            String message = peergos.shared.util.Exceptions.getRootCause(e).getMessage();
            Assert.assertTrue(String.valueOf(message), message != null && message.contains("Storage quota reached"));
        }

        // the refused commit takes its key back with it: kept, it would write outside every cap once the share is gone
        owner.unShareWriteAccessWith(dir, Set.of(sharee.username)).join();
        assertCannotCommit(made[0], ownerKey, "a key from a refused commit, after the share was revoked");
    }

    /** A writer whose last update the queue never sized has an empty stored target, and the edge to the child that
     *  update named. Dropped, it takes that child with it, as one whose sized root names the child does. */
    @Test
    public void aChildLinkedUnderAWriterWhoseStoredTargetIsEmptyGoesWithIt() throws Exception {
        UserContext owner = signUp();
        UserContext sharee = signUp();
        PeergosNetworkUtils.friendBetweenGroups(List.of(owner), List.of(sharee));
        Path dir = sharedDir(owner, "team", sharee);
        owner.setWriteShareQuota(dir, Optional.of(256L * KiB)).join();
        FileWrapper shared = sharee.getByPath(dir).join().get();
        PublicKeyHash ownerKey = shared.owner();
        SigningPrivateKeyAndPublicHash w = shared.signingPair();

        // the sharee makes a child writing space C under the capped folder, as usual
        NetworkAccess net = network.clear();
        SigningPrivateKeyAndPublicHash[] made = new SigningPrivateKeyAndPublicHash[1];
        net.synchronizer.applyComplexUpdate(ownerKey, w, (s, committer) ->
                CryptreeNode.initAndAuthoriseSigner(ownerKey, w, SigningKeyPair.random(crypto.random, crypto.signer), net, s, committer)
                        .thenApply(child -> { made[0] = child.right; return child.left; })).join();
        SigningPrivateKeyAndPublicHash c = made[0];
        awaitUsageUpdate();

        // empties C: processed, so C's stored target is empty, and C stays registered as W still names it
        CommittedWriterData cNow = WriterData.getWriterData(ownerKey, c.publicKeyHash, service.mutable, service.storage).join();
        PointerUpdate toEmpty = new PointerUpdate(cNow.hash, MaybeMultihash.empty(), PointerUpdate.increment(cNow.sequence));
        service.mutable.setPointer(ownerKey, c, toEmpty).join();
        awaitUsageUpdate();

        // sets C to a root naming a new key D whose tree links a missing block: D is linked as the pointer arrives,
        // and the update is never sized, so C's stored target stays empty
        SigningPrivateKeyAndPublicHash d = newKeyUnder(ownerKey, c);
        OwnerProof proof = OwnerProof.build(d, c.publicKeyHash).join();
        byte[] absent = new byte[100];
        random.nextBytes(absent);
        Cid missing = crypto.hasher.hash(absent, false).join();
        TransactionId tid = service.storage.startTransaction(ownerKey).join();
        WriterData empty = WriterData.createEmpty(ownerKey, c, service.storage, crypto.hasher, tid).join();
        Cid naming = service.storage.put(ownerKey, c, empty.addNamedKey("unsized", proof).withChamp(missing).serialize(),
                crypto.hasher, tid).join();
        service.mutable.setPointer(ownerKey, c, new PointerUpdate(MaybeMultihash.empty(), MaybeMultihash.of(naming),
                PointerUpdate.increment(toEmpty.sequence))).join();
        awaitUsageUpdate();

        // the sharee drops C from the capped folder: D goes with it
        NetworkAccess net2 = network.clear();
        net2.synchronizer.applyComplexUpdate(ownerKey, w, (s, committer) ->
                CryptreeNode.deAuthoriseSigner(ownerKey, w, c.publicKeyHash, net2, s, committer)).join();
        awaitUsageUpdate();
        assertCannotCommit(d, ownerKey, "a key linked under a dropped writer whose stored target was empty");
    }

    /** A refused commit can have made writers that name each other, and landed their own pointer updates while it
     *  was in flight. Each has a parent then, but none that leads to the owner, so the undo takes them back. */
    @Test
    public void aCycleMadeInARefusedCommitIsTakenBackWithIt() throws Exception {
        UserContext owner = signUp();
        UserContext sharee = signUp();
        PeergosNetworkUtils.friendBetweenGroups(List.of(owner), List.of(sharee));
        Path dir = sharedDir(owner, "team", sharee);
        owner.setWriteShareQuota(dir, Optional.of(256L * KiB)).join();
        awaitUsageUpdate();
        FileWrapper shared = sharee.getByPath(dir).join().get();
        PublicKeyHash ownerKey = shared.owner();
        SigningPrivateKeyAndPublicHash w = shared.signingPair();
        SigningPrivateKeyAndPublicHash d = newKeyUnder(ownerKey, w);
        SigningPrivateKeyAndPublicHash e = newKeyUnder(ownerKey, d);

        // the commit's own roots for D (naming E) and E (naming D), written under W, which is registered
        TransactionId tid = service.storage.startTransaction(ownerKey).join();
        Cid champ = OwnedKeyChamp.createEmpty(ownerKey, w, service.storage, crypto.hasher, tid).join();
        WriterData dEmpty = new WriterData(d.publicKeyHash, Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(champ),
                Collections.emptyMap(), Optional.empty(), Optional.empty(), Optional.empty());
        Cid dRoot = service.storage.put(ownerKey, w, dEmpty.addNamedKey("e", OwnerProof.build(e, d.publicKeyHash).join()).serialize(),
                crypto.hasher, tid).join();
        WriterData eEmpty = new WriterData(e.publicKeyHash, Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(champ),
                Collections.emptyMap(), Optional.empty(), Optional.empty(), Optional.empty());
        Cid eRoot = service.storage.put(ownerKey, w, eEmpty.addNamedKey("d", OwnerProof.build(d, e.publicKeyHash).join()).serialize(),
                crypto.hasher, tid).join();
        PointerUpdate dUpd = new PointerUpdate(MaybeMultihash.empty(), MaybeMultihash.of(dRoot), PointerUpdate.increment(Optional.empty()));
        PointerUpdate eUpd = new PointerUpdate(MaybeMultihash.empty(), MaybeMultihash.of(eRoot), PointerUpdate.increment(Optional.empty()));
        SignedPointerUpdate dSigned = new SignedPointerUpdate(d.publicKeyHash, d.secret.signMessage(dUpd.serialize()).join());
        SignedPointerUpdate eSigned = new SignedPointerUpdate(e.publicKeyHash, e.secret.signMessage(eUpd.serialize()).join());

        // as a commit [W naming D, D naming E, E naming D] registers them, in the order it proves them
        SpaceCheckingKeyFilter filter = quotaFilter();
        BulkCommitStorage.Registration underW = filter.registerNewWriter(ownerKey, w.publicKeyHash, d.publicKeyHash, Optional.of(dSigned));
        BulkCommitStorage.Registration underD = filter.registerNewWriter(ownerKey, d.publicKeyHash, e.publicKeyHash, Optional.of(eSigned));
        // while its blocks are written, the commit's own updates for D and E land, which a provisional writer may do
        service.mutable.setPointer(ownerKey, d, dUpd).join();
        service.mutable.setPointer(ownerKey, e, eUpd).join();
        awaitUsageUpdate();
        // the commit is refused, and takes back what it registered, the last first
        underD.undo();
        underW.undo();
        awaitUsageUpdate();
        assertCannotCommit(d, ownerKey, "a writer a refused commit made, named only by another it made");
        assertCannotCommit(e, ownerKey, "a writer a refused commit made under one it made");
    }

    /** A key the server once knew as the sharee's own, named under the owner's capped folder too, is forgotten when
     *  the sharee drops it from their own account. The owner's folder still names it, so the next write heals it in
     *  as the owner's: under the folder that names it, with its cap, not as a writer of the owner's with no parent. */
    @Test
    public void aKeyHealedBackInIsUnderTheCapOfTheFolderThatNamesIt() throws Exception {
        UserContext owner = signUp();
        UserContext sharee = signUp();
        PeergosNetworkUtils.friendBetweenGroups(List.of(owner), List.of(sharee));
        Path dir = sharedDir(owner, "team", sharee);
        owner.setWriteShareQuota(dir, Optional.of(256L * KiB)).join();
        awaitUsageUpdate();
        FileWrapper shared = sharee.getByPath(dir).join().get();
        PublicKeyHash ownerKey = shared.owner();
        SigningPrivateKeyAndPublicHash w = shared.signingPair();
        PublicKeyHash shareeKey = sharee.signer.publicKeyHash;

        // K is a writer of the sharee's own account first, then named under the owner's capped folder as well
        SigningPrivateKeyAndPublicHash k = newKeyUnder(shareeKey, sharee.signer);
        linkUnder(sharee, k, sharee.signer);
        awaitUsageUpdate();
        linkUnderOwner(ownerKey, k, w);
        awaitUsageUpdate();
        Assert.assertEquals(sharee.username, usageStore().getOwner(k.publicKeyHash));

        // dropped from the sharee's own account, the server forgets it
        NetworkAccess net = network.clear();
        net.synchronizer.applyComplexUpdate(shareeKey, sharee.signer, (s, committer) ->
                CryptreeNode.deAuthoriseSigner(shareeKey, sharee.signer, k.publicKeyHash, net, s, committer)).join();
        awaitUsageUpdate();
        try {
            Assert.fail("K is still registered, to " + usageStore().getOwner(k.publicKeyHash));
        } catch (IllegalStateException forgotten) {}

        // a write with K for the owner heals it in: about 1 MiB into a 256 KiB cap, with nothing refused before
        try {
            fillWriter(ownerKey, k);
            Assert.fail("A healed key wrote past the cap of the folder that names it");
        } catch (Exception refused) {
            String message = String.valueOf(peergos.shared.util.Exceptions.getRootCause(refused).getMessage());
            Assert.assertTrue(message, message.contains("Storage quota reached"));
        }
        Assert.assertEquals(owner.username, usageStore().getOwner(k.publicKeyHash));
        Assert.assertEquals(Set.of(w.publicKeyHash), usageStore().getParents(k.publicKeyHash));
    }

    /** The self heal walks the owner's tree from their identity. A sharee's own key named under the shared folder
     *  is the sharee's, and so is whatever it names: the walk doesn't go through it, or a key it names would be
     *  healed in as the owner's, under the sharee's key, with no cap and nothing to revoke it. */
    @Test
    public void aKeyNamedByAnotherUsersWriterIsntHealedInAsTheOwners() throws Exception {
        UserContext owner = signUp();
        UserContext sharee = signUp();
        PeergosNetworkUtils.friendBetweenGroups(List.of(owner), List.of(sharee));
        Path dir = sharedDir(owner, "team", sharee);
        owner.setWriteShareQuota(dir, Optional.of(256L * KiB)).join();
        awaitUsageUpdate();
        FileWrapper shared = sharee.getByPath(dir).join().get();
        PublicKeyHash ownerKey = shared.owner();
        SigningPrivateKeyAndPublicHash w = shared.signingPair();
        PublicKeyHash shareeKey = sharee.signer.publicKeyHash;

        // K: a writer of the sharee's own account, named under the owner's capped folder as well
        SigningPrivateKeyAndPublicHash k = newKeyUnder(shareeKey, sharee.signer);
        linkUnder(sharee, k, sharee.signer);
        awaitUsageUpdate();
        linkUnderOwner(ownerKey, k, w);
        awaitUsageUpdate();
        Assert.assertEquals(sharee.username, usageStore().getOwner(k.publicKeyHash));

        // X: a key the sharee holds
        SigningKeyPair xPair = SigningKeyPair.random(crypto.random, crypto.signer);
        SigningPrivateKeyAndPublicHash x = new SigningPrivateKeyAndPublicHash(ContentAddressedStorage.hashKey(xPair.publicSigningKey), xPair.secretSigningKey);
        OwnerProof proof = OwnerProof.build(x, k.publicKeyHash).join();
        // K's owned-key champ holds X; built to one side, its blocks are unique to this test
        peergos.server.storage.RAMStorage aside = new peergos.server.storage.RAMStorage(crypto.hasher);
        TransactionId asideTid = aside.startTransaction(shareeKey).join();
        Cid emptyChamp = OwnedKeyChamp.createEmpty(shareeKey, k, aside, crypto.hasher, asideTid).join();
        Cid champ = (Cid) OwnedKeyChamp.build(shareeKey, emptyChamp, aside, crypto.hasher).join()
                .add(shareeKey, k, proof, crypto.hasher, asideTid).join();
        Cid proofBlock = aside.put(shareeKey, k, proof.serialize(), crypto.hasher, asideTid).join();
        byte[] champRaw = aside.getRaw(shareeKey, champ, Optional.empty()).join().get();
        byte[] proofRaw = aside.getRaw(shareeKey, proofBlock, Optional.empty()).join().get();
        WriterData kRoot = new WriterData(k.publicKeyHash, Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(champ),
                Collections.emptyMap(), Optional.empty(), Optional.empty(), Optional.empty());
        byte[] rootRaw = kRoot.serialize();
        // Blocks are partitioned by owner. The root goes in the sharee's space (so K's pointer can be set) and in the
        // owner's (under W, which the sharee holds); the champ's blocks only in the owner's. Processing K's pointer
        // as the sharee's fails on the champ, so X stays unregistered while K's committed root names it
        TransactionId tid = service.storage.startTransaction(shareeKey).join();
        Cid naming = service.storage.put(shareeKey, k, rootRaw, crypto.hasher, tid).join();
        TransactionId ownerTid = service.storage.startTransaction(ownerKey).join();
        Assert.assertEquals(naming, service.storage.put(ownerKey, w, rootRaw, crypto.hasher, ownerTid).join());
        Assert.assertEquals(champ, service.storage.put(ownerKey, w, champRaw, crypto.hasher, ownerTid).join());
        Assert.assertEquals(proofBlock, service.storage.put(ownerKey, w, proofRaw, crypto.hasher, ownerTid).join());
        service.mutable.setPointer(shareeKey, k, new PointerUpdate(MaybeMultihash.empty(), MaybeMultihash.of(naming),
                PointerUpdate.increment(Optional.empty()))).join();
        awaitUsageUpdate();
        try {
            Assert.fail("X registered already, to " + usageStore().getOwner(x.publicKeyHash));
        } catch (IllegalStateException unregistered) {}

        // a write with X for the owner: the self heal walks owner -> ... -> W -> K (the sharee's) -> X
        String healed;
        try {
            write(ownerKey, x, 1, KiB);
            healed = "committed";
        } catch (Exception e) {
            healed = "refused: " + peergos.shared.util.Exceptions.getRootCause(e).getMessage();
        }
        String xOwner;
        try {
            xOwner = usageStore().getOwner(x.publicKeyHash);
        } catch (IllegalStateException unregistered) {
            xOwner = "<unregistered>";
        }

        // the cap on the folder: about 1 MiB into a 256 KiB cap
        String fill;
        try {
            fillWriter(ownerKey, x);
            fill = "committed";
        } catch (Exception e) {
            fill = "refused: " + peergos.shared.util.Exceptions.getRootCause(e).getMessage();
        }
        awaitUsageUpdate();

        // the owner revokes the share
        owner.unShareWriteAccessWith(dir, Set.of(sharee.username)).join();
        awaitUsageUpdate();
        String after;
        try {
            write(ownerKey, x, 1, KiB);
            after = "committed";
        } catch (Exception e) {
            after = "refused: " + peergos.shared.util.Exceptions.getRootCause(e).getMessage();
        }

        Assert.assertNotEquals("X was registered as the owner's", owner.username, xOwner);
        Assert.assertTrue("X wrote for the owner: " + healed, healed.startsWith("refused"));
        Assert.assertTrue("X wrote past the cap: " + fill, fill.startsWith("refused"));
        Assert.assertTrue("X survived the revocation: " + after, after.startsWith("refused"));
    }

    /** A sharee's key named under the owner's folder has its root in the sharee's block space, which the owner's
     *  self heal can't read: not walked, it doesn't stop a writer of the owner's the store has lost from being
     *  healed in. */
    @Test
    public void aLostWriterIsHealedInPastAnotherUsersKeyInTheTree() throws Exception {
        UserContext owner = signUp();
        UserContext sharee = signUp();
        PeergosNetworkUtils.friendBetweenGroups(List.of(owner), List.of(sharee));
        Path dir = sharedDir(owner, "team", sharee);
        owner.getByPath(dir).join().get().mkdir("inner", owner.network, false, owner.mirrorBatId(), crypto).join();
        Path inner = dir.resolve("inner");
        owner.shareWriteAccessWith(inner, Set.of(sharee.username)).join();
        awaitUsageUpdate();
        FileWrapper shared = sharee.getByPath(dir).join().get();
        PublicKeyHash ownerKey = shared.owner();
        SigningPrivateKeyAndPublicHash w = shared.signingPair();
        SigningPrivateKeyAndPublicHash v = sharee.getByPath(inner).join().get().signingPair();
        PublicKeyHash shareeKey = sharee.signer.publicKeyHash;

        // K: a writer of the sharee's own, with a root of its own, named under the owner's folder as well
        SigningPrivateKeyAndPublicHash k = newKeyUnder(shareeKey, sharee.signer);
        linkUnder(sharee, k, sharee.signer);
        awaitUsageUpdate();
        write(shareeKey, k, 1, KiB);
        awaitUsageUpdate();
        linkUnderOwner(ownerKey, k, w);
        awaitUsageUpdate();
        Assert.assertEquals(sharee.username, usageStore().getOwner(k.publicKeyHash));

        // the store loses the inner folder's writer, as a missed registration would
        usageStore().removeWriter(v.publicKeyHash);
        String healed;
        try {
            write(ownerKey, v, 1, KiB);
            healed = "committed";
        } catch (Exception e) {
            healed = "refused: " + peergos.shared.util.Exceptions.getRootCause(e).getMessage();
        }
        String vOwner;
        try {
            vOwner = usageStore().getOwner(v.publicKeyHash);
        } catch (IllegalStateException unregistered) {
            vOwner = "<unregistered>";
        }
        Assert.assertEquals("committed", healed);
        Assert.assertEquals(owner.username, vOwner);
    }

    private void assertCannotCommit(SigningPrivateKeyAndPublicHash key, PublicKeyHash owner, String what) {
        try {
            write(owner, key, 1, KiB);
        } catch (Exception refused) {
            // refused for the key, not for anything else a commit can fail on
            String message = String.valueOf(peergos.shared.util.Exceptions.getRootCause(refused).getMessage());
            Assert.assertTrue(message, message.contains("not present on this server") || message.contains("not allowed to write"));
            return;
        }
        Assert.fail("Committed with " + what);
    }

    /** The sharee names a key they hold as owned by one of their own writers, with a proof the key signs */
    private void linkUnder(UserContext sharee, SigningPrivateKeyAndPublicHash key, SigningPrivateKeyAndPublicHash parent) {
        NetworkAccess net = network.clear();
        PublicKeyHash shareeKey = sharee.signer.publicKeyHash;
        OwnerProof proof = OwnerProof.build(key, parent.publicKeyHash).join();
        IpfsTransaction.call(shareeKey, tid -> net.synchronizer.applyComplexUpdate(shareeKey, parent, (s, committer) -> {
            CommittedWriterData current = s.get(parent);
            return current.props.get().addOwnedKeyAndCommit(shareeKey, parent, proof, current.hash, current.sequence, net, committer, tid);
        }), net.dhtClient).join();
    }

    @Test
    public void aShareeCantKeepARevokedKeyByNamingItUnderTheirOwnWriter() {
        UserContext owner = signUp();
        UserContext sharee = signUp();
        PeergosNetworkUtils.friendBetweenGroups(List.of(owner), List.of(sharee));
        Path dir = sharedDir(owner, "team", sharee);
        FileWrapper shared = sharee.getByPath(dir).join().get();
        linkUnder(sharee, shared.signingPair(), sharee.signer);
        awaitUsageUpdate();

        owner.unShareWriteAccessWith(dir, Set.of(sharee.username)).join();
        awaitUsageUpdate();
        assertCannotCommit(shared.signingPair(), shared.owner(), "a revoked key the sharee had named under their own writer");
    }

    @Test
    public void aShareesCapDoesntReachAnOwnersFolderTheyNamedUnderIt() {
        UserContext owner = signUp();
        UserContext sharee = signUp();
        UserContext third = signUp();
        PeergosNetworkUtils.friendBetweenGroups(List.of(owner), List.of(sharee));
        PeergosNetworkUtils.friendBetweenGroups(List.of(sharee), List.of(third));
        Path dir = sharedDir(owner, "team", sharee);
        Path mine = sharedDir(sharee, "mine", third);
        linkUnder(sharee, sharee.getByPath(dir).join().get().signingPair(), sharee.getByPath(mine).join().get().signingPair());
        awaitUsageUpdate();

        sharee.setWriteShareQuota(mine, Optional.of(1L)).join();
        upload(owner, dir, "ownersfile", 10 * KiB);
    }

    /** A landed pointer whose root names only keys already linked under its writer registers and links none of them
     *  again, as it lands or in the queue: on a busy server those would be writes that change nothing, for each owned
     *  key on every commit of the writer.
     *  A key not linked yet is still registered and linked as the pointer lands. */
    @Test
    public void aLandedPointerRegistersAndLinksOnlyKeysNotLinkedYet() throws Exception {
        UserContext owner = signUp();
        Path dir = sharedDir(owner, "linked");
        awaitUsageUpdate();
        PublicKeyHash ownerKey = owner.signer.publicKeyHash;
        PublicKeyHash parent = owner.getUserRoot().join().writer();
        PublicKeyHash child = owner.getByPath(dir).join().get().writer();
        UsageStore store = usageStore();
        Assert.assertTrue(store.getOwnedKeys(parent).contains(child));
        // the parent moves on from a root that names the child already
        MaybeMultihash before = service.mutable.getPointerTarget(ownerKey, parent, service.storage).join().updated;
        upload(owner, PathUtil.get(owner.username), "file", KiB);
        awaitUsageUpdate();

        Map<String, Integer> writes = new HashMap<>();
        UsageStore counting = (UsageStore) java.lang.reflect.Proxy.newProxyInstance(UsageStore.class.getClassLoader(),
                new Class<?>[] {UsageStore.class}, (proxy, method, methodArgs) -> {
                    if (method.getName().equals("addWriter") || method.getName().equals("addOwnedKey"))
                        writes.merge(method.getName(), 1, Integer::sum);
                    // as the queue finds the parent before it reaches that move
                    if (method.getName().equals("getUsage") && parent.equals(methodArgs[0])) {
                        WriterUsage now = store.getUsage(parent);
                        return new WriterUsage(now.owner, before, now.directRetainedStorage(), now.ownedKeys());
                    }
                    try {
                        return method.invoke(store, methodArgs);
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
        MutableEvent landed = new MutableEvent(ownerKey, parent, service.mutable.getPointer(ownerKey, parent).join().get());
        pointerLanded(landed, counting);
        updateProcessed(ownerKey, parent, before, counting);
        Assert.assertEquals("writes for keys already linked", Map.of(), writes);

        store.removeOwnedKey(parent, child);
        pointerLanded(landed, counting);
        Assert.assertEquals(Map.of("addWriter", 1, "addOwnedKey", 1), writes);
        Assert.assertTrue(store.getOwnedKeys(parent).contains(child));
    }

    /** The server's step as a pointer lands, against the given store */
    private static void pointerLanded(MutableEvent event, UsageStore store) throws Exception {
        java.lang.reflect.Method prepare = SpaceCheckingKeyFilter.class.getDeclaredMethod("prepareMutablePointerChange",
                MutableEvent.class, DeletableContentAddressedStorage.class, UsageStore.class, Hasher.class);
        prepare.setAccessible(true);
        prepare.invoke(null, event, filterField("dht"), store, filterField("hasher"));
    }

    /** The queue's step for a writer's update from a root to its current one, against the given store */
    private static void updateProcessed(PublicKeyHash owner, PublicKeyHash writer, MaybeMultihash from, UsageStore store) throws Exception {
        MaybeMultihash to = service.mutable.getPointerTarget(owner, writer, service.storage).join().updated;
        java.lang.reflect.Method process = SpaceCheckingKeyFilter.class.getDeclaredMethod("processMutablePointerEvent",
                UsageStore.class, PublicKeyHash.class, PublicKeyHash.class, MaybeMultihash.class, MaybeMultihash.class,
                MutablePointers.class, QuotaAdmin.class, DeletableContentAddressedStorage.class, Hasher.class);
        process.setAccessible(true);
        process.invoke(null, store, owner, writer, from, to, filterField("mutable"), filterField("quotaAdmin"),
                filterField("dht"), filterField("hasher"));
    }

    private static Object filterField(String name) throws Exception {
        java.lang.reflect.Field field = SpaceCheckingKeyFilter.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(quotaFilter());
    }

    /** The server's quota filter, to take a commit's steps one at a time */
    private static SpaceCheckingKeyFilter quotaFilter() throws Exception {
        java.lang.reflect.Field local = ProxyingSpaceUsage.class.getDeclaredField("local");
        local.setAccessible(true);
        return (SpaceCheckingKeyFilter) local.get(service.usage);
    }

    private static UsageStore usageStore() throws Exception {
        java.lang.reflect.Field store = SpaceCheckingKeyFilter.class.getDeclaredField("usageStore");
        store.setAccessible(true);
        return (UsageStore) store.get(quotaFilter());
    }

    private static WriterQuotas writerQuotas() throws Exception {
        java.lang.reflect.Field quotas = SpaceCheckingKeyFilter.class.getDeclaredField("writerQuotas");
        quotas.setAccessible(true);
        return (WriterQuotas) quotas.get(quotaFilter());
    }

    /** Names a key as owned by a parent, both writers of the owner, in a commit the parent signs */
    private void linkUnderOwner(PublicKeyHash owner, SigningPrivateKeyAndPublicHash key, SigningPrivateKeyAndPublicHash parent) {
        NetworkAccess net = network.clear();
        OwnerProof proof = OwnerProof.build(key, parent.publicKeyHash).join();
        IpfsTransaction.call(owner, tid -> net.synchronizer.applyComplexUpdate(owner, parent, (s, committer) -> {
            CommittedWriterData current = s.get(parent);
            return current.props.get().addOwnedKeyAndCommit(owner, parent, proof, current.hash, current.sequence, net, committer, tid);
        }), net.dhtClient).join();
    }

    /** A new key, its public key stored under the parent as a client stores one it is about to make a writer */
    private SigningPrivateKeyAndPublicHash newKeyUnder(PublicKeyHash owner, SigningPrivateKeyAndPublicHash parent) {
        NetworkAccess net = network.clear();
        SigningKeyPair pair = SigningKeyPair.random(crypto.random, crypto.signer);
        PublicKeyHash hash = net.dhtClient.putSigningKey(parent.secret.signMessage(pair.publicSigningKey.serialize()).join(), owner,
                parent.publicKeyHash, pair.publicSigningKey, net.dhtClient.startTransaction(owner).join()).join();
        return new SigningPrivateKeyAndPublicHash(hash, pair.secretSigningKey);
    }

    /** About 1 MiB into a writer's own space, in one buffered commit */
    private void fillWriter(PublicKeyHash owner, SigningPrivateKeyAndPublicHash writer) {
        write(owner, writer, 11, 90 * KiB);
    }

    /** Blocks into a writer's own space in one buffered commit, over what it has, or as its first write */
    private void write(PublicKeyHash owner, SigningPrivateKeyAndPublicHash writer, int count, int size) {
        NetworkAccess net = network.clear();
        List<byte[]> blocks = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            byte[] block = new byte[size];
            random.nextBytes(block);
            blocks.add(block);
        }
        net.synchronizer.applyComplexUpdate(owner, writer, (s, committer) -> IpfsTransaction.call(owner,
                tid -> signed(writer, blocks).thenCompose(sigs -> net.dhtClient.putRaw(owner,
                                writer.publicKeyHash, sigs, blocks, tid, x -> {}))
                        .thenCompose(raw -> {
                            byte[] links = new CborObject.CborList(raw.stream().map(CborObject.CborMerkleLink::new).toList()).serialize();
                            return signed(writer, List.of(links)).thenCompose(sigs -> net.dhtClient.put(owner,
                                    writer.publicKeyHash, sigs, List.of(links), tid));
                        })
                        .thenCompose(links -> {
                            CommittedWriterData current = s.contains(writer.publicKeyHash) ? s.get(writer)
                                    : new CommittedWriterData(MaybeMultihash.empty(), Optional.empty(), Optional.empty());
                            return (current.props.isPresent() ? Futures.of(current.props.get())
                                    : WriterData.createEmpty(owner, writer, net.dhtClient, crypto.hasher, tid))
                                    .thenCompose(wd -> committer.commit(owner, writer, wd.withChamp(links.get(0)), current, tid));
                        }),
                net.dhtClient)).join();
    }

    @Test
    public void aWriterACommitIsStillCreatingIsMadeByNoOther() throws Exception {
        UserContext owner = signUp();
        UserContext sharee = signUp();
        PeergosNetworkUtils.friendBetweenGroups(List.of(owner), List.of(sharee));
        Path dir = sharedDir(owner, "team", sharee);
        owner.setWriteShareQuota(dir, Optional.of(256L * KiB)).join();
        FileWrapper shared = sharee.getByPath(dir).join().get();
        PublicKeyHash ownerKey = shared.owner();
        SigningPrivateKeyAndPublicHash parent = shared.signingPair();
        SigningKeyPair pair = SigningKeyPair.random(crypto.random, crypto.signer);

        // a commit creating the child registers it and is still writing its blocks: until it lands, no other commit
        // lands the child, a retry say, or what that wrote would be forgotten with the first if it is refused
        BulkCommitStorage.Registration creating = quotaFilter().registerNewWriter(ownerKey, parent.publicKeyHash,
                ContentAddressedStorage.hashKey(pair.publicSigningKey), Optional.empty());
        NetworkAccess net = network.clear();
        try {
            net.synchronizer.applyComplexUpdate(ownerKey, parent, (s, committer) ->
                    CryptreeNode.initAndAuthoriseSigner(ownerKey, parent, pair, net, s, committer).thenApply(made -> made.left)).join();
            Assert.fail("A second commit made a child another commit is still creating");
        } catch (Exception refused) {
            String message = String.valueOf(peergos.shared.util.Exceptions.getRootCause(refused).getMessage());
            Assert.assertTrue(message, message.contains("not allowed to write"));
        }
        // the first is refused and takes its registration back; the child can be made as usual, under the cap
        creating.undo();
        NetworkAccess again = network.clear();
        SigningPrivateKeyAndPublicHash[] child = new SigningPrivateKeyAndPublicHash[1];
        again.synchronizer.applyComplexUpdate(ownerKey, parent, (s, committer) ->
                CryptreeNode.initAndAuthoriseSigner(ownerKey, parent, pair, again, s, committer)
                        .thenApply(made -> { child[0] = made.right; return made.left; })).join();
        awaitUsageUpdate();
        assertRefusedForTheCap(() -> fillWriter(ownerKey, child[0]));
    }

    @Test
    public void aRefusedCommitDoesntReviveAWriterItsParentDropped() {
        UserContext owner = signUp();
        UserContext sharee = signUp();
        PeergosNetworkUtils.friendBetweenGroups(List.of(owner), List.of(sharee));
        Path dir = sharedDir(owner, "team", sharee);
        owner.setWriteShareQuota(dir, Optional.of(256L * KiB)).join();
        FileWrapper shared = sharee.getByPath(dir).join().get();
        PublicKeyHash ownerKey = shared.owner();
        SigningPrivateKeyAndPublicHash parent = shared.signingPair();
        SigningKeyPair pair = SigningKeyPair.random(crypto.random, crypto.signer);
        NetworkAccess net = network.clear();
        SigningPrivateKeyAndPublicHash[] child = new SigningPrivateKeyAndPublicHash[1];
        net.synchronizer.applyComplexUpdate(ownerKey, parent, (s, committer) ->
                CryptreeNode.initAndAuthoriseSigner(ownerKey, parent, pair, net, s, committer)
                        .thenApply(made -> { child[0] = made.right; return made.left; })).join();
        awaitUsageUpdate();
        // dropped by its parent, the child is forgotten; its pointer stays
        NetworkAccess net2 = network.clear();
        net2.synchronizer.applyComplexUpdate(ownerKey, parent, (s, committer) ->
                CryptreeNode.deAuthoriseSigner(ownerKey, parent, child[0].publicKeyHash, net2, s, committer)).join();
        awaitUsageUpdate();

        // a commit names it again and claims to create it, which its pointer refutes: refused, it takes back what it
        // registered, whatever pointer the child has
        NetworkAccess net3 = network.clear();
        try {
            net3.synchronizer.applyComplexUpdate(ownerKey, parent, (s, committer) -> IpfsTransaction.call(ownerKey, tid -> {
                CommittedWriterData current = s.get(parent);
                return OwnerProof.build(child[0], parent.publicKeyHash)
                        .thenCompose(proof -> current.props.get().addOwnedKeyAndCommit(ownerKey, parent, proof, current.hash, current.sequence, net3, committer, tid))
                        .thenCompose(named -> WriterData.createEmpty(ownerKey, child[0], net3.dhtClient, crypto.hasher, tid)
                                .thenCompose(wd -> committer.commit(ownerKey, child[0], wd, new CommittedWriterData(MaybeMultihash.empty(), Optional.empty(), Optional.empty()), tid))
                                .thenApply(named::mergeAndOverwriteWith));
            }, net3.dhtClient)).join();
            Assert.fail("A commit creating a writer that has a pointer already landed");
        } catch (Exception refused) {
            // refused for its stale claim, not anything else a commit can fail on
            String message = String.valueOf(peergos.shared.util.Exceptions.getRootCause(refused).getMessage());
            Assert.assertTrue(message, message.contains("PointerCAS") || message.contains("Pointers changed"));
        }
        assertCannotCommit(child[0], ownerKey, "a dropped writer that a refused commit named again");
    }

    @Test
    public void aWriterACommitIsStillCreatingSetsNoPointerOfItsOwn() throws Exception {
        UserContext owner = signUp();
        UserContext sharee = signUp();
        PeergosNetworkUtils.friendBetweenGroups(List.of(owner), List.of(sharee));
        Path dir = sharedDir(owner, "team", sharee);
        owner.setWriteShareQuota(dir, Optional.of(256L * KiB)).join();
        FileWrapper shared = sharee.getByPath(dir).join().get();
        PublicKeyHash ownerKey = shared.owner();
        SigningPrivateKeyAndPublicHash parent = shared.signingPair();
        SigningPrivateKeyAndPublicHash child = newKeyUnder(ownerKey, parent);

        // a commit creating the child registers it and is still writing its blocks: the child may set only the pointer
        // that commit carries, or a write of its own let in now would be forgotten with the commit if it is refused
        BulkCommitStorage.Registration creating = quotaFilter().registerNewWriter(ownerKey, parent.publicKeyHash, child.publicKeyHash, Optional.empty());
        assertCannotCommit(child, ownerKey, "a key the commit creating it hasn't landed");
        // the commit is refused and takes its registration back
        creating.undo();
        assertCannotCommit(child, ownerKey, "a key whose creating commit was refused");
    }

    @Test
    public void aRefusedCommitTakesBackAWriterItNamedUnderTwoParents() throws Exception {
        UserContext owner = signUp();
        UserContext sharee = signUp();
        PeergosNetworkUtils.friendBetweenGroups(List.of(owner), List.of(sharee));
        FileWrapper first = sharee.getByPath(sharedDir(owner, "first", sharee)).join().get();
        FileWrapper second = sharee.getByPath(sharedDir(owner, "second", sharee)).join().get();
        PublicKeyHash ownerKey = first.owner();
        SigningPrivateKeyAndPublicHash child = newKeyUnder(ownerKey, first.signingPair());

        // as a commit naming the child under both is registered, and then refused
        BulkCommitStorage.Registration underFirst = quotaFilter().registerNewWriter(ownerKey, first.signingPair().publicKeyHash, child.publicKeyHash, Optional.empty());
        BulkCommitStorage.Registration underSecond = quotaFilter().registerNewWriter(ownerKey, second.signingPair().publicKeyHash, child.publicKeyHash, Optional.empty());
        underSecond.undo();
        underFirst.undo();
        assertCannotCommit(child, ownerKey, "a key a refused commit named under two parents");
    }

    /** A writer a commit is still creating is provisional on every server, and on this one after a restart: the
     *  registration is in the store, not only in the memory of the server handling the commit */
    @Test
    public void aWriterACommitIsStillCreatingIsProvisionalOnEveryServer() throws Exception {
        UserContext owner = signUp();
        UserContext sharee = signUp();
        PeergosNetworkUtils.friendBetweenGroups(List.of(owner), List.of(sharee));
        FileWrapper shared = sharee.getByPath(sharedDir(owner, "team", sharee)).join().get();
        PublicKeyHash ownerKey = shared.owner();
        SigningPrivateKeyAndPublicHash parent = shared.signingPair();
        SigningPrivateKeyAndPublicHash child = newKeyUnder(ownerKey, parent);

        BulkCommitStorage.Registration creating = quotaFilter().registerNewWriter(ownerKey, parent.publicKeyHash, child.publicKeyHash, Optional.empty());
        SignedPointerUpdate another = new SignedPointerUpdate(child.publicKeyHash, child.secret.signMessage(new byte[] {1}).join());
        Assert.assertFalse("another server let in a pointer of a writer a commit is still creating",
                anotherServer().isOwnedWriter(ownerKey, another));
        creating.undo();
    }

    /** A registration nothing resolved, as the server handling its commit restarted before the commit landed or was
     *  refused, is taken back once it expires by whichever server finds it: its writer and edge would otherwise stay
     *  for good, the queue only dropping children a parent's earlier root named */
    @Test
    public void aRegistrationNothingResolvedIsTakenBackOnceItExpires() throws Exception {
        UserContext owner = signUp();
        UserContext sharee = signUp();
        PeergosNetworkUtils.friendBetweenGroups(List.of(owner), List.of(sharee));
        FileWrapper shared = sharee.getByPath(sharedDir(owner, "team", sharee)).join().get();
        PublicKeyHash ownerKey = shared.owner();
        SigningPrivateKeyAndPublicHash parent = shared.signingPair();
        SigningPrivateKeyAndPublicHash child = newKeyUnder(ownerKey, parent);

        quotaFilter().registerNewWriter(ownerKey, parent.publicKeyHash, child.publicKeyHash, Optional.empty());
        Assert.assertTrue(usageStore().getOwnedKeys(parent.publicKeyHash).contains(child.publicKeyHash));
        anotherServer().takeBackExpired(Long.MAX_VALUE);
        Assert.assertFalse(usageStore().getOwnedKeys(parent.publicKeyHash).contains(child.publicKeyHash));
        Assert.assertTrue(usageStore().getProvisional(child.publicKeyHash).isEmpty());
        assertCannotCommit(child, ownerKey, "a key whose creating commit never landed, once its registration expired");
    }

    /** A commit that landed, its server restarting before the commit's row went, doesn't hold its writer back: the
     *  writer's next write finds the commit landed, and the row goes */
    @Test
    public void aLandedCommitsLeftoverRowDoesntHoldItsWriterBack() throws Exception {
        UserContext owner = signUp();
        UserContext sharee = signUp();
        PeergosNetworkUtils.friendBetweenGroups(List.of(owner), List.of(sharee));
        FileWrapper shared = sharee.getByPath(sharedDir(owner, "team", sharee)).join().get();
        PublicKeyHash ownerKey = shared.owner();
        SigningPrivateKeyAndPublicHash parent = shared.signingPair();
        SigningPrivateKeyAndPublicHash child = newKeyUnder(ownerKey, parent);

        quotaFilter().registerNewWriter(ownerKey, parent.publicKeyHash, child.publicKeyHash, Optional.empty());
        // the commit lands, naming the child under its parent
        linkUnderOwner(ownerKey, child, parent);
        write(ownerKey, child, 1, KiB);
        Assert.assertTrue(usageStore().getProvisional(child.publicKeyHash).isEmpty());
    }

    /** As above, for a commit that carried the writer's own pointer update, as commits creating a writer do */
    @Test
    public void aLeftoverRowWhoseUpdateLandedDoesntHoldItsWriterBack() throws Exception {
        UserContext owner = signUp();
        UserContext sharee = signUp();
        PeergosNetworkUtils.friendBetweenGroups(List.of(owner), List.of(sharee));
        FileWrapper shared = sharee.getByPath(sharedDir(owner, "team", sharee)).join().get();
        PublicKeyHash ownerKey = shared.owner();
        SigningPrivateKeyAndPublicHash parent = shared.signingPair();
        SigningPrivateKeyAndPublicHash child = newKeyUnder(ownerKey, parent);
        linkUnderOwner(ownerKey, child, parent);
        write(ownerKey, child, 1, KiB);

        // the row of the commit that set that pointer, left behind
        byte[] landed = service.mutable.getPointer(ownerKey, child.publicKeyHash).join().get();
        usageStore().addProvisional(new WriterUsageStore.ProvisionalWriter(child.publicKeyHash, parent.publicKeyHash, ownerKey,
                true, Optional.of(landed), System.currentTimeMillis() + 600_000));
        write(ownerKey, child, 1, KiB);
        Assert.assertTrue(usageStore().getProvisional(child.publicKeyHash).isEmpty());
    }

    /** A row whose writer was never registered, its server stopping between the two, is taken back like any other */
    @Test
    public void anExpiredRowWhoseWriterWasNeverRegisteredIsTakenBack() throws Exception {
        UserContext owner = signUp();
        PublicKeyHash ownerKey = owner.signer.publicKeyHash;
        SigningPrivateKeyAndPublicHash home = owner.getUserRoot().join().signingPair();
        PublicKeyHash child = newKeyUnder(ownerKey, home).publicKeyHash;

        usageStore().addProvisional(new WriterUsageStore.ProvisionalWriter(child, home.publicKeyHash, ownerKey, true, Optional.empty(), 0));
        anotherServer().takeBackExpired(System.currentTimeMillis());
        Assert.assertTrue(usageStore().getProvisional(child).isEmpty());
    }

    /** An expired registration that can't be taken back yet still holds its writer back: its commit never landed */
    @Test
    public void anExpiredRegistrationNotYetTakenBackStillHoldsItsWriterBack() throws Exception {
        UserContext owner = signUp();
        PublicKeyHash ownerKey = owner.signer.publicKeyHash;
        SigningPrivateKeyAndPublicHash home = owner.getUserRoot().join().signingPair();
        SigningPrivateKeyAndPublicHash child = newKeyUnder(ownerKey, home);

        quotaFilter().registerNewWriter(ownerKey, home.publicKeyHash, child.publicKeyHash, Optional.empty());
        // its server stopped long ago, and the store can't take the edge back for now
        usageStore().removeProvisional(child.publicKeyHash, home.publicKeyHash);
        usageStore().addProvisional(new WriterUsageStore.ProvisionalWriter(child.publicKeyHash, home.publicKeyHash, ownerKey,
                true, Optional.empty(), 0));
        SpaceCheckingKeyFilter server = serverOver(failing("removeOwnedKey", new boolean[] {true}));
        SignedPointerUpdate another = new SignedPointerUpdate(child.publicKeyHash, child.secret.signMessage(new byte[] {1}).join());
        Assert.assertFalse("a pointer of a writer whose commit never landed", server.isOwnedWriter(ownerKey, another));
        anotherServer().takeBackExpired(System.currentTimeMillis());
    }

    /** A writer whose own update was set on its own, its commit never landing as its server stopped, isn't let loose:
     *  its parent doesn't name it, and once its row expires it is taken back */
    @Test
    public void aWriterWhoseOwnUpdateLandedWithoutItsCommitStaysProvisional() throws Exception {
        UserContext owner = signUp();
        PublicKeyHash ownerKey = owner.signer.publicKeyHash;
        SigningPrivateKeyAndPublicHash home = owner.getUserRoot().join().signingPair();
        SigningPrivateKeyAndPublicHash child = newKeyUnder(ownerKey, home);

        quotaFilter().registerNewWriter(ownerKey, home.publicKeyHash, child.publicKeyHash, Optional.empty());
        // the update its commit carries for it is set on its own, and the commit's server stops before it lands
        usageStore().removeProvisional(child.publicKeyHash, home.publicKeyHash);
        write(ownerKey, child, 1, KiB);
        byte[] own = service.mutable.getPointer(ownerKey, child.publicKeyHash).join().get();
        usageStore().addProvisional(new WriterUsageStore.ProvisionalWriter(child.publicKeyHash, home.publicKeyHash, ownerKey,
                true, Optional.of(own), System.currentTimeMillis() + 600_000));

        SpaceCheckingKeyFilter another = anotherServer();
        SignedPointerUpdate next = new SignedPointerUpdate(child.publicKeyHash, child.secret.signMessage(new byte[] {1}).join());
        Assert.assertFalse("a pointer of a writer its parent doesn't name", another.isOwnedWriter(ownerKey, next));
        another.takeBackExpired(Long.MAX_VALUE);
        Assert.assertTrue(usageStore().getProvisional(child.publicKeyHash).isEmpty());
        assertCannotCommit(child, ownerKey, "a key whose creating commit never landed, once its registration expired");
    }

    /** A writer whose commit is refused while one of its pointers is being checked isn't let in: the undo forgets it
     *  before its rows go, so a check that finds no rows finds it forgotten */
    @Test
    public void aWriterTakenBackWhileItsPointerIsCheckedIsntLetIn() throws Exception {
        UserContext owner = signUp();
        PublicKeyHash ownerKey = owner.signer.publicKeyHash;
        SigningPrivateKeyAndPublicHash home = owner.getUserRoot().join().signingPair();
        SigningPrivateKeyAndPublicHash child = newKeyUnder(ownerKey, home);

        BulkCommitStorage.Registration creating = quotaFilter().registerNewWriter(ownerKey, home.publicKeyHash, child.publicKeyHash, Optional.empty());
        UsageStore store = usageStore();
        boolean[] refused = {false};
        // the commit is refused, and its registration taken back, just as the check reads the writer's rows
        UsageStore refusedAsRowsAreRead = (UsageStore) java.lang.reflect.Proxy.newProxyInstance(UsageStore.class.getClassLoader(),
                new Class<?>[] {UsageStore.class}, (proxy, m, args) -> {
                    if (m.getName().equals("getProvisional") && ! refused[0]) {
                        refused[0] = true;
                        creating.undo();
                    }
                    try {
                        return m.invoke(store, args);
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
        SignedPointerUpdate another = new SignedPointerUpdate(child.publicKeyHash, child.secret.signMessage(new byte[] {1}).join());
        Assert.assertFalse("a pointer of a writer its refused commit took back",
                serverOver(refusedAsRowsAreRead).isOwnedWriter(ownerKey, another));
        Assert.assertTrue(refused[0]);
    }

    /** A commit that landed stands, whether or not its row can be removed then: the row goes once it is found landed
     *  or expires */
    @Test
    public void aLandedCommitStandsIfItsRowCantBeRemoved() throws Exception {
        UserContext owner = signUp();
        PublicKeyHash ownerKey = owner.signer.publicKeyHash;
        SigningPrivateKeyAndPublicHash home = owner.getUserRoot().join().signingPair();
        SigningPrivateKeyAndPublicHash child = newKeyUnder(ownerKey, home);

        boolean[] fail = {false};
        BulkCommitStorage.Registration registered = serverOver(failing("removeProvisional", fail))
                .registerNewWriter(ownerKey, home.publicKeyHash, child.publicKeyHash, Optional.empty());
        fail[0] = true;
        registered.landed();
        fail[0] = false;
        Assert.assertEquals("the row stays, to go once it is found landed or expires", 1,
                usageStore().getProvisional(child.publicKeyHash).size());
    }

    /** A new writer's row taken back takes the rows its commit left under other parents with it, whichever expires
     *  first: the writer would otherwise stay owned through one of their edges, unrestricted, until that row expired */
    @Test
    public void aNewWritersRowTakesItsCommitsOtherRowsBackWithIt() throws Exception {
        UserContext owner = signUp();
        UserContext sharee = signUp();
        PeergosNetworkUtils.friendBetweenGroups(List.of(owner), List.of(sharee));
        FileWrapper first = sharee.getByPath(sharedDir(owner, "first", sharee)).join().get();
        FileWrapper second = sharee.getByPath(sharedDir(owner, "second", sharee)).join().get();
        PublicKeyHash ownerKey = first.owner();
        PublicKeyHash firstParent = first.signingPair().publicKeyHash, secondParent = second.signingPair().publicKeyHash;
        SigningPrivateKeyAndPublicHash child = newKeyUnder(ownerKey, first.signingPair());

        // a commit naming the child under both is registered, and its server stops; the new writer's row expires first
        quotaFilter().registerNewWriter(ownerKey, firstParent, child.publicKeyHash, Optional.empty());
        quotaFilter().registerNewWriter(ownerKey, secondParent, child.publicKeyHash, Optional.empty());
        usageStore().removeProvisional(child.publicKeyHash, firstParent);
        usageStore().addProvisional(new WriterUsageStore.ProvisionalWriter(child.publicKeyHash, firstParent, ownerKey,
                true, Optional.empty(), 0));
        anotherServer().takeBackExpired(System.currentTimeMillis());
        Assert.assertTrue(usageStore().getProvisional(child.publicKeyHash).isEmpty());
        assertCannotCommit(child, ownerKey, "a key a commit that never landed named under two parents");
    }

    /** The sharee names a key the server won't read in the shared folder's root: the folder's own block, which needs a BAT */
    private static void nameAnUnreadableKey(UserContext sharee, FileWrapper shared) {
        PublicKeyHash ownerKey = shared.owner();
        SigningPrivateKeyAndPublicHash writer = shared.signingPair();
        OwnerProof unreadable = new OwnerProof(new PublicKeyHash((Cid) shared.getPointer().fileAccess.committedHash().get()), new byte[96]);
        NetworkAccess net = sharee.network.clear();
        net.synchronizer.applyComplexUpdate(ownerKey, writer, (s, committer) -> IpfsTransaction.call(ownerKey, tid -> {
            CommittedWriterData current = s.get(writer);
            return committer.commit(ownerKey, writer, current.props.get().addNamedKey("unreadable", unreadable), current, tid);
        }, net.dhtClient)).join();
    }

    /** A commit creating a writer is refused, its parent's committed root naming a key the server won't read: the
     *  registration goes with it, as that root doesn't name the writer, whatever else it names */
    @Test
    public void aRefusedCommitTakesBackItsWriterUnderARootNamingAnUnreadableKey() throws Exception {
        UserContext owner = signUp();
        UserContext sharee = signUp();
        PeergosNetworkUtils.friendBetweenGroups(List.of(owner), List.of(sharee));
        FileWrapper shared = sharee.getByPath(sharedDir(owner, "team", sharee)).join().get();
        PublicKeyHash ownerKey = shared.owner();
        SigningPrivateKeyAndPublicHash parent = shared.signingPair();
        CommittedWriterData before = network.clear().synchronizer.getValue(ownerKey, parent.publicKeyHash).join().get(parent);
        nameAnUnreadableKey(sharee, shared);
        awaitUsageUpdate();

        // named under the parent's root from before that key, which vouches for it, over a pointer that has moved on
        SigningKeyPair pair = SigningKeyPair.random(crypto.random, crypto.signer);
        NetworkAccess net = network.clear();
        SigningPrivateKeyAndPublicHash[] child = new SigningPrivateKeyAndPublicHash[1];
        try {
            net.synchronizer.applyComplexUpdate(ownerKey, parent, (s, committer) -> IpfsTransaction.call(ownerKey, tid ->
                    parent.secret.signMessage(pair.publicSigningKey.serialize())
                            .thenCompose(sig -> net.dhtClient.putSigningKey(sig, ownerKey, parent.publicKeyHash, pair.publicSigningKey, tid))
                            .thenCompose(childHash -> {
                                child[0] = new SigningPrivateKeyAndPublicHash(childHash, pair.secretSigningKey);
                                return OwnerProof.build(child[0], parent.publicKeyHash);
                            })
                            .thenCompose(proof -> before.props.get().addOwnedKeyAndCommit(ownerKey, parent, proof, before.hash, before.sequence, net, committer, tid))
                            .thenCompose(named -> WriterData.createEmpty(ownerKey, child[0], net.dhtClient, crypto.hasher, tid)
                                    .thenCompose(wd -> committer.commit(ownerKey, child[0], wd, new CommittedWriterData(MaybeMultihash.empty(), Optional.empty(), Optional.empty()), tid))
                                    .thenApply(named::mergeAndOverwriteWith)), net.dhtClient)).join();
            Assert.fail("A commit over a moved pointer landed");
        } catch (Exception refused) {
            String message = String.valueOf(peergos.shared.util.Exceptions.getRootCause(refused).getMessage());
            Assert.assertTrue(message, message.contains("PointerCAS") || message.contains("Pointers changed"));
        }
        Assert.assertFalse(usageStore().getOwnedKeys(parent.publicKeyHash).contains(child[0].publicKeyHash));
        assertCannotCommit(child[0], ownerKey, "a key whose creating commit was refused");
    }

    /** As above, for a registration taken back once it expires */
    @Test
    public void anExpiredRegistrationUnderARootNamingAnUnreadableKeyIsTakenBack() throws Exception {
        UserContext owner = signUp();
        UserContext sharee = signUp();
        PeergosNetworkUtils.friendBetweenGroups(List.of(owner), List.of(sharee));
        FileWrapper shared = sharee.getByPath(sharedDir(owner, "team", sharee)).join().get();
        PublicKeyHash ownerKey = shared.owner();
        SigningPrivateKeyAndPublicHash parent = shared.signingPair();
        nameAnUnreadableKey(sharee, shared);
        awaitUsageUpdate();
        SigningPrivateKeyAndPublicHash child = newKeyUnder(ownerKey, parent);

        quotaFilter().registerNewWriter(ownerKey, parent.publicKeyHash, child.publicKeyHash, Optional.empty());
        anotherServer().takeBackExpired(Long.MAX_VALUE);
        Assert.assertFalse(usageStore().getOwnedKeys(parent.publicKeyHash).contains(child.publicKeyHash));
        Assert.assertTrue(usageStore().getProvisional(child.publicKeyHash).isEmpty());
    }

    /** As above, for another pointer of a writer a commit is still creating: refused, rather than failed on the parent's root */
    @Test
    public void aWriterStillBeingCreatedUnderARootNamingAnUnreadableKeySetsNoPointerOfItsOwn() throws Exception {
        UserContext owner = signUp();
        UserContext sharee = signUp();
        PeergosNetworkUtils.friendBetweenGroups(List.of(owner), List.of(sharee));
        FileWrapper shared = sharee.getByPath(sharedDir(owner, "team", sharee)).join().get();
        PublicKeyHash ownerKey = shared.owner();
        SigningPrivateKeyAndPublicHash parent = shared.signingPair();
        nameAnUnreadableKey(sharee, shared);
        awaitUsageUpdate();
        SigningPrivateKeyAndPublicHash child = newKeyUnder(ownerKey, parent);

        BulkCommitStorage.Registration creating = quotaFilter().registerNewWriter(ownerKey, parent.publicKeyHash, child.publicKeyHash, Optional.empty());
        SignedPointerUpdate another = new SignedPointerUpdate(child.publicKeyHash, child.secret.signMessage(new byte[] {1}).join());
        try {
            Assert.assertFalse(quotaFilter().isOwnedWriter(ownerKey, another));
        } finally {
            creating.undo();
        }
    }

    /** As above, the sharee pointing the root's owned key champ, rather than one key, at a block the server won't read */
    @Test
    public void anExpiredRegistrationUnderAnUnreadableOwnedKeyChampIsTakenBack() throws Exception {
        UserContext owner = signUp();
        UserContext sharee = signUp();
        PeergosNetworkUtils.friendBetweenGroups(List.of(owner), List.of(sharee));
        FileWrapper shared = sharee.getByPath(sharedDir(owner, "team", sharee)).join().get();
        PublicKeyHash ownerKey = shared.owner();
        SigningPrivateKeyAndPublicHash parent = shared.signingPair();
        Cid unreadable = (Cid) shared.getPointer().fileAccess.committedHash().get();
        NetworkAccess net = sharee.network.clear();
        net.synchronizer.applyComplexUpdate(ownerKey, parent, (s, committer) -> IpfsTransaction.call(ownerKey, tid -> {
            CommittedWriterData current = s.get(parent);
            return committer.commit(ownerKey, parent, current.props.get().withOwnedRoot(unreadable), current, tid);
        }, net.dhtClient)).join();
        SigningPrivateKeyAndPublicHash child = newKeyUnder(ownerKey, parent);

        quotaFilter().registerNewWriter(ownerKey, parent.publicKeyHash, child.publicKeyHash, Optional.empty());
        anotherServer().takeBackExpired(Long.MAX_VALUE);
        Assert.assertFalse(usageStore().getOwnedKeys(parent.publicKeyHash).contains(child.publicKeyHash));
        Assert.assertTrue(usageStore().getProvisional(child.publicKeyHash).isEmpty());
    }

    /** A writer whose commit landed, its row left behind by a server that stopped before removing it, isn't forgotten,
     *  its cap with it, because its parent's root can't be read for a moment as the row is found expired */
    @Test
    public void aReadFailingAsALandedWritersRowExpiresKeepsItsCap() throws Exception {
        UserContext owner = signUp();
        UserContext sharee = signUp();
        PeergosNetworkUtils.friendBetweenGroups(List.of(owner), List.of(sharee));
        Path dir = sharedDir(owner, "team", sharee);
        owner.setWriteShareQuota(dir, Optional.of(256L * KiB)).join();
        awaitUsageUpdate();
        FileWrapper shared = sharee.getByPath(dir).join().get();
        PublicKeyHash ownerKey = shared.owner();
        PublicKeyHash writer = shared.signingPair().publicKeyHash;
        Set<PublicKeyHash> parents = usageStore().getParents(writer);
        Assert.assertEquals(1, parents.size());
        PublicKeyHash parent = parents.iterator().next();
        long now = System.currentTimeMillis();
        usageStore().addProvisional(new WriterUsageStore.ProvisionalWriter(writer, parent, ownerKey, true, Optional.empty(), now - 1));

        Cid parentRoot = (Cid) service.mutable.getPointerTarget(ownerKey, parent, service.storage).join().updated.get();
        boolean[] fail = {true};
        serverOver(usageStore(), unreadable(parentRoot, fail)).takeBackExpired(now);
        fail[0] = false;

        Assert.assertTrue(anotherServer().isOwnedWriter(ownerKey, writer));
        Assert.assertEquals(Optional.of(256L * KiB), usageStore().getWriterQuota(writer));
        assertRefusedForTheCap(() -> upload(sharee, dir, "big", 1024 * KiB));
        // and its row goes once the root can be read
        anotherServer().takeBackExpired(now);
        Assert.assertTrue(usageStore().getProvisional(writer).isEmpty());
        Assert.assertTrue(usageStore().getOwnedKeys(parent).contains(writer));
    }

    /** The server's blocks, with one of them failing to be read while the flag is set */
    private static DeletableContentAddressedStorage unreadable(Cid block, boolean[] fail) throws Exception {
        DeletableContentAddressedStorage dht = (DeletableContentAddressedStorage) filterField("dht");
        return (DeletableContentAddressedStorage) java.lang.reflect.Proxy.newProxyInstance(
                DeletableContentAddressedStorage.class.getClassLoader(), new Class<?>[] {DeletableContentAddressedStorage.class},
                (proxy, m, args) -> {
                    if (fail[0] && args != null && Arrays.asList(args).contains(block)) {
                        IllegalStateException unreachable = new IllegalStateException("Storage unreachable");
                        if (m.getReturnType() == java.util.concurrent.CompletableFuture.class)
                            return Futures.errored(unreachable);
                        throw unreachable;
                    }
                    try {
                        return m.invoke(dht, args);
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    /** A quota filter over the server's store with nothing in memory: another server's, or this one's after a restart */
    private static SpaceCheckingKeyFilter anotherServer() throws Exception {
        return serverOver(usageStore());
    }

    private static SpaceCheckingKeyFilter serverOver(UsageStore store) throws Exception {
        return serverOver(store, (DeletableContentAddressedStorage) filterField("dht"));
    }

    private static SpaceCheckingKeyFilter serverOver(UsageStore store, DeletableContentAddressedStorage dht) throws Exception {
        SpaceCheckingKeyFilter filter = new SpaceCheckingKeyFilter((peergos.shared.corenode.CoreNode) filterField("core"),
                (MutablePointers) filterField("mutable"), dht,
                (Hasher) filterField("hasher"), (QuotaAdmin) filterField("quotaAdmin"), store,
                (Long) filterField("quotaUploadLimitSeconds"));
        // its sweep stopped, so that only what a test does here takes registrations back
        java.lang.reflect.Field sweeper = SpaceCheckingKeyFilter.class.getDeclaredField("sweeper");
        sweeper.setAccessible(true);
        ((java.util.concurrent.ExecutorService) sweeper.get(filter)).shutdownNow();
        return filter;
    }

    /** The server's store, with one of its methods failing while the flag is set */
    private static UsageStore failing(String method, boolean[] fail) throws Exception {
        UsageStore store = usageStore();
        return (UsageStore) java.lang.reflect.Proxy.newProxyInstance(UsageStore.class.getClassLoader(),
                new Class<?>[] {UsageStore.class}, (proxy, m, args) -> {
                    if (fail[0] && m.getName().equals(method))
                        throw new IllegalStateException("Store unavailable");
                    try {
                        return m.invoke(store, args);
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    @Test
    public void anEdgeFromAnotherUsersWriterDoesntKeepARevokedKey() throws Exception {
        UserContext owner = signUp();
        UserContext sharee = signUp();
        PeergosNetworkUtils.friendBetweenGroups(List.of(owner), List.of(sharee));
        Path dir = sharedDir(owner, "team", sharee);
        FileWrapper shared = sharee.getByPath(dir).join().get();
        linkUnder(sharee, shared.signingPair(), sharee.signer);
        awaitUsageUpdate();
        // the edge an older version recorded for that
        usageStore().addOwnedKey(sharee.signer.publicKeyHash, shared.signingPair().publicKeyHash);

        owner.unShareWriteAccessWith(dir, Set.of(sharee.username)).join();
        awaitUsageUpdate();
        assertCannotCommit(shared.signingPair(), shared.owner(), "a revoked key another user's writer had an edge to");
    }

    @Test
    public void aShareeDroppingTheirOwnWriterForgetsNothingOfTheOwners() throws Exception {
        UserContext owner = signUp();
        UserContext sharee = signUp();
        UserContext third = signUp();
        PeergosNetworkUtils.friendBetweenGroups(List.of(owner), List.of(sharee));
        PeergosNetworkUtils.friendBetweenGroups(List.of(sharee), List.of(third));
        Path dir = sharedDir(owner, "team", sharee);
        owner.setWriteShareQuota(dir, Optional.of(256L * KiB)).join();
        upload(owner, dir, "ownersfile", 100 * KiB);
        // the sharee names the owner's key under a writing space of their own, then drops that space
        Path mine = sharedDir(sharee, "mine", third);
        FileWrapper team = sharee.getByPath(dir).join().get();
        linkUnder(sharee, team.signingPair(), sharee.getByPath(mine).join().get().signingPair());
        awaitUsageUpdate();
        long counted = owner.getSpaceUsage(false).join();
        sharee.unShareWriteAccessWith(mine, Set.of(third.username)).join();
        awaitUsageUpdate();

        // the owner's key is still theirs, counted, and capped
        PublicKeyHash k = team.signingPair().publicKeyHash;
        Assert.assertEquals(owner.username, usageStore().getOwner(k));
        Assert.assertEquals(Optional.of(256L * KiB), usageStore().getWriterQuota(k));
        long after = owner.getSpaceUsage(false).join();
        Assert.assertTrue("The owner's data was uncounted: " + after + " of " + counted, after >= counted - 10 * KiB);
        assertRefusedForTheCap(() -> upload(sharee, dir, "big", 1024 * KiB));
    }

    @Test
    public void anEdgeNoProcessedRootNamesGoesWithItsParent() throws Exception {
        UserContext owner = signUp();
        UserContext sharee = signUp();
        PeergosNetworkUtils.friendBetweenGroups(List.of(owner), List.of(sharee));
        Path dir = sharedDir(owner, "team", sharee);
        owner.setWriteShareQuota(dir, Optional.of(256L * KiB)).join();
        awaitUsageUpdate();
        FileWrapper shared = sharee.getByPath(dir).join().get();
        PublicKeyHash ownerKey = shared.owner();
        SigningPrivateKeyAndPublicHash parent = shared.signingPair();
        SigningPrivateKeyAndPublicHash child = newKeyUnder(ownerKey, parent);

        // the sharee sets a root naming a new key, which the server links on arrival but can't size (it links to a
        // block that isn't there), then sets the old root back: the queue never processes a root naming the key
        CommittedWriterData before = WriterData.getWriterData(ownerKey, parent.publicKeyHash, service.mutable, service.storage).join();
        OwnerProof proof = OwnerProof.build(child, parent.publicKeyHash).join();
        byte[] absent = new byte[100];
        random.nextBytes(absent);
        Cid missing = crypto.hasher.hash(absent, true).join();
        TransactionId tid = service.storage.startTransaction(ownerKey).join();
        Cid naming = service.storage.put(ownerKey, parent, before.props.get().addNamedKey("unsized", proof).withChamp(missing).serialize(),
                crypto.hasher, tid).join();
        PointerUpdate toNaming = new PointerUpdate(before.hash, MaybeMultihash.of(naming), PointerUpdate.increment(before.sequence));
        service.mutable.setPointer(ownerKey, parent, toNaming).join();
        awaitUsageUpdate();
        Cid back = service.storage.put(ownerKey, parent, before.props.get().serialize(), crypto.hasher, tid).join();
        service.mutable.setPointer(ownerKey, parent, new PointerUpdate(MaybeMultihash.of(naming), MaybeMultihash.of(back),
                PointerUpdate.increment(toNaming.sequence))).join();
        awaitUsageUpdate();

        // revoked, the folder's writer goes, and the key linked under it with it
        owner.unShareWriteAccessWith(dir, Set.of(sharee.username)).join();
        awaitUsageUpdate();
        assertCannotCommit(child, ownerKey, "a key linked under a revoked folder by a root the queue never processed");
    }

    @Test
    public void aKeyNamedUnderItselfDoesntOutliveItsRevocation() throws Exception {
        UserContext owner = signUp();
        UserContext sharee = signUp();
        PeergosNetworkUtils.friendBetweenGroups(List.of(owner), List.of(sharee));
        Path dir = sharedDir(owner, "team", sharee);
        FileWrapper shared = sharee.getByPath(dir).join().get();
        linkUnderOwner(shared.owner(), shared.signingPair(), shared.signingPair());
        awaitUsageUpdate();
        // and the edge an older version recorded for that
        usageStore().addOwnedKey(shared.signingPair().publicKeyHash, shared.signingPair().publicKeyHash);

        owner.unShareWriteAccessWith(dir, Set.of(sharee.username)).join();
        awaitUsageUpdate();
        assertCannotCommit(shared.signingPair(), shared.owner(), "a revoked key named under itself");
        // nor does walking up from it to its owner go round for ever
        java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try {
                return usageStore().getAllTargets(owner.username);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }).get(30, java.util.concurrent.TimeUnit.SECONDS);
    }

    @Test
    public void revokedKeysNamedUnderEachOtherDontKeepEachOther() {
        UserContext owner = signUp();
        UserContext sharee = signUp();
        PeergosNetworkUtils.friendBetweenGroups(List.of(owner), List.of(sharee));
        Path a = sharedDir(owner, "a", sharee);
        Path b = sharedDir(owner, "b", sharee);
        FileWrapper af = sharee.getByPath(a).join().get();
        FileWrapper bf = sharee.getByPath(b).join().get();
        linkUnderOwner(af.owner(), af.signingPair(), bf.signingPair());
        linkUnderOwner(af.owner(), bf.signingPair(), af.signingPair());
        awaitUsageUpdate();

        owner.unShareWriteAccessWith(a, Set.of(sharee.username)).join();
        awaitUsageUpdate();
        owner.unShareWriteAccessWith(b, Set.of(sharee.username)).join();
        awaitUsageUpdate();
        assertCannotCommit(af.signingPair(), af.owner(), "a revoked key named under another revoked key it names");
        assertCannotCommit(bf.signingPair(), af.owner(), "a revoked key named under another revoked key it names");
    }

    @Test
    public void aRefusedCommitLeavesTheWritesInFlightReserved() throws Exception {
        UserContext owner = signUp();
        UserContext sharee = signUp();
        PeergosNetworkUtils.friendBetweenGroups(List.of(owner), List.of(sharee));
        Path dir = sharedDir(owner, "team", sharee);
        FileWrapper other = sharee.getByPath(sharedDir(owner, "other", sharee)).join().get();
        owner.setWriteShareQuota(dir, Optional.of(1024L * KiB)).join();
        awaitUsageUpdate();
        FileWrapper shared = sharee.getByPath(dir).join().get();
        PublicKeyHash ownerKey = shared.owner();
        SigningPrivateKeyAndPublicHash capped = shared.signingPair();
        SigningPrivateKeyAndPublicHash elsewhere = other.signingPair();

        // 900 KiB in flight under the cap, as a bulk commit reserves it before writing its blocks
        SpaceCheckingKeyFilter filter = quotaFilter();
        Assert.assertTrue(filter.allowWrite(ownerKey, capped.publicKeyHash, 900 * KiB));
        // a commit names the capped writer under another writer and claims to create it, which its pointer refutes
        NetworkAccess net = network.clear();
        try {
            net.synchronizer.applyComplexUpdate(ownerKey, elsewhere, (s, committer) -> IpfsTransaction.call(ownerKey, tid -> {
                CommittedWriterData current = s.get(elsewhere);
                return OwnerProof.build(capped, elsewhere.publicKeyHash)
                        .thenCompose(proof -> current.props.get().addOwnedKeyAndCommit(ownerKey, elsewhere, proof, current.hash, current.sequence, net, committer, tid))
                        .thenCompose(named -> WriterData.createEmpty(ownerKey, capped, net.dhtClient, crypto.hasher, tid)
                                .thenCompose(wd -> committer.commit(ownerKey, capped, wd, new CommittedWriterData(MaybeMultihash.empty(), Optional.empty(), Optional.empty()), tid))
                                .thenApply(named::mergeAndOverwriteWith));
            }, net.dhtClient)).join();
            Assert.fail("A commit creating a writer that has a pointer already landed");
        } catch (Exception refused) {
            // refused for its stale claim, not anything else a commit can fail on
            String message = String.valueOf(peergos.shared.util.Exceptions.getRootCause(refused).getMessage());
            Assert.assertTrue(message, message.contains("PointerCAS") || message.contains("Pointers changed"));
        }

        // the write in flight is still reserved, so a second as large doesn't fit beside it
        long expected = writerQuotas().getUsage(capped.publicKeyHash).expectedUsage();
        Assert.assertTrue("The write in flight is no longer reserved: " + expected, expected >= 900 * KiB);
        assertRefusedForTheCap(() -> filter.allowWrite(ownerKey, capped.publicKeyHash, 900 * KiB));
    }

    @Test
    public void aWriterNamedAgainIsCountedAgainWhenTheQueueIsBehind() throws Exception {
        UserContext owner = signUp();
        UserContext sharee = signUp();
        PeergosNetworkUtils.friendBetweenGroups(List.of(owner), List.of(sharee));
        Path dir = sharedDir(owner, "team", sharee);
        owner.getByPath(dir).join().get().mkdir("nested", owner.network, false, owner.mirrorBatId(), crypto).join();
        Path nested = dir.resolve("nested");
        owner.shareWriteAccessWith(nested, Set.of(sharee.username)).join();
        owner.setWriteShareQuota(dir, Optional.of(1024L * KiB)).join();
        upload(sharee, nested, "file", 600 * KiB);
        awaitUsageUpdate();
        long counted = owner.getWriteShareQuota(dir).join().used;
        Assert.assertTrue(counted >= 600 * KiB);

        // the sharee drops the nested writing space from the parent, and the server forgets it
        PublicKeyHash ownerKey = sharee.getByPath(dir).join().get().owner();
        SigningPrivateKeyAndPublicHash parent = sharee.getByPath(dir).join().get().signingPair();
        SigningPrivateKeyAndPublicHash child = sharee.getByPath(nested).join().get().signingPair();
        NetworkAccess net = network.clear();
        net.synchronizer.applyComplexUpdate(ownerKey, parent, (s, committer) ->
                CryptreeNode.deAuthoriseSigner(ownerKey, parent, child.publicKeyHash, net, s, committer)).join();
        awaitUsageUpdate();

        // and names it again, its data still there, while the usage queue is busy with the parent
        java.lang.reflect.Method lockOf = SpaceCheckingKeyFilter.class.getDeclaredMethod("getWriterLock", PublicKeyHash.class);
        lockOf.setAccessible(true);
        Object parentLock = lockOf.invoke(null, parent.publicKeyHash);
        java.util.concurrent.CountDownLatch held = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch named = new java.util.concurrent.CountDownLatch(1);
        Thread busy = new Thread(() -> {
            synchronized (parentLock) {
                held.countDown();
                try {
                    named.await(20, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException e) {}
            }
        });
        busy.start();
        held.await();
        NetworkAccess net2 = network.clear();
        OwnerProof proof = OwnerProof.build(child, parent.publicKeyHash).join();
        IpfsTransaction.call(ownerKey, tid -> net2.synchronizer.applyComplexUpdate(ownerKey, parent, (s, committer) -> {
            CommittedWriterData current = s.get(parent);
            return current.props.get().addOwnedKeyAndCommit(ownerKey, parent, proof, current.hash, current.sequence, net2, committer, tid);
        }), net2.dhtClient).join();
        named.countDown();
        busy.join();
        awaitUsageUpdate();

        long recounted = owner.getWriteShareQuota(dir).join().used;
        Assert.assertTrue("The data of a writer named again isn't counted under the cap: " + recounted + " of " + counted,
                recounted >= counted - 50 * KiB);
        assertRefusedForTheCap(() -> upload(sharee, dir, "more", 600 * KiB));
    }

    /** Per block signatures, as a client's own uploads carry. */
    private static java.util.concurrent.CompletableFuture<List<byte[]>> signed(SigningPrivateKeyAndPublicHash signer, List<byte[]> blocks) {
        return Futures.combineAllInOrder(blocks.stream()
                .map(b -> crypto.hasher.sha256(b).thenCompose(h -> signer.secret.signMessage(h)))
                .toList());
    }

    @Test
    public void onlyTheOwnerCanSetACap() {
        UserContext owner = signUp();
        UserContext sharee = signUp();
        PeergosNetworkUtils.friendBetweenGroups(List.of(owner), List.of(sharee));
        Path dir = sharedDir(owner, "team", sharee);
        FileWrapper shared = sharee.getByPath(dir).join().get();
        PublicKeyHash writer = shared.writer();
        PublicKeyHash ownerKey = owner.signer.publicKeyHash;

        // signed by someone else's identity
        WriterQuotaRequest req = new WriterQuotaRequest(ownerKey, writer, Optional.of(1L), System.currentTimeMillis());
        byte[] signedBySharee = sharee.signer.secret.signMessage(req.serialize()).join();
        assertRejected(() -> network.spaceUsage.setWriterQuota(ownerKey, signedBySharee).join());
        // signed by the writer key
        byte[] signedByWriter = shared.signingPair().secret.signMessage(req.serialize()).join();
        assertRejected(() -> network.spaceUsage.setWriterQuota(ownerKey, signedByWriter).join());
        // the identity can't be capped
        WriterQuotaRequest identityReq = new WriterQuotaRequest(ownerKey, ownerKey, Optional.of(1L), System.currentTimeMillis());
        byte[] signedIdentity = owner.signer.secret.signMessage(identityReq.serialize()).join();
        assertRejected(() -> network.spaceUsage.setWriterQuota(ownerKey, signedIdentity).join());

        // a replayed request is rejected
        byte[] signed = owner.signer.secret.signMessage(req.serialize()).join();
        Assert.assertTrue(network.spaceUsage.setWriterQuota(ownerKey, signed).join());
        owner.setWriteShareQuota(dir, Optional.empty()).join();
        assertRejected(() -> network.spaceUsage.setWriterQuota(ownerKey, signed).join());
        Assert.assertTrue(owner.getWriteShareQuotas().join().isEmpty());

        // a sharee can only query space with the writer key
        assertRejected(() -> network.spaceUsage.getWriterUsage(ownerKey, writer, sharee.signer.secret).join());

        // a signed request can't be replayed for another path
        byte[] forQuotas = new TimeLimitedClient.SignedRequest(SpaceUsage.writerQuotasPath(), System.currentTimeMillis())
                .sign(owner.signer.secret).join();
        Assert.assertNotNull(network.spaceUsage.getWriterQuotas(ownerKey, forQuotas).join());
        assertRejected(() -> network.spaceUsage.getWriterUsage(ownerKey, writer, forQuotas).join());
        byte[] timeOnly = TimeLimitedClient.signNow(owner.signer.secret).join();
        assertRejected(() -> network.spaceUsage.getWriterQuotas(ownerKey, timeOnly).join());
    }

    private static void assertRejected(Runnable r) {
        try {
            r.run();
            Assert.fail("Request should have been rejected");
        } catch (Exception expected) {}
    }

    @Test
    public void capOnRotatedNestedSpaceCountsItsContents() {
        UserContext owner = signUp();
        UserContext outer = signUp();
        UserContext inner = signUp();
        PeergosNetworkUtils.friendBetweenGroups(List.of(owner), List.of(outer, inner));
        Path dir = sharedDir(owner, "team", outer);
        owner.getByPath(dir).join().get().mkdir("nested", owner.network, false, owner.mirrorBatId(), crypto).join();
        Path nested = dir.resolve("nested");
        owner.getByPath(nested).join().get().mkdir("sub", owner.network, false, owner.mirrorBatId(), crypto).join();
        upload(owner, nested.resolve("sub"), "subfile", 20 * KiB);
        owner.shareWriteAccessWith(nested, Set.of(inner.username)).join();
        upload(owner, nested, "file", 200 * KiB);
        awaitUsageUpdate();
        // rotate the nested space on its own first
        owner.unShareWriteAccessWith(nested, Set.of(inner.username)).join();
        upload(owner, nested, "file2", 200 * KiB);
        owner.setWriteShareQuota(nested, Optional.of(4096L * KiB)).join();
        upload(outer, nested.resolve("sub"), "fromOuter", 100 * KiB);
        owner.setWriteShareQuota(nested, Optional.empty()).join();
        awaitUsageUpdate();

        // revoking the outer share rotates the nested writing space too
        owner.unShareWriteAccessWith(dir, Set.of(outer.username)).join();
        awaitUsageUpdate();
        owner.setWriteShareQuota(nested, Optional.of(200L * KiB)).join();
        awaitUsageUpdate();

        WriterUsageInfo info = owner.getWriteShareQuota(nested).join();
        Assert.assertTrue("rotated space reports " + info.used + " bytes used", info.used >= 500 * KiB);
        assertUploadRejected(owner, nested, "more", 10 * KiB);
    }

    @Test
    public void sharingAnEnclosingFolderKeepsNestedUsage() {
        UserContext owner = signUp();
        UserContext a = signUp();
        UserContext b = signUp();
        UserContext c = signUp();
        PeergosNetworkUtils.friendBetweenGroups(List.of(owner), List.of(a, b, c));
        owner.getUserRoot().join().mkdir("outer", owner.network, false, owner.mirrorBatId(), crypto).join();
        Path outer = PathUtil.get(owner.username, "outer");
        owner.getByPath(outer).join().get().mkdir("middle", owner.network, false, owner.mirrorBatId(), crypto).join();
        Path middle = outer.resolve("middle");
        owner.getByPath(middle).join().get().mkdir("inner", owner.network, false, owner.mirrorBatId(), crypto).join();
        Path inner = middle.resolve("inner");
        upload(owner, inner, "file", 400 * KiB);

        // share from the inside out, so each share moves writing spaces that already exist
        owner.shareWriteAccessWith(middle, Set.of(a.username)).join();
        owner.shareWriteAccessWith(inner, Set.of(b.username)).join();
        awaitUsageUpdate();
        long before = owner.getSpaceUsage(false).join();
        owner.shareWriteAccessWith(outer, Set.of(c.username)).join();
        awaitUsageUpdate();
        long after = owner.getSpaceUsage(false).join();
        Assert.assertTrue("usage went from " + before + " to " + after, after >= 400 * KiB);

        owner.setWriteShareQuota(inner, Optional.of(200L * KiB)).join();
        awaitUsageUpdate();
        long used = owner.getWriteShareQuota(inner).join().used;
        Assert.assertTrue("inner space reports " + used + " bytes used", used >= 400 * KiB);
    }

    @Test
    public void capSurvivesRevokingWriteAccess() {
        UserContext owner = signUp();
        UserContext remaining = signUp();
        UserContext revoked = signUp();
        PeergosNetworkUtils.friendBetweenGroups(List.of(owner), List.of(remaining, revoked));
        Path dir = sharedDir(owner, "team", remaining, revoked);
        owner.getByPath(dir).join().get().mkdir("nested", owner.network, false, owner.mirrorBatId(), crypto).join();
        Path nested = dir.resolve("nested");
        owner.shareWriteAccessWith(nested, Set.of(remaining.username)).join();
        owner.setWriteShareQuota(dir, Optional.of(1024L * KiB)).join();
        owner.setWriteShareQuota(nested, Optional.of(512L * KiB)).join();
        PublicKeyHash originalWriter = owner.getByPath(dir).join().get().writer();

        owner.unShareWriteAccessWith(dir, Set.of(revoked.username)).join();
        FileWrapper rotated = owner.getByPath(dir).join().get();
        Assert.assertNotEquals(originalWriter, rotated.writer());
        Assert.assertEquals(Optional.of(1024L * KiB), owner.getWriteShareQuota(dir).join().quota);
        Assert.assertEquals(Optional.of(512L * KiB), owner.getWriteShareQuota(nested).join().quota);
        // the caps moved to the new signers rather than being copied
        Assert.assertEquals(2, owner.getWriteShareQuotas().join().size());

        assertUploadRejected(remaining, dir, "big", 1200 * KiB);
        assertUploadRejected(remaining, nested, "big", 600 * KiB);
    }
}
