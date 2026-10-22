package peergos.server.tests;

import org.junit.*;
import peergos.server.*;
import peergos.server.sync.*;
import peergos.server.util.*;
import peergos.shared.*;
import peergos.shared.storage.*;
import peergos.shared.user.*;
import peergos.shared.user.fs.*;
import peergos.shared.util.*;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** A small local file where Peergos has a folder went into a bulk upload, which tried to overwrite the folder,
 *  failed the whole batch with "Cannot get input stream for a directory!", and did so again on every sync.
 */
public class PeergosSyncClashTests {
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

    private PeergosSyncFS syncedFolder(UserContext context, Path remoteRoot) {
        context.getUserRoot().join().mkdir(remoteRoot.getFileName().toString(), context.network, false, context.mirrorBatId(), crypto).join();
        LinkProperties link = context.createSecretLink(remoteRoot.toString(), true, Optional.empty(), Optional.<Integer>empty(), "", false).join();
        return DirectorySync.buildRemote(link.toLinkString(context.signer.publicKeyHash), context.network, crypto);
    }

    @Test
    public void smallLocalFileWhereRemoteHasEmptyFolder() throws Exception {
        UserContext context = signIn("sc" + Math.abs(random.nextInt() % 1_000_000));
        Path remoteRoot = PathUtil.get(context.username, "sync");
        PeergosSyncFS remote = syncedFolder(context, remoteRoot);
        remote.mkdirs(PathUtil.get("backups", "files"));

        Path base = Files.createTempDirectory("peergos-sync");
        byte[] data = new byte[1024];
        random.nextBytes(data);
        Files.createDirectories(base.resolve("backups"));
        Files.write(base.resolve("backups/files"), data);
        Files.write(base.resolve("backups/main"), data);
        LocalFileSystem local = new LocalFileSystem(base, crypto.hasher);
        SyncState state = new JdbcTreeState(":memory:");
        for (int i = 0; i < 2; i++)
            DirectorySync.syncDir(local, remote, true, true, null, null, state, 32, 5,
                    Files.createTempDirectory("peergos-sync"), crypto, () -> false, DirectorySync::log);

        for (String name : List.of("files", "main")) {
            FileWrapper f = context.getByPath(remoteRoot.resolve("backups").resolve(name)).join().get();
            Assert.assertFalse(name, f.isDirectory());
            Assert.assertEquals(name, data.length, f.getSize());
        }
        Assert.assertTrue(Files.isRegularFile(base.resolve("backups/files")));
    }

    @Test
    public void uploadOverFolderNamesIt() {
        UserContext context = signIn("su" + Math.abs(random.nextInt() % 1_000_000));
        Path remoteRoot = PathUtil.get(context.username, "sync");
        PeergosSyncFS remote = syncedFolder(context, remoteRoot);
        remote.mkdirs(PathUtil.get("backups", "files"));

        byte[] data = new byte[1024];
        FileWrapper.FileUploadProperties file = new FileWrapper.FileUploadProperties("files", () -> AsyncReader.build(data),
                0, data.length, Optional.empty(), Optional.empty(), false, true, x -> {});
        try {
            remote.uploadSubtree(java.util.stream.Stream.of(new FileWrapper.FolderUploadProperties(List.of("backups"), List.of(file))));
            Assert.fail("Uploaded a file over a folder");
        } catch (CompletionException e) {
            Assert.assertTrue(e.getMessage(), e.getMessage().contains("Cannot replace folder") && e.getMessage().contains("files"));
        }
        Assert.assertTrue(context.getByPath(remoteRoot.resolve("backups/files")).join().get().isDirectory());
    }
}
