package peergos.server.tests;

import org.junit.*;
import peergos.server.*;
import peergos.server.util.*;
import peergos.shared.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.hash.*;
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
        try {
            // the child's new root links the blocks, so they travel in the same call as the pointers that create it
            net.synchronizer.applyComplexUpdate(ownerKey, parent, (s, committer) ->
                    CryptreeNode.initAndAuthoriseSigner(ownerKey, parent, SigningKeyPair.random(crypto.random, crypto.signer), net, s, committer)
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
