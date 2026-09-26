package peergos.server.tests;

import org.junit.*;
import peergos.server.*;
import peergos.server.util.*;
import peergos.shared.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.*;
import peergos.shared.storage.*;
import peergos.shared.user.*;
import peergos.shared.user.fs.*;
import peergos.shared.util.*;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class OverwriteAtomicityTests {
    private static Args args = UserTests.useMemoryDbs(UserTests.buildArgs()).with("enable-gc", "false");
    private static UserService service;
    private static final Crypto crypto = Main.initCrypto();
    private final Random random = new Random();

    @BeforeClass
    public static void init() {
        service = Main.PKI_INIT.main(args).localApi;
    }

    private NetworkAccess network(ContentAddressedStorage storage) {
        return NetworkAccess.buildBuffered(storage, service.bats, service.coreNode, service.account, service.mutable, 0,
                service.social, service.controller, service.usage, service.serverMessages, crypto.hasher,
                Arrays.asList("peergos"), false);
    }

    private static byte[] read(UserContext user, Path path) {
        FileWrapper file = user.getByPath(path).join().get();
        return Serialize.readFully(file.getInputStream(user.network, crypto, x -> {}).join(), file.getSize()).join();
    }

    private static ContentAddressedStorage failingAfterFirstCommit(AtomicInteger commits) {
        return new DelegatingStorage(service.storage) {
            @Override
            public ContentAddressedStorage directToOrigin() {
                return this;
            }

            @Override
            public CompletableFuture<List<Cid>> bulkCommit(PublicKeyHash owner, BulkCommit commit) {
                if (commits.incrementAndGet() > 1)
                    return Futures.errored(new RuntimeException("Simulated failure of a second commit"));
                return super.bulkCommit(owner, commit);
            }
        };
    }

    /** The same for someone who can write the file but not the folder it is in, which is replaced another way */
    @Test
    public void shorterOverwriteOfAFileSharedOnItsOwnIsOneCommit() {
        String suffix = "" + Math.abs(random.nextInt() % 1_000_000);
        UserContext owner = PeergosNetworkUtils.ensureSignedUp("oo" + suffix, "password", network(service.storage), crypto);
        UserContext sharee = PeergosNetworkUtils.ensureSignedUp("os" + suffix, "password", network(service.storage), crypto);
        PeergosNetworkUtils.friendBetweenGroups(List.of(owner), List.of(sharee));
        owner.getUserRoot().join().mkdir("dir", owner.network, false, owner.mirrorBatId(), crypto).join();
        Path dir = PathUtil.get(owner.username, "dir");
        byte[] original = new byte[7000];
        random.nextBytes(original);
        owner.getByPath(dir).join().get()
                .uploadOrReplaceFile("file", AsyncReader.build(original), original.length, owner.network, crypto, () -> false, x -> {})
                .join();
        owner.shareWriteAccessWith(dir.resolve("file"), Set.of(sharee.username)).join();

        AtomicInteger commits = new AtomicInteger();
        UserContext failing = PeergosNetworkUtils.ensureSignedUp(sharee.username, "password",
                network(failingAfterFirstCommit(commits)), crypto);
        FileWrapper folder = failing.getByPath(dir).join().get();
        Assert.assertFalse("the sharee can't write the folder", folder.isWritable());
        byte[] shorter = new byte[3000];
        random.nextBytes(shorter);
        commits.set(0);
        boolean failed = false;
        try {
            folder.uploadOrReplaceFile("file", AsyncReader.build(shorter), shorter.length, failing.network, crypto, () -> false, x -> {})
                    .join();
        } catch (Exception e) {
            failed = true;
        }

        UserContext fresh = PeergosNetworkUtils.ensureSignedUp(owner.username, "password", network(service.storage), crypto);
        byte[] now = read(fresh, dir.resolve("file"));
        Assert.assertTrue("holds " + now.length + " bytes, neither the old nor the new contents",
                Arrays.equals(now, original) || Arrays.equals(now, shorter));
        Assert.assertFalse("the replacement needed more than one commit", failed);

        // and growing it still works
        byte[] longer = new byte[9000];
        random.nextBytes(longer);
        UserContext again = PeergosNetworkUtils.ensureSignedUp(sharee.username, "password", network(service.storage), crypto);
        again.getByPath(dir).join().get()
                .uploadOrReplaceFile("file", AsyncReader.build(longer), longer.length, again.network, crypto, () -> false, x -> {})
                .join();
        Assert.assertArrayEquals(longer, read(fresh, dir.resolve("file")));
    }

    /** Replacing a file with a shorter one used to write the new bytes and cut off the old tail in two commits. A
     *  failure between them left the new bytes followed by the end of the old file.
     */
    @Test
    public void shorterOverwriteIsOneCommit() {
        String username = "ow" + Math.abs(random.nextInt() % 1_000_000);
        UserContext user = PeergosNetworkUtils.ensureSignedUp(username, "password", network(service.storage), crypto);
        Path dir = PathUtil.get(username);
        byte[] original = new byte[7000];
        random.nextBytes(original);
        user.getByPath(dir).join().get()
                .uploadOrReplaceFile("file", AsyncReader.build(original), original.length, user.network, crypto, () -> false, x -> {})
                .join();

        AtomicInteger commits = new AtomicInteger();
        ContentAddressedStorage failSecondCommit = new DelegatingStorage(service.storage) {
            @Override
            public ContentAddressedStorage directToOrigin() {
                return this;
            }

            @Override
            public CompletableFuture<List<Cid>> bulkCommit(PublicKeyHash owner, BulkCommit commit) {
                if (commits.incrementAndGet() > 1)
                    return Futures.errored(new RuntimeException("Simulated failure of a second commit"));
                return super.bulkCommit(owner, commit);
            }
        };
        UserContext failing = PeergosNetworkUtils.ensureSignedUp(username, "password", network(failSecondCommit), crypto);
        byte[] shorter = new byte[3000];
        random.nextBytes(shorter);
        commits.set(0);
        boolean failed = false;
        try {
            failing.getByPath(dir).join().get()
                    .uploadOrReplaceFile("file", AsyncReader.build(shorter), shorter.length, failing.network, crypto, () -> false, x -> {})
                    .join();
        } catch (Exception e) {
            failed = true;
        }

        UserContext fresh = PeergosNetworkUtils.ensureSignedUp(username, "password", network(service.storage), crypto);
        byte[] now = read(fresh, dir.resolve("file"));
        Assert.assertTrue("holds " + now.length + " bytes, neither the old nor the new contents",
                Arrays.equals(now, original) || Arrays.equals(now, shorter));
        Assert.assertFalse("the replacement needed more than one commit", failed);
    }
}
