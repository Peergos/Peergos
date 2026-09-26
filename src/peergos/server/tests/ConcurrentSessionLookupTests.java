package peergos.server.tests;

import org.junit.*;
import peergos.server.*;
import peergos.server.util.*;
import peergos.shared.*;
import peergos.shared.storage.*;
import peergos.shared.user.*;
import peergos.shared.user.fs.*;
import peergos.shared.util.*;

import java.nio.file.*;
import java.util.*;

public class ConcurrentSessionLookupTests {
    private static Args args = UserTests.useMemoryDbs(UserTests.buildArgs()).with("enable-gc", "false");
    private static UserService service;
    private static final Crypto crypto = Main.initCrypto();
    private final Random random = new Random();

    @BeforeClass
    public static void init() {
        service = Main.PKI_INIT.main(args).localApi;
    }

    private UserContext signIn(String username) {
        NetworkAccess network = NetworkAccess.buildBuffered(new CachingStorage(service.storage, 1_000, 50 * 1024),
                service.bats, service.coreNode, service.account, service.mutable, 0, service.social,
                service.controller, service.usage, service.serverMessages, crypto.hasher, Arrays.asList("peergos"), false);
        return PeergosNetworkUtils.ensureSignedUp(username, "password", network, crypto);
    }

    private Path sharedFolder(UserContext owner, String name, String friend) {
        owner.getUserRoot().join().mkdir(name, owner.network, false, owner.mirrorBatId(), crypto).join();
        Path dir = PathUtil.get(owner.username, name);
        byte[] data = new byte[100];
        random.nextBytes(data);
        owner.getByPath(dir).join().get()
                .uploadOrReplaceFile("file", AsyncReader.build(data), data.length, owner.network, crypto, () -> false, x -> {})
                .join();
        owner.shareReadAccessWith(dir, Set.of(friend)).join();
        return dir;
    }

    /** One user signed in twice, as on two devices: each keeps a cache of what's been shared with them, and the other
     *  updating it first used to make a lookup fail as a concurrent modification and report the file as missing.
     */
    @Test
    public void lookupAfterAnotherSessionUpdatedTheShareCache() {
        String suffix = "" + Math.abs(random.nextInt() % 1_000_000);
        UserContext owner = signIn("co" + suffix);
        UserContext first = signIn("cf" + suffix);
        PeergosNetworkUtils.friendBetweenGroups(List.of(owner), List.of(first));
        Path dir = sharedFolder(owner, "one", first.username);
        Assert.assertTrue(first.getByPath(dir.resolve("file")).join().isPresent());

        UserContext second = signIn(first.username);
        Path other = sharedFolder(owner, "two", first.username);
        Assert.assertTrue(second.getByPath(other.resolve("file")).join().isPresent());

        Assert.assertTrue("a file shared earlier is still found",
                first.getByPath(dir.resolve("file")).join().isPresent());
        Assert.assertTrue("a folder shared earlier still lists its contents",
                ! first.getByPath(dir).join().get().getChildren(crypto.hasher, first.network).join().isEmpty());
    }
}
