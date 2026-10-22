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

    /** The remote scan took a file with the size and modification time it was synced with as unchanged, but Peergos
     *  keeps modification times to the second, so an edit within one second of the last was missed and overwritten.
     */
    @Test
    public void remoteEditWithTheSameSizeAndModificationTime() throws Exception {
        UserContext context = signIn("se" + Math.abs(random.nextInt() % 1_000_000));
        Path remoteRoot = PathUtil.get(context.username, "sync");
        PeergosSyncFS remote = syncedFolder(context, remoteRoot);
        Path base = Files.createTempDirectory("peergos-sync");
        LocalFileSystem local = new LocalFileSystem(base, crypto.hasher);
        SyncState state = new JdbcTreeState(":memory:");
        Files.write(base.resolve("f"), "original".getBytes());
        DirectorySync.syncDir(local, remote, true, true, null, null, state, 32, 5,
                Files.createTempDirectory("peergos-sync"), crypto, () -> false, DirectorySync::log);

        Path remoteFile = remoteRoot.resolve("f");
        FileWrapper synced = context.getByPath(remoteFile).join().get();
        java.time.LocalDateTime modified = synced.getFileProperties().modified;
        byte[] remoteEdit = "remote e".getBytes();
        context.getByPath(remoteRoot).join().get().uploadOrReplaceFile("f", AsyncReader.build(remoteEdit), 0, remoteEdit.length,
                context.network, crypto, x -> {}).join();
        FileWrapper edited = context.getByPath(remoteFile).join().get();
        edited.setProperties(edited.getFileProperties().withModified(modified), crypto.hasher, context.network,
                context.getByPath(remoteRoot).join()).join();
        Files.write(base.resolve("f"), "local ed".getBytes());
        // only the remote edit is meant to be within the second, as the local side has no hash to go on
        Files.setLastModifiedTime(base.resolve("f"), java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 5_000));
        DirectorySync.syncDir(local, remote, true, true, null, null, state, 32, 5,
                Files.createTempDirectory("peergos-sync"), crypto, () -> false, DirectorySync::log);

        Set<String> contents = new HashSet<>();
        try (java.util.stream.Stream<Path> files = Files.list(base)) {
            for (Path p : files.collect(java.util.stream.Collectors.toList()))
                contents.add(new String(Files.readAllBytes(p)));
        }
        Assert.assertEquals(Set.of("remote e", "local ed"), contents);
    }
}
