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

    /** A file deleted here was deleted in Peergos in bulk, as the sync saw it, even after it had been replaced by a folder */
    @Test
    public void folderReplacesAFileWhoseDeleteSyncs() throws Exception {
        UserContext context = signIn("sb" + Math.abs(random.nextInt() % 1_000_000));
        Path remoteRoot = PathUtil.get(context.username, "sync");
        PeergosSyncFS remote = syncedFolder(context, remoteRoot);
        Path base = Files.createTempDirectory("peergos-sync");
        LocalFileSystem local = new LocalFileSystem(base, crypto.hasher);
        SyncState state = new JdbcTreeState(":memory:");
        Files.write(base.resolve("f"), "original".getBytes());
        DirectorySync.syncDir(local, remote, true, true, null, null, state, 32, 5,
                Files.createTempDirectory("peergos-sync"), crypto, () -> false, DirectorySync::log);

        Files.delete(base.resolve("f"));
        byte[] inFolder = "in the folder".getBytes();
        DirectorySync.syncDir(local, remote, true, true, null, null, state, 32, 5,
                Files.createTempDirectory("peergos-sync"), crypto, () -> false, m -> {
                    if (m.contains("remote files in")) {
                        FileWrapper dir = context.getByPath(remoteRoot).join().get();
                        context.getByPath(remoteRoot.resolve("f")).join().get().remove(dir, remoteRoot.resolve("f"), context).join();
                        context.getByPath(remoteRoot).join().get().mkdir("f", context.network, false, context.mirrorBatId(), crypto).join();
                        context.getByPath(remoteRoot.resolve("f")).join().get().uploadOrReplaceFile("g", AsyncReader.build(inFolder), 0,
                                inFolder.length, context.network, crypto, x -> {}).join();
                    }
                });
        Assert.assertEquals(inFolder.length, context.getByPath(remoteRoot.resolve("f/g")).join().get().getSize());
    }

    /** With nothing changed in Peergos, a sync took what it had synced for what is there, including a file whose delete
     *  it doesn't sync, so it forgot the delete, and a new file there later replaced the one kept here
     */
    @Test
    public void fileAddedWhereAnUnsyncedDeleteWas() throws Exception {
        UserContext context = signIn("sd" + Math.abs(random.nextInt() % 1_000_000));
        Path remoteRoot = PathUtil.get(context.username, "sync");
        context.getUserRoot().join().mkdir("sync", context.network, false, context.mirrorBatId(), crypto).join();
        String link = context.createSecretLink(remoteRoot.toString(), true, Optional.empty(), Optional.<Integer>empty(), "", false)
                .join().toLinkString(context.signer.publicKeyHash);
        // the sync client has a network of its own, and builds the remote afresh for every pass, as the app does
        NetworkAccess syncNetwork = NetworkAccess.buildBuffered(new CachingStorage(service.storage, 1_000, 50 * 1024),
                service.bats, service.coreNode, service.account, service.mutable, 0, service.social,
                service.controller, service.usage, service.serverMessages, crypto.hasher, Arrays.asList("peergos"), false);
        Path base = Files.createTempDirectory("peergos-sync");
        LocalFileSystem local = new LocalFileSystem(base, crypto.hasher);
        SyncState state = new JdbcTreeState(":memory:");
        Files.write(base.resolve("f"), "kept here".getBytes());
        // a version is only recorded for a folder with files in it
        Files.write(base.resolve("g"), "stays".getBytes());
        Runnable sync = () -> {
            try {
                PeergosSyncFS remote = DirectorySync.buildRemote(link, syncNetwork, crypto);
                DirectorySync.syncDir(local, remote, true, false, context.signer.publicKeyHash, syncNetwork, state, 32, 5,
                        Files.createTempDirectory("peergos-sync"), crypto, () -> false, DirectorySync::log);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };
        sync.run();
        FileWrapper synced = context.getByPath(remoteRoot.resolve("f")).join().get();
        synced.remove(context.getByPath(remoteRoot).join().get(), remoteRoot.resolve("f"), context).join();
        // a pass that changes Drive itself saves the version it leaves, which the next pass finds unchanged
        Files.createDirectories(base.resolve("d"));
        sync.run();
        sync.run();

        byte[] other = "a different file".getBytes();
        context.getByPath(remoteRoot).join().get().uploadOrReplaceFile("f", AsyncReader.build(other), 0, other.length,
                context.network, crypto, x -> {}).join();
        sync.run();
        Set<String> contents = new HashSet<>();
        try (java.util.stream.Stream<Path> files = Files.list(base)) {
            for (Path p : files.filter(Files::isRegularFile).collect(java.util.stream.Collectors.toList()))
                contents.add(new String(Files.readAllBytes(p)));
        }
        Assert.assertEquals(Set.of("kept here", "a different file", "stays"), contents);
    }

    /** An upload to Peergos is written to the file itself, so finishing it was taken for a change made meanwhile */
    @Test
    public void largeFileUploads() throws Exception {
        UserContext context = signIn("sl" + Math.abs(random.nextInt() % 1_000_000));
        Path remoteRoot = PathUtil.get(context.username, "sync");
        PeergosSyncFS remote = syncedFolder(context, remoteRoot);
        Path base = Files.createTempDirectory("peergos-sync");
        LocalFileSystem local = new LocalFileSystem(base, crypto.hasher);
        SyncState state = new JdbcTreeState(":memory:");
        byte[] data = new byte[2 * 1024 * 1024];
        random.nextBytes(data);
        Files.write(base.resolve("big"), data);
        DirectorySync.syncDir(local, remote, true, true, null, null, state, 32, 5,
                Files.createTempDirectory("peergos-sync"), crypto, () -> false, DirectorySync::log);
        Assert.assertEquals(data.length, context.getByPath(remoteRoot.resolve("big")).join().get().getSize());

        byte[] edit = new byte[3 * 1024 * 1024];
        random.nextBytes(edit);
        Files.write(base.resolve("big"), edit);
        Files.setLastModifiedTime(base.resolve("big"), java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 5_000));
        DirectorySync.syncDir(local, remote, true, true, null, null, state, 32, 5,
                Files.createTempDirectory("peergos-sync"), crypto, () -> false, DirectorySync::log);
        Assert.assertEquals(edit.length, context.getByPath(remoteRoot.resolve("big")).join().get().getSize());
    }

    /** An upload over a file in Peergos is written into the file itself, so one stopped before the file was cut to its
     *  new size left it changed, and the next sync abandoned it as an edit made meanwhile, keeping the mix of both
     */
    @Test
    public void uploadOverALongerFileStoppedBeforeTheTruncate() throws Exception {
        UserContext context = signIn("su" + Math.abs(random.nextInt() % 1_000_000));
        Path remoteRoot = PathUtil.get(context.username, "sync");
        PeergosSyncFS remote = syncedFolder(context, remoteRoot);
        Path base = Files.createTempDirectory("peergos-sync");
        LocalFileSystem local = new LocalFileSystem(base, crypto.hasher);
        SyncState state = new JdbcTreeState(":memory:");
        byte[] original = new byte[1552];
        random.nextBytes(original);
        Files.write(base.resolve("f"), original);
        DirectorySync.syncDir(local, remote, true, true, null, null, state, 32, 5,
                Files.createTempDirectory("peergos-sync"), crypto, () -> false, DirectorySync::log);

        byte[] shorter = new byte[202];
        random.nextBytes(shorter);
        Files.write(base.resolve("f"), shorter);
        Files.setLastModifiedTime(base.resolve("f"), java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() / 1000 * 1000 + 5_000));
        Path rel = Paths.get("f");
        FileState synced = state.byPath("f");
        HashTree hash = local.hashFile(rel, Optional.empty(), "f", state, shorter.length, synced.chunkSize);
        FileState source = new FileState("f", local.getLastModified(rel), shorter.length, hash, synced.chunkSize);
        List<CopyOp> ops = source.diffRanges(synced).stream()
                .map(d -> new CopyOp(false, local.resolve("f"), remote.resolve("f"), source, synced, d.left, d.right,
                        ResumeUploadProps.random(crypto)))
                .collect(java.util.stream.Collectors.toList());
        state.startCopies(ops);
        // the sync stops once the new contents are written, before the file is cut to their size
        java.time.LocalDateTime modified = java.time.LocalDateTime.ofInstant(
                java.time.Instant.ofEpochSecond(source.modificationTime / 1000), java.time.ZoneOffset.UTC);
        for (CopyOp op : ops)
            try (AsyncReader in = local.getBytes(op.source, op.diffStart)) {
                remote.setBytes(op.target, op.diffStart, in, op.diffEnd - op.diffStart, Optional.of(hash), Optional.of(modified),
                        Optional.empty(), op.props, () -> false, x -> {});
            }
        DirectorySync.syncDir(local, remote, true, true, null, null, state, 32, 5,
                Files.createTempDirectory("peergos-sync"), crypto, () -> false, DirectorySync::log);

        try (java.util.stream.Stream<Path> files = Files.list(base)) {
            Assert.assertEquals(List.of(base.resolve("f")), files.collect(java.util.stream.Collectors.toList()));
        }
        Assert.assertArrayEquals(shorter, Files.readAllBytes(base.resolve("f")));
        byte[] uploaded = new byte[shorter.length];
        Assert.assertEquals(shorter.length, context.getByPath(remoteRoot.resolve("f")).join().get().getSize());
        try (AsyncReader in = remote.getBytes(rel, 0)) {
            for (int done = 0; done < uploaded.length; )
                done += in.readIntoArray(uploaded, done, uploaded.length - done).join();
        }
        Assert.assertArrayEquals(shorter, uploaded);
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
