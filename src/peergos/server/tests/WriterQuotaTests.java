package peergos.server.tests;

import org.junit.*;
import peergos.server.*;
import peergos.server.util.*;
import peergos.shared.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.storage.*;
import peergos.shared.user.*;
import peergos.shared.user.fs.*;
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

        assertUploadRejected(remaining, dir, "big", 1200 * KiB);
        assertUploadRejected(remaining, nested, "big", 600 * KiB);
    }
}
