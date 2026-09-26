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
