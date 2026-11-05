package peergos.server.tests;

import org.junit.*;
import peergos.server.*;
import peergos.server.sync.*;
import peergos.shared.*;
import peergos.shared.storage.CachingStorage;
import peergos.shared.user.UserContext;
import peergos.shared.user.fs.*;

import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Exercises the real remote hash metadata and resume paths against a disposable test server. */
public class PeergosSyncTimestampTests {
    private static final Crypto crypto = Main.initCrypto();
    private static UserContext context;
    private static final AtomicInteger folders = new AtomicInteger();
    private static final Path FILE = Paths.get("file.bin");
    private static final byte[] ORIGINAL = {1, 2, 3, 4};
    private static final byte[] EDIT = {5, 6, 7, 8};
    private static final long MODIFIED = 1_700_000_000_000L;
    private Path base, root;
    private LocalFileSystem local;
    private PeergosSyncFS remote;
    private UserContext syncContext;
    private Path syncRoot;
    private SyncState synced;
    private FileState scanned;

    @BeforeClass
    public static void init() {
        UserService service = Main.PKI_INIT.main(UserTests.useMemoryDbs(UserTests.buildArgs())
                .with("enable-gc", "false")).localApi;
        NetworkAccess network = NetworkAccess.buildBuffered(new CachingStorage(service.storage, 1_000, 50 * 1024),
                service.bats, service.coreNode, service.account, service.mutable, 0, service.social,
                service.controller, service.usage, service.serverMessages, crypto.hasher, List.of("peergos"), false);
        context = PeergosNetworkUtils.ensureSignedUp("timestamp-tests", "password", network, crypto);
    }

    @Before
    public void setup() throws Exception {
        String folder = "sync" + folders.incrementAndGet();
        context.getUserRoot().join().mkdir(folder, context.network, false, context.mirrorBatId(), crypto).join();
        root = Paths.get(context.username, folder);
        String link = context.createSecretLink(root.toString(), true, Optional.empty(), Optional.<Integer>empty(), "", false)
                .join().toLinkString(context.signer.publicKeyHash);
        syncContext = UserContext.fromSecretLinksV2(List.of(link),
                List.of(() -> java.util.concurrent.CompletableFuture.completedFuture("")), context.network, crypto).join();
        syncRoot = Paths.get(syncContext.getEntryPath().join());
        remote = new PeergosSyncFS(syncContext, syncRoot);
        base = Files.createTempDirectory("peergos-remote-timestamp");
        local = new LocalFileSystem(base, crypto.hasher);
        synced = new JdbcTreeState(":memory:");
        Files.write(base.resolve(FILE), ORIGINAL);
        Files.setLastModifiedTime(base.resolve(FILE), FileTime.fromMillis(MODIFIED));
        int chunk = FileProperties.chunkSizeForNewFiles();
        HashTree hash = local.hashFile(FILE, Optional.empty(), FILE.toString(), synced, ORIGINAL.length, chunk);
        scanned = new FileState(FILE.toString(), MODIFIED, ORIGINAL.length, hash, chunk);
        synced.add(scanned);
        remote.setBytes(FILE, 0, AsyncReader.build(ORIGINAL), ORIGINAL.length, Optional.of(hash),
                Optional.of(LocalDateTime.ofInstant(Instant.ofEpochMilli(MODIFIED), ZoneOffset.UTC)),
                Optional.empty(), ResumeUploadProps.random(crypto), () -> false, m -> {});
        Assert.assertTrue(current().getFileProperties().treeHash.isPresent());
    }

    @After
    public void cleanup() throws Exception {
        if (synced != null)
            synced.close();
        if (base != null) {
            try (Stream<Path> paths = Files.walk(base)) {
                for (Path p : paths.sorted(Comparator.reverseOrder()).collect(Collectors.toList()))
                    Files.delete(p);
            }
        }
    }

    private FileWrapper current() {
        return context.getByPath(root.resolve(FILE)).join().get();
    }

    private void editLocal() throws Exception {
        Files.write(base.resolve(FILE), EDIT);
        Files.setLastModifiedTime(base.resolve(FILE), FileTime.fromMillis(MODIFIED));
    }

    private void editRemote() {
        FileWrapper before = current();
        before.overwriteSectionJS(AsyncReader.build(EDIT), 0, 0, 0, EDIT.length,
                Optional.of(before.getFileProperties().modified), context.network, crypto, x -> {}).join();
        // Missing hash metadata must fail closed, whether a writer omitted it or an edit invalidated it.
        FileWrapper edited = current();
        edited.setProperties(edited.getFileProperties().withHash(Optional.empty()), crypto.hasher, context.network,
                context.getByPath(root).join()).join();
        Assert.assertEquals(MODIFIED, remote.getLastModified(FILE));
        Assert.assertFalse("fixture has no authoritative hash", current().getFileProperties().treeHash.isPresent());
    }

    private byte[] readRemote() throws Exception {
        byte[] result = new byte[(int) remote.size(FILE)];
        try (AsyncReader reader = remote.getBytes(FILE, 0)) {
            int done = 0;
            while (done < result.length)
                done += reader.readIntoArray(result, done, result.length - done).join();
        }
        return result;
    }

    @Test
    public void bulkDeleteWithoutCurrentHashFailsClosed() throws Exception {
        editRemote();
        Assert.assertTrue("missing hash cannot authorize deletion", remote.bulkDeleteFiles(Paths.get(""),
                Map.of(FILE.toString(), scanned)).isEmpty());
        Assert.assertArrayEquals(EDIT, readRemote());
    }

    @Test
    public void bulkDeleteWithMatchingHashIgnoresTimestampOnlyChange() {
        remote.setModificationTime(FILE, MODIFIED + 10_000);
        Assert.assertEquals(Set.of(FILE.toString()), remote.bulkDeleteFiles(Paths.get(""), Map.of(FILE.toString(), scanned)));
        Assert.assertFalse(remote.exists(FILE));
    }

    @Test
    public void scanRetainsAuthoritativeRemoteHashShortcut() throws Exception {
        PeergosSyncFS noHashing = new PeergosSyncFS(syncContext, syncRoot) {
            @Override
            public HashTree hashFile(Path p, Optional<FileWrapper> meta, String rel, SyncState db, long size, int chunk) {
                Assert.fail("matching authoritative remote metadata should avoid hashing");
                return null;
            }
        };
        try (SyncState scan = new JdbcTreeState(":memory:")) {
            DirectorySync.buildDirState(noHashing, scan, synced, () -> false);
            Assert.assertEquals(scanned, scan.byPath(FILE.toString()));
        }
    }

    @Test
    public void resumedNewUploadRejectsChangedSource() throws Exception {
        Path target = Paths.get("new.bin");
        CopyOp op = new CopyOp(false, FILE, target, scanned, null, 0, ORIGINAL.length, ResumeUploadProps.random(crypto));
        editLocal();
        try {
            remote.copy(local, List.of(op), true, synced, () -> false, m -> {});
            Assert.fail("resume accepted changed bytes under the old source hash");
        } catch (ConcurrentChangeException expected) {}
        Assert.assertFalse(remote.exists(target));
    }

    @Test
    public void resumedOverwriteRehashesAndRestartsChangedSource() throws Exception {
        // A resumed range alone cannot repair changes outside that range. The existing resume policy restarts
        // the entire upload when the source changes, and must do so even if size and timestamp still match.
        CopyOp op = new CopyOp(false, FILE, FILE, scanned, scanned, 1, 2, ResumeUploadProps.random(crypto));
        editLocal();
        remote.copy(local, List.of(op), true, synced, () -> false, m -> {});
        Assert.assertArrayEquals(EDIT, readRemote());
        HashTree expected = local.hashFile(FILE, Optional.empty(), FILE.toString(), synced, EDIT.length, scanned.chunkSize);
        Assert.assertEquals(expected.rootHash, current().getFileProperties().treeHash.get().rootHash);
    }

    @Test
    public void resumedDownloadRejectsChangedRemoteSourceBeforeStaging() throws Exception {
        CopyOp op = new CopyOp(true, FILE, FILE, scanned, scanned, 0, ORIGINAL.length, ResumeUploadProps.random(crypto));
        editRemote();
        LocalFileSystem target = new LocalFileSystem(base, crypto.hasher) {
            @Override
            protected Path partialCopy(Path p) {
                Assert.fail("resume staged bytes before checking the remote source hash");
                return null;
            }
        };
        try {
            target.copy(remote, List.of(op), true, synced, () -> false, m -> {});
            Assert.fail("resume accepted changed remote source");
        } catch (ConcurrentChangeException expected) {}
        Assert.assertArrayEquals(ORIGINAL, Files.readAllBytes(base.resolve(FILE)));
    }

    @Test
    public void resumedDownloadPassesActualSyncStateToRemoteHashing() throws Exception {
        AtomicInteger hashes = new AtomicInteger();
        PeergosSyncFS source = new PeergosSyncFS(syncContext, syncRoot) {
            @Override
            public HashTree hashFile(Path p, Optional<FileWrapper> meta, String rel, SyncState db, long size, int chunk) {
                Assert.assertSame("the remote hash path requires sync state", synced, db);
                hashes.incrementAndGet();
                return super.hashFile(p, meta, rel, db, size, chunk);
            }
        };
        CopyOp op = new CopyOp(true, FILE, FILE, scanned, scanned, 0, ORIGINAL.length, ResumeUploadProps.random(crypto));
        local.copy(source, List.of(op), true, synced, () -> false, m -> {});
        Assert.assertTrue("resume and installation must both check source content", hashes.get() >= 2);
        Assert.assertArrayEquals(ORIGINAL, Files.readAllBytes(base.resolve(FILE)));
    }
}
