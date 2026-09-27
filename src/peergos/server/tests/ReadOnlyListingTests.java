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
import java.util.stream.*;

/** A file shared to write gets a writing space of its own, reached through a link node in its folder's. A reader of
 *  the folder who also had the file shared with them walked up from the file to the link with the file's parent key,
 *  which reads the link as a file, and cached it that way. Listing the folder then found that and couldn't decrypt it.
 */
public class ReadOnlyListingTests {
    private static final Args args = UserTests.useMemoryDbs(UserTests.buildArgs()).with("enable-gc", "false");
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

    private Set<String> list(UserContext user, Path dir) {
        return user.getByPath(dir).join().get().getChildren(crypto.hasher, user.network).join().stream()
                .map(FileWrapper::getName)
                .collect(Collectors.toSet());
    }

    private void check(boolean shareFileToWrite, boolean shareFileToRead, boolean uploadedBySharee) {
        String suffix = "" + Math.abs(random.nextInt() % 1_000_000);
        UserContext owner = signIn("lo" + suffix);
        UserContext reader = signIn("lr" + suffix);
        UserContext writer = signIn("lw" + suffix);
        UserContext folderReader = signIn("lf" + suffix);
        PeergosNetworkUtils.friendBetweenGroups(List.of(owner), List.of(reader, writer, folderReader));
        owner.getUserRoot().join().mkdir("top", owner.network, false, owner.mirrorBatId(), crypto).join();
        Path top = PathUtil.get(owner.username, "top");
        if (uploadedBySharee)
            owner.shareWriteAccessWith(top, Set.of(writer.username)).join();
        UserContext uploader = uploadedBySharee ? signIn(writer.username) : owner;
        byte[] data = new byte[4000];
        random.nextBytes(data);
        uploader.getByPath(top).join().get()
                .uploadOrReplaceFile("file", AsyncReader.build(data), data.length, uploader.network, crypto, () -> false, x -> {})
                .join();
        owner.shareReadAccessWith(top, Set.of(reader.username, folderReader.username)).join();
        if (shareFileToWrite)
            owner.shareWriteAccessWith(top.resolve("file"), Set.of(writer.username)).join();
        if (shareFileToRead)
            owner.shareReadAccessWith(top.resolve("file"), Set.of(reader.username)).join();

        Assert.assertEquals(Set.of("file"), list(signIn(owner.username), top));
        Assert.assertEquals("a reader of the folder alone", Set.of("file"), list(signIn(folderReader.username), top));
        Assert.assertEquals("a reader of the folder and the file", Set.of("file"), list(signIn(reader.username), top));
    }

    @Test
    public void fileSharedToWriteAndToAReaderOfItsFolder() {
        check(true, true, false);
    }

    @Test
    public void shareesFileSharedToWriteAndToAReaderOfItsFolder() {
        check(true, true, true);
    }

    @Test
    public void fileSharedToWrite() {
        check(true, false, false);
    }

    @Test
    public void fileSharedToAReaderOfItsFolder() {
        check(false, true, false);
    }

    @Test
    public void subfolderSharedToWrite() {
        String suffix = "" + Math.abs(random.nextInt() % 1_000_000);
        UserContext owner = signIn("lo" + suffix);
        UserContext reader = signIn("lr" + suffix);
        UserContext writer = signIn("lw" + suffix);
        PeergosNetworkUtils.friendBetweenGroups(List.of(owner), List.of(reader, writer));
        owner.getUserRoot().join().mkdir("top", owner.network, false, owner.mirrorBatId(), crypto).join();
        Path top = PathUtil.get(owner.username, "top");
        owner.getByPath(top).join().get().mkdir("mid", owner.network, false, owner.mirrorBatId(), crypto).join();
        owner.shareReadAccessWith(top, Set.of(reader.username)).join();
        owner.shareWriteAccessWith(top.resolve("mid"), Set.of(writer.username)).join();
        owner.shareReadAccessWith(top.resolve("mid"), Set.of(reader.username)).join();
        Assert.assertEquals(Set.of("mid"), list(signIn(reader.username), top));
    }
}
