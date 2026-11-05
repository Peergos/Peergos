package peergos.server.tests;

import org.junit.*;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import peergos.server.Main;
import peergos.server.sync.*;
import peergos.shared.Crypto;
import peergos.shared.user.fs.*;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Size and timestamps are not content identities, even on a subsecond filesystem. */
@RunWith(Parameterized.class)
public class SyncTimestampTests {
    private static final Crypto crypto = Main.initCrypto();
    private static final String NAME = "file.bin";
    private static final byte[] ORIGINAL = {1, 2, 3, 4};
    private static final byte[] EDIT = {5, 6, 7, 8};
    private static final byte[] OTHER = {9, 10, 11, 12};
    private static final long SECOND = 1_700_000_000_000L;

    @Parameterized.Parameters(name = "chunk={0}, preserved={1}")
    public static Collection<Object[]> parameters() {
        return List.of(new Object[]{Chunk.LEGACY_SIZE, false}, new Object[]{Chunk.LEGACY_SIZE, true},
                new Object[]{Chunk.DEFAULT_SIZE, false}, new Object[]{Chunk.DEFAULT_SIZE, true});
    }

    private final int chunkSize;
    private final boolean preserved;
    private Path base, left, right, work;
    private SyncState synced;
    private LocalFileSystem local, remote;

    public SyncTimestampTests(int chunkSize, boolean preserved) {
        this.chunkSize = chunkSize;
        this.preserved = preserved;
    }

    @Before
    public void setup() throws Exception {
        base = Files.createTempDirectory("peergos-timestamp");
        left = Files.createDirectory(base.resolve("left"));
        right = Files.createDirectory(base.resolve("right"));
        work = Files.createDirectory(base.resolve("work"));
        local = new LocalFileSystem(left, crypto.hasher);
        remote = new LocalFileSystem(right, crypto.hasher);
        synced = new JdbcTreeState(":memory:");
        write(left, ORIGINAL, SECOND + 100);
        write(right, ORIGINAL, SECOND + 100);
        synced.add(state(local));
        synced.setCompletedSync(true);
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

    private void write(Path root, byte[] bytes, long millis) throws IOException {
        Files.write(root.resolve(NAME), bytes);
        Files.setLastModifiedTime(root.resolve(NAME), FileTime.fromMillis(millis));
    }

    private void edit(Path root, byte[] bytes) throws IOException {
        FileTime before = Files.getLastModifiedTime(root.resolve(NAME));
        long millis = preserved ? before.toMillis() : SECOND + 900;
        write(root, bytes, millis);
        FileTime after = Files.getLastModifiedTime(root.resolve(NAME));
        Assert.assertEquals("fixture timestamps must be in the same second", before.toMillis() / 1000, after.toMillis() / 1000);
        if (preserved)
            Assert.assertEquals("fixture preserves the exact timestamp", before, after);
        else
            Assert.assertNotEquals("fixture must retain distinct subsecond timestamps", before, after);
    }

    private FileState state(LocalFileSystem fs) {
        Path p = Paths.get(NAME);
        return new FileState(NAME, fs.getLastModified(p), fs.size(p),
                fs.hashFile(p, Optional.empty(), NAME, synced, fs.size(p), chunkSize), chunkSize);
    }

    private void sync(Consumer<String> progress) throws Exception {
        DirectorySync.syncDir(local, remote, true, true, null, null, synced, 32, 5, work,
                crypto, () -> false, progress);
    }

    private void assertBoth(byte[] bytes) throws Exception {
        assertBoth(bytes, chunkSize);
    }

    private void assertBoth(byte[] bytes, int expectedChunkSize) throws Exception {
        Assert.assertArrayEquals(bytes, Files.readAllBytes(left.resolve(NAME)));
        Assert.assertArrayEquals(bytes, Files.readAllBytes(right.resolve(NAME)));
        FileState recorded = synced.byPath(NAME);
        Assert.assertEquals(expectedChunkSize, recorded.chunkSize);
        Assert.assertEquals(local.hashFile(Paths.get(NAME), Optional.empty(), NAME, synced, bytes.length,
                recorded.chunkSize).rootHash, recorded.hashTree.rootHash);
    }

    @Test
    public void scanHashesSameSizeTimestampCollision() throws Exception {
        FileState before = synced.byPath(NAME);
        edit(left, EDIT);
        try (SyncState scanned = new JdbcTreeState(":memory:")) {
            DirectorySync.buildDirState(local, scanned, synced, () -> false);
            Assert.assertNotEquals(before.hashTree.rootHash, scanned.byPath(NAME).hashTree.rootHash);
            Assert.assertEquals(state(local).hashTree.rootHash, scanned.byPath(NAME).hashTree.rootHash);
            Assert.assertEquals(chunkSize, scanned.byPath(NAME).chunkSize);
        }
    }

    @Test
    public void localEditPropagates() throws Exception {
        edit(left, EDIT);
        sync(m -> {});
        assertBoth(EDIT);
        sync(m -> {});
        assertBoth(EDIT);
    }

    @Test
    public void remoteEditPropagates() throws Exception {
        edit(right, EDIT);
        sync(m -> {});
        assertBoth(EDIT);
    }

    private void editDelete(boolean editLocal) throws Exception {
        edit(editLocal ? left : right, EDIT);
        Files.delete((editLocal ? right : left).resolve(NAME));
        sync(m -> {});
        assertBoth(EDIT);
    }

    @Test
    public void localEditSurvivesRemoteDelete() throws Exception {
        editDelete(true);
    }

    @Test
    public void remoteEditSurvivesLocalDelete() throws Exception {
        editDelete(false);
    }

    @Test
    public void editEditPreservesBothContents() throws Exception {
        edit(left, EDIT);
        edit(right, OTHER);
        sync(m -> {});
        for (Path root : List.of(left, right)) {
            Set<String> contents = new HashSet<>();
            try (Stream<Path> files = Files.list(root)) {
                for (Path p : files.filter(Files::isRegularFile).collect(Collectors.toList()))
                    contents.add(Arrays.toString(Files.readAllBytes(p)));
            }
            Assert.assertEquals(Set.of(Arrays.toString(EDIT), Arrays.toString(OTHER)), contents);
        }
    }

    private void afterScanDelete(boolean editLocal) throws Exception {
        Path survivor = editLocal ? left : right;
        Files.delete((editLocal ? right : left).resolve(NAME));
        AtomicBoolean edited = new AtomicBoolean();
        sync(m -> {
            if (m.contains("remote files in") && ! edited.getAndSet(true)) {
                try {
                    edit(survivor, EDIT);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        });
        Assert.assertTrue("edit hook ran after scanning", edited.get());
        Assert.assertArrayEquals("delete must leave the new bytes", EDIT, Files.readAllBytes(survivor.resolve(NAME)));
        Assert.assertNull("a deferred delete is not a synced file", synced.byPath(NAME));
        sync(m -> {});
        // The deferred delete forgot the old row, so a local-only file now uses the new-file scheme.
        assertBoth(EDIT, FileProperties.chunkSizeForNewFiles());
    }

    @Test
    public void localEditAfterScanSurvivesDelete() throws Exception {
        afterScanDelete(true);
    }

    @Test
    public void remoteEditAfterScanSurvivesBulkDelete() throws Exception {
        afterScanDelete(false);
    }

    @Test
    public void bulkDeleteRejectsTimestampCollision() throws Exception {
        FileState before = state(local);
        edit(left, EDIT);
        Assert.assertTrue(local.bulkDeleteFiles(Paths.get(""), Map.of(NAME, before)).isEmpty());
        Assert.assertArrayEquals(EDIT, Files.readAllBytes(left.resolve(NAME)));
    }

    @Test
    public void guardRejectsTimestampCollision() throws Exception {
        FileState before = state(local);
        edit(left, EDIT);
        Assert.assertFalse(SyncFilesystem.asScanned(local, Paths.get(NAME), before, synced));
    }

    private CopyOp copyOp() throws Exception {
        write(right, OTHER, SECOND + 100);
        return new CopyOp(true, Paths.get(NAME), Paths.get(NAME), state(local), state(remote),
                0, ORIGINAL.length, ResumeUploadProps.random(crypto));
    }

    @Test
    public void stagedOverwriteRejectsChangedTarget() throws Exception {
        CopyOp op = copyOp();
        edit(right, EDIT);
        try {
            remote.copy(local, List.of(op), false, synced, () -> false, m -> {});
            Assert.fail("staged overwrite installed over an edit");
        } catch (ConcurrentChangeException expected) {}
        Assert.assertArrayEquals(EDIT, Files.readAllBytes(right.resolve(NAME)));
    }

    @Test
    public void stagedCopyRejectsChangedSource() throws Exception {
        CopyOp op = copyOp();
        edit(left, EDIT);
        try {
            remote.copy(local, List.of(op), false, synced, () -> false, m -> {});
            Assert.fail("staged copy installed bytes with the old source hash");
        } catch (ConcurrentChangeException expected) {}
        Assert.assertArrayEquals(OTHER, Files.readAllBytes(right.resolve(NAME)));
    }

    @Test
    public void resumeRejectsChangedSourceBeforeReading() throws Exception {
        CopyOp op = copyOp();
        edit(left, EDIT);
        LocalFileSystem source = new LocalFileSystem(left, crypto.hasher) {
            @Override
            public HashTree hashFile(Path p, Optional<FileWrapper> meta, String rel, SyncState db, long size, int chunk) {
                Assert.assertSame("source hashing needs the actual sync state", synced, db);
                return super.hashFile(p, meta, rel, db, size, chunk);
            }

            @Override
            public AsyncReader getBytes(Path p, long offset) {
                Assert.fail("resume read a source that no longer matches the recorded hash");
                return null;
            }
        };
        try {
            remote.copy(source, List.of(op), true, synced, () -> false, m -> {});
            Assert.fail("resume accepted a changed source");
        } catch (ConcurrentChangeException expected) {}
        Assert.assertArrayEquals(OTHER, Files.readAllBytes(right.resolve(NAME)));
    }

    @Test
    public void resumeRejectsChangedTarget() throws Exception {
        CopyOp op = copyOp();
        edit(right, EDIT);
        try {
            remote.copy(local, List.of(op), true, synced, () -> false, m -> {});
            Assert.fail("resume overwrote a changed target");
        } catch (ConcurrentChangeException expected) {}
        Assert.assertArrayEquals(EDIT, Files.readAllBytes(right.resolve(NAME)));
    }

    @Test
    public void unchangedAndTimestampOnlyControlsUseRecordedScheme() throws Exception {
        FileState before = state(local);
        Assert.assertTrue(SyncFilesystem.asScanned(local, Paths.get(NAME), before, synced));
        write(left, ORIGINAL, SECOND + 10_000);
        Assert.assertTrue(SyncFilesystem.asScanned(local, Paths.get(NAME), before, synced));
        try (SyncState scanned = new JdbcTreeState(":memory:")) {
            DirectorySync.buildDirState(local, scanned, synced, () -> false);
            Assert.assertEquals(before, scanned.byPath(NAME));
        }
        Assert.assertEquals(Set.of(NAME), local.bulkDeleteFiles(Paths.get(""), Map.of(NAME, before)));
        Assert.assertFalse(SyncFilesystem.asScanned(local, Paths.get(NAME), before, synced));
        Assert.assertTrue(SyncFilesystem.asScanned(local, Paths.get(NAME), null, synced));
    }

    @Test
    public void resumeAcceptsTimestampOnlySourceChange() throws Exception {
        CopyOp op = copyOp();
        write(left, ORIGINAL, SECOND + 10_000);
        remote.copy(local, List.of(op), true, synced, () -> false, m -> {});
        Assert.assertArrayEquals(ORIGINAL, Files.readAllBytes(right.resolve(NAME)));
    }
}
