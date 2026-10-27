package peergos.server.tests;

import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;
import peergos.server.JavaCrypto;
import peergos.server.Main;
import peergos.server.sync.*;
import peergos.server.sync.SyncFilesystem.FileProps;
import peergos.shared.user.fs.ThumbnailGenerator;
import peergos.shared.Crypto;
import peergos.shared.MaybeMultihash;
import peergos.shared.crypto.hash.PublicKeyHash;
import peergos.shared.io.ipfs.Cid;
import peergos.shared.io.ipfs.Multihash;
import peergos.shared.user.CommittedWriterData;
import peergos.shared.user.Snapshot;
import peergos.shared.user.fs.AsyncReader;
import peergos.shared.user.fs.Chunk;
import peergos.shared.user.fs.ChunkHashList;
import peergos.shared.user.fs.HashTree;
import peergos.shared.user.fs.ResumeUploadProps;
import peergos.shared.user.fs.RootHash;
import peergos.shared.util.Pair;
import peergos.shared.util.PathUtil;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class SyncTests {

    private static Crypto crypto = JavaCrypto.init();

    /** ffmpeg is linked into this process, so a file it can't parse doesn't lose a thumbnail, it takes
     *  the server down with an assertion. Syncing a folder must not hand it everything in there.
     */
    @Test
    public void onlyVideosReachTheVideoThumbnailer() throws Exception {
        List<String> sentToFfmpeg = new ArrayList<>();
        ThumbnailGenerator.VideoGenerator videos = ThumbnailGenerator.getVideo();
        ThumbnailGenerator.setVideoInstance(f -> {
            sentToFfmpeg.add(f.getName());
            return Optional.empty();
        });
        ThumbnailGenerator.setInstance(bytes -> Optional.empty());
        try {
            Path dir = Files.createTempDirectory("peergos-thumbnails");
            Files.write(dir.resolve("notes.txt"), "text, and definitely not a video".repeat(20).getBytes());
            Files.write(dir.resolve("archive.zip"), new byte[]{'P', 'K', 3, 4, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0});
            byte[] mp4 = new byte[64];
            System.arraycopy(new byte[]{0, 0, 0, 0x18, 'f', 't', 'y', 'p', 'm', 'p', '4', '2'}, 0, mp4, 0, 12);
            Files.write(dir.resolve("clip.mp4"), mp4);

            LocalFileSystem fs = new LocalFileSystem(dir, Main.initCrypto().hasher);
            for (String name : List.of("notes.txt", "archive.zip", "clip.mp4"))
                fs.getThumbnail(PathUtil.get(name));

            Assert.assertEquals("only the video is handed to ffmpeg",
                    List.of("clip.mp4"), sentToFfmpeg);
        } finally {
            ThumbnailGenerator.setVideoInstance(videos);
        }
    }

    @Test
    public void rename() throws Exception {
        LocalDateTime.now();
        for (int filesize : List.of(1024, 6 * 1024 * 1024)) {
            rename("file.bin", "newfile.bin", true, true, filesize);
            rename("file.bin", "newfile.bin", false, false, filesize);
            rename("newfile.bin", "file.bin", true, true, filesize);
            rename("newfile.bin", "file.bin", false, false, filesize);
        }
    }

    public void rename(String originalFilename,
                       String newFilename,
                       boolean syncLocalDeletes,
                       boolean syncRemoteDeletes,
                       int filesize) throws Exception {
        Path base1 = Files.createTempDirectory("peergos-sync");
        Path base2 = Files.createTempDirectory("peergos-sync");

        LocalFileSystem localFs = new LocalFileSystem(base1, Main.initCrypto().hasher);
        LocalFileSystem remoteFs = new LocalFileSystem(base2, Main.initCrypto().hasher);
        SyncState syncedState = new JdbcTreeState(":memory:");

        DirectorySync.syncDir(localFs, remoteFs, syncLocalDeletes, syncRemoteDeletes, null, null, syncedState, 32, 5, Files.createTempDirectory("peergos-sync"), crypto, () -> false, DirectorySync::log);

        byte[] data = new byte[filesize];
        new Random(42).nextBytes(data);
        Files.write(base1.resolve(originalFilename), data, StandardOpenOption.CREATE);

        DirectorySync.syncDir(localFs, remoteFs, syncLocalDeletes, syncRemoteDeletes, null, null, syncedState, 32, 5, Files.createTempDirectory("peergos-sync"), crypto, () -> false, DirectorySync::log);
        Assert.assertNotNull(syncedState.byPath(originalFilename));

        // rename file
        Files.move(base1.resolve(originalFilename), base1.resolve(newFilename));
        DirectorySync.syncDir(localFs, remoteFs, syncLocalDeletes, syncRemoteDeletes, null, null, syncedState, 32, 5, Files.createTempDirectory("peergos-sync"), crypto, () -> false, DirectorySync::log);
        Assert.assertNull(syncedState.byPath(originalFilename));
        Assert.assertNotNull(syncedState.byPath(newFilename));

        // sync should be stable
        DirectorySync.syncDir(localFs, remoteFs, syncLocalDeletes, syncRemoteDeletes, null, null, syncedState, 32, 5, Files.createTempDirectory("peergos-sync"), crypto, () -> false, DirectorySync::log);
        Assert.assertNull(syncedState.byPath(originalFilename));
        Assert.assertNotNull(syncedState.byPath(newFilename));
    }

    @Test
    public void namesWithLeadingAndTrailingSpaces() throws Exception {
        Assume.assumeFalse("Windows file names can't end in a space",
                System.getProperty("os.name").toLowerCase().startsWith("windows"));
        Path base1 = Files.createTempDirectory("peergos-sync");
        Path base2 = Files.createTempDirectory("peergos-sync");

        LocalFileSystem localFs = new LocalFileSystem(base1, Main.initCrypto().hasher);
        LocalFileSystem remoteFs = new LocalFileSystem(base2, Main.initCrypto().hasher);
        SyncState syncedState = new JdbcTreeState(":memory:");

        List<String> relPaths = List.of(" leading.txt", "trailing.txt ", "dir/ leading.txt", "dir/trailing.txt ",
                " dir /sub/ both .txt");
        byte[] data = new byte[1024];
        new Random(42).nextBytes(data);
        for (String relPath : relPaths) {
            Path p = base1.resolve(Paths.get("", relPath.split("/")));
            Files.createDirectories(p.getParent());
            Files.write(p, data, StandardOpenOption.CREATE);
        }

        DirectorySync.syncDir(localFs, remoteFs, true, true, null, null, syncedState, 32, 5, Files.createTempDirectory("peergos-sync"), crypto, () -> false, DirectorySync::log);
        for (String relPath : relPaths) {
            Assert.assertNotNull(relPath, syncedState.byPath(relPath));
            Assert.assertArrayEquals(relPath, data, Files.readAllBytes(base2.resolve(Paths.get("", relPath.split("/")))));
        }

        // sync should be stable
        DirectorySync.syncDir(localFs, remoteFs, true, true, null, null, syncedState, 32, 5, Files.createTempDirectory("peergos-sync"), crypto, () -> false, DirectorySync::log);
        for (String relPath : relPaths)
            Assert.assertNotNull(relPath, syncedState.byPath(relPath));
    }

    private static void syncBoth(Path base1, Path base2, SyncState state) throws Exception {
        DirectorySync.syncDir(new LocalFileSystem(base1, crypto.hasher), new LocalFileSystem(base2, crypto.hasher), true, true,
                null, null, state, 32, 5, Files.createTempDirectory("peergos-sync"), crypto, () -> false, DirectorySync::log);
    }

    private static void syncKeepingDeletes(Path base1, Path base2, SyncState state) throws Exception {
        DirectorySync.syncDir(new LocalFileSystem(base1, crypto.hasher), new LocalFileSystem(base2, crypto.hasher), false, false,
                null, null, state, 32, 5, Files.createTempDirectory("peergos-sync"), crypto, () -> false, DirectorySync::log);
    }

    @Test
    public void ignoredDeletesAreOnlyLoggedOnce() throws Exception {
        Path base1 = Files.createTempDirectory("peergos-sync");
        Path base2 = Files.createTempDirectory("peergos-sync");
        Files.writeString(base1.resolve("local.txt"), "local");
        Files.writeString(base2.resolve("remote.txt"), "remote");
        SyncState state = new JdbcTreeState(":memory:");
        syncKeepingDeletes(base1, base2, state);
        Files.delete(base1.resolve("local.txt"));
        Files.delete(base2.resolve("remote.txt"));

        for (int pass = 0; pass < 2; pass++) {
            List<String> log = new ArrayList<>();
            DirectorySync.syncDir(new LocalFileSystem(base1, crypto.hasher), new LocalFileSystem(base2, crypto.hasher), false, false,
                    null, null, state, 32, 5, Files.createTempDirectory("peergos-sync"), crypto, () -> false, log::add);
            long ignored = log.stream().filter(m -> m.startsWith("Sync ignore")).count();
            Assert.assertEquals(log.toString(), pass == 0 ? 2 : 0, ignored);
        }
        Assert.assertTrue(Files.exists(base2.resolve("local.txt")));
        Assert.assertTrue(Files.exists(base1.resolve("remote.txt")));
    }

    private static Map<String, String> tree(Path base) throws IOException {
        Map<String, String> res = new TreeMap<>();
        try (Stream<Path> all = Files.walk(base)) {
            for (Path p : all.filter(p -> ! p.equals(base)).collect(Collectors.toList()))
                res.put(base.relativize(p).toString().replace(base.getFileSystem().getSeparator(), "/"), Files.isDirectory(p) ? "dir" : new String(Files.readAllBytes(p)));
        }
        return res;
    }

    private static void write(Path base, String relPath, String contents) throws IOException {
        Path p = base.resolve(relPath);
        Files.createDirectories(p.getParent());
        Files.write(p, contents.getBytes());
    }

    private static void deleteTree(Path p) throws IOException {
        try (Stream<Path> all = Files.walk(p)) {
            for (Path f : all.sorted(Comparator.reverseOrder()).collect(Collectors.toList()))
                Files.delete(f);
        }
    }

    /** The folders the other side's new files sit in were deleted from the side that deleted the folder too */
    @Test
    public void folderDeletedWhileOtherSideAddsToASubfolder() throws Exception {
        for (boolean deleteLocally : List.of(true, false)) {
            Path base1 = Files.createTempDirectory("peergos-sync"), base2 = Files.createTempDirectory("peergos-sync");
            SyncState state = new JdbcTreeState(":memory:");
            write(base1, "x/a", "a");
            syncBoth(base1, base2, state);
            deleteTree((deleteLocally ? base1 : base2).resolve("x"));
            write(deleteLocally ? base2 : base1, "x/y/z", "z");
            syncBoth(base1, base2, state);
            Assert.assertEquals(Map.of("x", "dir", "x/y", "dir", "x/y/z", "z"), tree(base1));
            Assert.assertEquals(tree(base1), tree(base2));
        }
    }

    /** As above, with a new empty folder, whose parents the other side gets back from making it */
    @Test
    public void folderDeletedWhileOtherSideAddsAnEmptySubfolder() throws Exception {
        Path base1 = Files.createTempDirectory("peergos-sync"), base2 = Files.createTempDirectory("peergos-sync");
        SyncState state = new JdbcTreeState(":memory:");
        write(base1, "a/files/x", "x");
        write(base1, "a/files/d", "d");
        syncBoth(base1, base2, state);
        deleteTree(base2.resolve("a"));
        Files.delete(base1.resolve("a/files/d"));
        Files.createDirectories(base1.resolve("a/files/d"));
        syncBoth(base1, base2, state);
        Assert.assertEquals(Map.of("a", "dir", "a/files", "dir", "a/files/d", "dir"), tree(base1));
        Assert.assertEquals(tree(base1), tree(base2));
    }

    /** An edit to a synced file must survive the other side replacing it with a folder */
    @Test
    public void editedFileReplacedByFolderOnOtherSide() throws Exception {
        Path base1 = Files.createTempDirectory("peergos-sync"), base2 = Files.createTempDirectory("peergos-sync");
        SyncState state = new JdbcTreeState(":memory:");
        write(base1, "f", "original");
        syncBoth(base1, base2, state);
        write(base1, "f", "edited");
        Files.delete(base2.resolve("f"));
        write(base2, "f/g", "g");
        syncBoth(base1, base2, state);
        Assert.assertEquals(Map.of("f", "dir", "f/g", "g", "f[conflict-0]", "edited"), tree(base1));
        Assert.assertEquals(tree(base1), tree(base2));
    }

    /** Uploads ran before deletes, so a new file in a folder that replaced a synced file found the file still there */
    @Test
    public void syncedFileReplacedByFolderWithAFile() throws Exception {
        Path base1 = Files.createTempDirectory("peergos-sync"), base2 = Files.createTempDirectory("peergos-sync");
        SyncState state = new JdbcTreeState(":memory:");
        write(base1, "x", "x");
        syncBoth(base1, base2, state);
        Files.delete(base1.resolve("x"));
        write(base1, "x/y", "y");
        syncBoth(base1, base2, state);
        Assert.assertEquals(Map.of("x", "dir", "x/y", "y"), tree(base2));
        Assert.assertEquals(tree(base1), tree(base2));
    }

    /** Both sides agreeing on a file the sync state recorded an older version of, as an interrupted copy that a
     *  later sync finished leaves them, kept the older one, so a delete of it later brought it back.
     */
    @Test
    public void deleteAfterSidesAgreeOnANewerVersion() throws Exception {
        Path base1 = Files.createTempDirectory("peergos-sync"), base2 = Files.createTempDirectory("peergos-sync");
        SyncState state = new JdbcTreeState(":memory:");
        write(base1, "f", "old");
        syncBoth(base1, base2, state);
        write(base1, "f", "new version");
        write(base2, "f", "new version");
        Files.setLastModifiedTime(base2.resolve("f"), Files.getLastModifiedTime(base1.resolve("f")));
        syncBoth(base1, base2, state);
        Files.delete(base1.resolve("f"));
        syncBoth(base1, base2, state);
        Assert.assertEquals(Map.of(), tree(base1));
        Assert.assertEquals(Map.of(), tree(base2));
    }

    /** With no sync state, a remote file whose contents are at another path locally was downloaded on another thread,
     *  where it moved the local copy, while the main thread moved the remote copy, swapping them over.
     */
    @Test
    public void samePathlessContentsOnBothSidesWithNoState() throws Exception {
        for (int i = 0; i < 20; i++) {
            Path base1 = Files.createTempDirectory("peergos-sync"), base2 = Files.createTempDirectory("peergos-sync");
            write(base1, "é.txt/main", "same");
            write(base2, "b", "same");
            syncBoth(base1, base2, new JdbcTreeState(":memory:"));
            Assert.assertEquals(tree(base1), tree(base2));
        }
    }

    /** Moving a file into a new folder that takes its name moved it beneath itself, which the file was in the way of */
    @Test
    public void fileMovedIntoAFolderOfItsName() throws Exception {
        Path base1 = Files.createTempDirectory("peergos-sync"), base2 = Files.createTempDirectory("peergos-sync");
        SyncState state = new JdbcTreeState(":memory:");
        write(base1, "b/main", "m");
        syncBoth(base1, base2, state);
        Files.move(base2.resolve("b/main"), base2.resolve("b/tmp"));
        Files.createDirectories(base2.resolve("b/main"));
        Files.move(base2.resolve("b/tmp"), base2.resolve("b/main/b"));
        syncBoth(base1, base2, state);
        Assert.assertEquals(Map.of("b", "dir", "b/main", "dir", "b/main/b", "m"), tree(base1));
        Assert.assertEquals(tree(base1), tree(base2));
    }

    /** Each side deleting a different copy of the same contents looked like each had moved it to the other's copy,
     *  and each waited for the other to do the move, so nothing was synced.
     */
    @Test
    public void differentCopiesDeletedOnEachSide() throws Exception {
        Path base1 = Files.createTempDirectory("peergos-sync"), base2 = Files.createTempDirectory("peergos-sync");
        SyncState state = new JdbcTreeState(":memory:");
        write(base1, "x/a", "same");
        write(base1, "y/b", "same");
        syncBoth(base1, base2, state);
        Files.delete(base1.resolve("x/a"));
        Files.delete(base2.resolve("y/b"));
        syncBoth(base1, base2, state);
        Assert.assertEquals(Map.of("x", "dir", "y", "dir"), tree(base1));
        Assert.assertEquals(tree(base1), tree(base2));
    }

    /** A conflict name was only checked on the side being renamed, so it could take the name of a file on the other */
    @Test
    public void conflictNameIsFreeOnBothSides() throws Exception {
        Path base1 = Files.createTempDirectory("peergos-sync"), base2 = Files.createTempDirectory("peergos-sync");
        write(base1, "a.txt", "mine");
        write(base2, "a[conflict-0].txt", "theirs");
        LocalFileSystem fs1 = new LocalFileSystem(base1, crypto.hasher), fs2 = new LocalFileSystem(base2, crypto.hasher);
        FileState state = new FileState("a.txt", 0, 4, null, Chunk.LEGACY_SIZE);
        FileState renamed = DirectorySync.renameOnConflict(fs1, fs2, fs1.resolve("a.txt"), state);
        Assert.assertEquals("a[conflict-1].txt", renamed.relPath);
    }

    /** The first sync uploads large files on their own, and one in a folder where the other side has a file failed */
    @Test
    public void firstSyncOfALargeFileWhereTheOtherSideHasAFileForItsFolder() throws Exception {
        Path base1 = Files.createTempDirectory("peergos-sync"), base2 = Files.createTempDirectory("peergos-sync");
        byte[] large = new byte[2 * 1024 * 1024];
        new Random(1).nextBytes(large);
        Files.createDirectories(base1.resolve("d"));
        Files.write(base1.resolve("d/b"), large);
        write(base2, "d", "a file");
        syncBoth(base1, base2, new JdbcTreeState(":memory:"));
        Assert.assertEquals(tree(base1), tree(base2));
        Assert.assertArrayEquals(large, Files.readAllBytes(base2.resolve("d/b")));
        Assert.assertTrue(tree(base1).containsValue("a file"));
    }

    /** A file on one side where the other has a folder can be neither copied over nor left, or every sync fails on it */
    @Test
    public void fileWhereOtherSideHasFolder() throws Exception {
        for (boolean fileIsLocal : List.of(true, false)) {
            for (boolean deletes : List.of(true, false)) {
                Path base1 = Files.createTempDirectory("peergos-sync");
                Path base2 = Files.createTempDirectory("peergos-sync");
                Path fileSide = fileIsLocal ? base1 : base2, folderSide = fileIsLocal ? base2 : base1;
                byte[] data = new byte[1024];
                new Random(42).nextBytes(data);
                // one clash with an empty folder, and one with a folder holding a file
                Files.createDirectories(fileSide.resolve("dir"));
                Files.write(fileSide.resolve("dir/empty"), data);
                Files.createDirectories(folderSide.resolve("dir/empty"));
                Files.write(fileSide.resolve("dir/full"), data);
                Files.createDirectories(folderSide.resolve("dir/full"));
                Files.write(folderSide.resolve("dir/full/inner.txt"), "inner".getBytes());

                LocalFileSystem localFs = new LocalFileSystem(base1, crypto.hasher);
                LocalFileSystem remoteFs = new LocalFileSystem(base2, crypto.hasher);
                SyncState syncedState = new JdbcTreeState(":memory:");
                for (int i = 0; i < 2; i++)
                    DirectorySync.syncDir(localFs, remoteFs, deletes, deletes, null, null, syncedState, 32, 5, Files.createTempDirectory("peergos-sync"), crypto, () -> false, DirectorySync::log);

                String clashName = "empty" + (deletes ? "" : "[conflict-0]");
                for (Path base : List.of(base1, base2)) {
                    Assert.assertArrayEquals(Files.readAllBytes(base.resolve("dir/full[conflict-0]")), data);
                    Assert.assertArrayEquals(Files.readAllBytes(base.resolve("dir/full/inner.txt")), "inner".getBytes());
                    Assert.assertArrayEquals(Files.readAllBytes(base.resolve("dir").resolve(clashName)), data);
                    Assert.assertEquals(! deletes, Files.isDirectory(base.resolve("dir/empty")));
                }
                Assert.assertNotNull(syncedState.byPath("dir/" + clashName));
                Assert.assertNotNull(syncedState.byPath("dir/full[conflict-0]"));
            }
        }
    }

    @Test
    public void renamesWithDuplicates() throws Exception {
        for (int copies=2; copies < 15; copies++)
            for (int renames=1; renames <= copies; renames++)
                renameDupe("file.bin", "newfile.bin", true, true, 1024, copies, renames);
    }

    public void renameDupe(String originalFilename,
                           String newFilename,
                           boolean syncLocalDeletes,
                           boolean syncRemoteDeletes,
                           int filesize,
                           int nCopies,
                           int nRenames) throws Exception {
        Path base1 = Files.createTempDirectory("peergos-sync");
        Path base2 = Files.createTempDirectory("peergos-sync");

        LocalFileSystem localFs = new LocalFileSystem(base1, Main.initCrypto().hasher);
        LocalFileSystem remoteFs = new LocalFileSystem(base2, Main.initCrypto().hasher);
        SyncState syncedState = new JdbcTreeState(":memory:");

        DirectorySync.syncDir(localFs, remoteFs, syncLocalDeletes, syncRemoteDeletes, null, null, syncedState, 32, 5, Files.createTempDirectory("peergos-sync"), crypto, () -> false, DirectorySync::log);

        byte[] data = new byte[filesize];
        new Random(42).nextBytes(data);
        for (int i=0; i < nCopies; i++)
            Files.write(base1.resolve(i + "_" + originalFilename), data, StandardOpenOption.CREATE);

        DirectorySync.syncDir(localFs, remoteFs, syncLocalDeletes, syncRemoteDeletes, null, null, syncedState, 32, 5, Files.createTempDirectory("peergos-sync"), crypto, () -> false, DirectorySync::log);
        for (int i=0; i < nCopies; i++)
            Assert.assertNotNull(syncedState.byPath(i + "_" + originalFilename));
        Assert.assertEquals(syncedState.allFilePaths().size(), nCopies);

        // rename file
        for (int i=0; i < nRenames; i++)
            Files.move(base1.resolve(i + "_" + originalFilename), base1.resolve(i + "_" + newFilename));
        List<String> ops = new ArrayList<>();
        DirectorySync.syncDir(localFs, remoteFs, syncLocalDeletes, syncRemoteDeletes, null, null, syncedState, 32, 5, Files.createTempDirectory("peergos-sync"), crypto, () -> false, ops::add);
        for (int i=0; i < nRenames; i++) {
            Assert.assertNull(syncedState.byPath(i + "_" + originalFilename));
            Assert.assertNotNull(syncedState.byPath(i + "_" + newFilename));
        }
        Assert.assertTrue(ops.stream().noneMatch(op -> op.contains("upload")));
        Assert.assertTrue(ops.stream().anyMatch(op -> op.contains("Moving")));

        // sync should be stable
        DirectorySync.syncDir(localFs, remoteFs, syncLocalDeletes, syncRemoteDeletes, null, null, syncedState, 32, 5, Files.createTempDirectory("peergos-sync"), crypto, () -> false, DirectorySync::log);
        for (int i=0; i < nRenames; i++) {
            Assert.assertNull(syncedState.byPath(i + "_" + originalFilename));
            Assert.assertNotNull(syncedState.byPath(i + "_" + newFilename));
        }
    }

    @Test
    public void renameIgnoringDeletes() throws Exception {
        Path base1 = Files.createTempDirectory("peergos-sync");
        Path base2 = Files.createTempDirectory("peergos-sync");

        LocalFileSystem localFs = new LocalFileSystem(base1, Main.initCrypto().hasher);
        LocalFileSystem remoteFs = new LocalFileSystem(base2, Main.initCrypto().hasher);
        SyncState syncedState = new JdbcTreeState(":memory:");

        boolean syncLocalDeletes = false;
        boolean syncRemoteDeletes = false;
        DirectorySync.syncDir(localFs, remoteFs, syncLocalDeletes, syncRemoteDeletes, null, null, syncedState, 32, 5, Files.createTempDirectory("peergos-sync"), crypto, () -> false, DirectorySync::log);

        byte[] data = new byte[6 * 1024 * 1024];
        new Random(42).nextBytes(data);
        String filename = "file.bin";
        Files.write(base1.resolve(filename), data, StandardOpenOption.CREATE);

        DirectorySync.syncDir(localFs, remoteFs, syncLocalDeletes, syncRemoteDeletes, null, null, syncedState, 32, 5, Files.createTempDirectory("peergos-sync"), crypto, () -> false, DirectorySync::log);
        Assert.assertNotNull(syncedState.byPath(filename));

        // rename file
        String filename2 = "newfile.bin";
        Files.move(base1.resolve(filename), base1.resolve(filename2));
        DirectorySync.syncDir(localFs, remoteFs, syncLocalDeletes, syncRemoteDeletes, null, null, syncedState, 32, 5, Files.createTempDirectory("peergos-sync"), crypto, () -> false, DirectorySync::log);
        Assert.assertNull(syncedState.byPath(filename));
        Assert.assertNotNull(syncedState.byPath(filename2));

        // sync should be stable
        DirectorySync.syncDir(localFs, remoteFs, syncLocalDeletes, syncRemoteDeletes, null, null, syncedState, 32, 5, Files.createTempDirectory("peergos-sync"), crypto, () -> false, DirectorySync::log);
        Assert.assertNull(syncedState.byPath(filename));
        Assert.assertNotNull(syncedState.byPath(filename2));
    }

    @Test
    public void moves() throws Exception {
        Path base1 = Files.createTempDirectory("peergos-sync");
        Path base2 = Files.createTempDirectory("peergos-sync");

        LocalFileSystem localFs = new LocalFileSystem(base1, Main.initCrypto().hasher);
        LocalFileSystem remoteFs = new LocalFileSystem(base2, Main.initCrypto().hasher);
        SyncState syncedState = new JdbcTreeState(":memory:");

        DirectorySync.syncDir(localFs, remoteFs, true, true, null, null, syncedState, 32, 5, Files.createTempDirectory("peergos-sync"), crypto, () -> false, DirectorySync::log);

        byte[] data = new byte[6 * 1024 * 1024];
        new Random(42).nextBytes(data);
        String filename = "file.bin";
        Files.write(base1.resolve(filename), data, StandardOpenOption.CREATE);

        DirectorySync.syncDir(localFs, remoteFs, true, true, null, null, syncedState, 32, 5, Files.createTempDirectory("peergos-sync"), crypto, () -> false, DirectorySync::log);
        Assert.assertNotNull(syncedState.byPath(filename));

        // move file to a subdir
        Path subdir = base1.resolve("subdir");
        subdir.toFile().mkdirs();
        Files.move(base1.resolve(filename), subdir.resolve(filename));
        DirectorySync.syncDir(localFs, remoteFs, true, true, null, null, syncedState, 32, 5, Files.createTempDirectory("peergos-sync"), crypto, () -> false, DirectorySync::log);
        Assert.assertNull(syncedState.byPath(filename));
        String fileRelPath = subdir.getFileName().resolve(filename).toString().replaceAll("\\\\", "/");
        Assert.assertNotNull(syncedState.byPath(fileRelPath));

        // sync should be stable
        DirectorySync.syncDir(localFs, remoteFs, true, true, null, null, syncedState, 32, 5, Files.createTempDirectory("peergos-sync"), crypto, () -> false, DirectorySync::log);
        Assert.assertNull(syncedState.byPath(filename));
        Assert.assertNotNull(syncedState.byPath(fileRelPath));

        // move the file back
        Files.move(subdir.resolve(filename), base1.resolve(filename));
        DirectorySync.syncDir(localFs, remoteFs, true, true, null, null, syncedState, 32, 5, Files.createTempDirectory("peergos-sync"), crypto, () -> false, DirectorySync::log);
        Assert.assertNotNull(syncedState.byPath(filename));
        Assert.assertNull(syncedState.byPath(fileRelPath));

        // check stability
        DirectorySync.syncDir(localFs, remoteFs, true, true, null, null, syncedState, 32, 5, Files.createTempDirectory("peergos-sync"), crypto, () -> false, DirectorySync::log);
        Assert.assertNotNull(syncedState.byPath(filename));
        Assert.assertNull(syncedState.byPath(fileRelPath));

        Assert.assertTrue(syncedState.getInProgressCopies().isEmpty());
    }

    @Test
    public void ignoredFileInDirIsCleanedUpOnDelete() throws Exception {
        // The Finder drops a .DS_Store into any dir it displays. We never sync those, so when the
        // dir is deleted remotely, the local dir looks empty to us, but still has the .DS_Store in it.
        Path base1 = Files.createTempDirectory("peergos-sync");
        Path base2 = Files.createTempDirectory("peergos-sync");

        LocalFileSystem localFs = new LocalFileSystem(base1, Main.initCrypto().hasher);
        LocalFileSystem remoteFs = new LocalFileSystem(base2, Main.initCrypto().hasher);
        SyncState syncedState = new JdbcTreeState(":memory:");

        DirectorySync.syncDir(localFs, remoteFs, true, true, null, null, syncedState, 32, 5, Files.createTempDirectory("peergos-sync"), crypto, () -> false, DirectorySync::log);

        // sync a subdir containing a file
        byte[] data = new byte[1024];
        new Random(42).nextBytes(data);
        String dirname = "subdir";
        String filename = "file.bin";
        String fileRelPath = dirname + "/" + filename;
        Files.createDirectory(base1.resolve(dirname));
        Files.write(base1.resolve(dirname).resolve(filename), data, StandardOpenOption.CREATE);

        DirectorySync.syncDir(localFs, remoteFs, true, true, null, null, syncedState, 32, 5, Files.createTempDirectory("peergos-sync"), crypto, () -> false, DirectorySync::log);
        Assert.assertNotNull(syncedState.byPath(fileRelPath));
        Assert.assertTrue(base2.resolve(dirname).resolve(filename).toFile().exists());

        // the Finder adds a .DS_Store to the local dir
        String ignored = ".DS_Store";
        Files.write(base1.resolve(dirname).resolve(ignored), "finder".getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE);

        // delete the dir and its file remotely
        Files.delete(base2.resolve(dirname).resolve(filename));
        Files.delete(base2.resolve(dirname));

        DirectorySync.syncDir(localFs, remoteFs, true, true, null, null, syncedState, 32, 5, Files.createTempDirectory("peergos-sync"), crypto, () -> false, DirectorySync::log);
        Assert.assertNull(syncedState.byPath(fileRelPath));
        Assert.assertFalse(base1.resolve(dirname).resolve(ignored).toFile().exists());
        Assert.assertFalse(base1.resolve(dirname).toFile().exists());
        Assert.assertFalse(base2.resolve(dirname).toFile().exists());

        // sync should be stable
        DirectorySync.syncDir(localFs, remoteFs, true, true, null, null, syncedState, 32, 5, Files.createTempDirectory("peergos-sync"), crypto, () -> false, DirectorySync::log);
        Assert.assertTrue(syncedState.allFilePaths().isEmpty());
        Assert.assertFalse(base1.resolve(dirname).toFile().exists());
        Assert.assertFalse(base2.resolve(dirname).toFile().exists());
    }

    @Test
    public void androidModTime() throws Exception {
        Path base1 = Files.createTempDirectory("peergos-sync");
        Path base2 = Files.createTempDirectory("peergos-sync");

        LocalFileSystem localFs = new LocalFileSystem(base1, Main.initCrypto().hasher);
        LocalFileSystem remoteFs = new LocalFileSystem(base2, Main.initCrypto().hasher);
        SyncState syncedState = new JdbcTreeState(":memory:");

        DirectorySync.syncDir(localFs, remoteFs, true, true, null, null, syncedState, 32, 5, Files.createTempDirectory("peergos-sync"), crypto, () -> false, DirectorySync::log);

        byte[] data = new byte[6 * 1024];
        new Random(42).nextBytes(data);
        String filename = "file.bin";
        Files.write(base2.resolve(filename), data, StandardOpenOption.CREATE);

        DirectorySync.syncDir(localFs, remoteFs, true, true, null, null, syncedState, 32, 5, Files.createTempDirectory("peergos-sync"), crypto, () -> false, DirectorySync::log);
        Assert.assertNotNull(syncedState.byPath(filename));

        // simulate Android (base1) not being able to set mod time, and a modification on original source (base2)
        boolean modTimeSet = base1.resolve(filename).toFile().setLastModified(System.currentTimeMillis() + 10_000);
        Files.write(base2.resolve(filename), "add to end".getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);

        // check stability
        List<String> ops = new ArrayList<>();
        DirectorySync.syncDir(localFs, remoteFs, true, true, null, null, syncedState, 32, 5, Files.createTempDirectory("peergos-sync"), crypto, () -> false, ops::add);
        Assert.assertNotNull(syncedState.byPath(filename));
        Set<String> all = syncedState.allFilePaths();
        Assert.assertEquals(1, all.size());
    }

    @Test
    public void ignoreLocalDeleteBeforeConflict() throws Exception {
        ignoreLocalDeleteBeforeConflict(6 * 1024 * 1024);
        ignoreLocalDeleteBeforeConflict(1024);
    }

    public void ignoreLocalDeleteBeforeConflict(int fileSize) throws Exception {
        Path base1 = Files.createTempDirectory("peergos-sync");
        Path base2 = Files.createTempDirectory("peergos-sync");

        LocalFileSystem localFs = new LocalFileSystem(base1, Main.initCrypto().hasher);
        LocalFileSystem remoteFs = new LocalFileSystem(base2, Main.initCrypto().hasher);
        SyncState syncedState = new JdbcTreeState(":memory:");

        boolean syncLocalDeletes = false;
        boolean syncRemoteDeletes = true;
        DirectorySync.syncDir(localFs, remoteFs, syncLocalDeletes, syncRemoteDeletes, null, null, syncedState, 32, 5, Files.createTempDirectory("peergos-sync"), crypto, () -> false, DirectorySync::log);

        byte[] data = new byte[fileSize];
        new Random(42).nextBytes(data);
        String filename = "file.bin";
        Files.write(base1.resolve(filename), data, StandardOpenOption.CREATE);

        DirectorySync.syncDir(localFs, remoteFs, syncLocalDeletes, syncRemoteDeletes, null, null, syncedState, 32, 5, Files.createTempDirectory("peergos-sync"), crypto, () -> false, DirectorySync::log);
        Assert.assertNotNull(syncedState.byPath(filename));

        // delete local file and check remote is not deleted
        Files.delete(base1.resolve(filename));
        DirectorySync.syncDir(localFs, remoteFs, syncLocalDeletes, syncRemoteDeletes, null, null, syncedState, 32, 5, Files.createTempDirectory("peergos-sync"), crypto, () -> false, DirectorySync::log);
        Assert.assertNotNull(syncedState.byPath(filename));
        Assert.assertTrue(base2.resolve(filename).toFile().exists());
        Assert.assertFalse(base1.resolve(filename).toFile().exists());
        Assert.assertTrue(syncedState.hasLocalDelete(filename));
        DirectorySync.syncDir(localFs, remoteFs, syncLocalDeletes, syncRemoteDeletes, null, null, syncedState, 32, 5, Files.createTempDirectory("peergos-sync"), crypto, () -> false, DirectorySync::log);
        Assert.assertNotNull(syncedState.byPath(filename));
        Assert.assertTrue(syncedState.hasLocalDelete(filename));
        Assert.assertTrue(base2.resolve(filename).toFile().exists());
        Assert.assertFalse(base1.resolve(filename).toFile().exists());

        // add a different local file with the same name (it should be renamed, and then synced)
        byte[] data2 = new byte[fileSize + 1024 * 1024];
        new Random(28).nextBytes(data2);
        Files.write(base1.resolve(filename), data2, StandardOpenOption.CREATE);
        DirectorySync.syncDir(localFs, remoteFs, syncLocalDeletes, syncRemoteDeletes, null, null, syncedState, 32, 5, Files.createTempDirectory("peergos-sync"), crypto, () -> false, DirectorySync::log);
        Assert.assertArrayEquals(Files.readAllBytes(base2.resolve(filename)), data);
        Assert.assertArrayEquals(Files.readAllBytes(base1.resolve(filename)), data);
        Assert.assertFalse(syncedState.hasLocalDelete(filename));
        Assert.assertEquals(2, syncedState.allFilePaths().size());
    }

    @Test
    public void ignoreLocalDeleteBeforeRestore() throws Exception {
        ignoreLocalDeleteBeforeRestore(6 * 1024 * 1024);
        ignoreLocalDeleteBeforeRestore(1024);
    }

    public void ignoreLocalDeleteBeforeRestore(int fileSize) throws Exception {
        Path base1 = Files.createTempDirectory("peergos-sync");
        Path base2 = Files.createTempDirectory("peergos-sync");

        LocalFileSystem localFs = new LocalFileSystem(base1, Main.initCrypto().hasher);
        LocalFileSystem remoteFs = new LocalFileSystem(base2, Main.initCrypto().hasher);
        SyncState syncedState = new JdbcTreeState(":memory:");

        boolean syncLocalDeletes = false;
        boolean syncRemoteDeletes = true;
        DirectorySync.syncDir(localFs, remoteFs, syncLocalDeletes, syncRemoteDeletes, null, null, syncedState, 32, 5, Files.createTempDirectory("peergos-sync"), crypto, () -> false, DirectorySync::log);

        byte[] data = new byte[fileSize];
        new Random(42).nextBytes(data);
        String filename = "file.bin";
        Files.write(base1.resolve(filename), data, StandardOpenOption.CREATE);

        DirectorySync.syncDir(localFs, remoteFs, syncLocalDeletes, syncRemoteDeletes, null, null, syncedState, 32, 5, Files.createTempDirectory("peergos-sync"), crypto, () -> false, DirectorySync::log);
        Assert.assertNotNull(syncedState.byPath(filename));

        // delete local file and check remote is not deleted
        Files.delete(base1.resolve(filename));
        DirectorySync.syncDir(localFs, remoteFs, syncLocalDeletes, syncRemoteDeletes, null, null, syncedState, 32, 5, Files.createTempDirectory("peergos-sync"), crypto, () -> false, DirectorySync::log);
        Assert.assertNotNull(syncedState.byPath(filename));
        Assert.assertTrue(base2.resolve(filename).toFile().exists());
        Assert.assertTrue(syncedState.hasLocalDelete(filename));
        DirectorySync.syncDir(localFs, remoteFs, syncLocalDeletes, syncRemoteDeletes, null, null, syncedState, 32, 5, Files.createTempDirectory("peergos-sync"), crypto, () -> false, DirectorySync::log);
        Assert.assertNotNull(syncedState.byPath(filename));
        Assert.assertTrue(syncedState.hasLocalDelete(filename));
        Assert.assertTrue(base2.resolve(filename).toFile().exists());

        // restore the local file (it should be removed from the delete list)
        Files.write(base1.resolve(filename), data, StandardOpenOption.CREATE);
        DirectorySync.syncDir(localFs, remoteFs, syncLocalDeletes, syncRemoteDeletes, null, null, syncedState, 32, 5, Files.createTempDirectory("peergos-sync"), crypto, () -> false, DirectorySync::log);
        Assert.assertArrayEquals(Files.readAllBytes(base2.resolve(filename)), data);
        Assert.assertArrayEquals(Files.readAllBytes(base1.resolve(filename)), data);
        Assert.assertFalse(syncedState.hasLocalDelete(filename));
        Assert.assertEquals(1, syncedState.allFilePaths().size());
    }

    @Test
    public void ignoreLocalDeleteBeforeRemoteModification() throws Exception {
        ignoreLocalDeleteBeforeRemoteModification(6 * 1024 * 1024);
        ignoreLocalDeleteBeforeRemoteModification(1024);
    }

    public void ignoreLocalDeleteBeforeRemoteModification(int fileSize) throws Exception {
        Path base1 = Files.createTempDirectory("peergos-sync");
        Path base2 = Files.createTempDirectory("peergos-sync");

        LocalFileSystem localFs = new LocalFileSystem(base1, Main.initCrypto().hasher);
        LocalFileSystem remoteFs = new LocalFileSystem(base2, Main.initCrypto().hasher);
        SyncState syncedState = new JdbcTreeState(":memory:");

        boolean syncLocalDeletes = false;
        boolean syncRemoteDeletes = true;
        DirectorySync.syncDir(localFs, remoteFs, syncLocalDeletes, syncRemoteDeletes, null, null, syncedState, 32, 5, Files.createTempDirectory("peergos-sync"), crypto, () -> false, DirectorySync::log);

        byte[] data = new byte[fileSize];
        new Random(42).nextBytes(data);
        String filename = "file.bin";
        Files.write(base1.resolve(filename), data, StandardOpenOption.CREATE);

        DirectorySync.syncDir(localFs, remoteFs, syncLocalDeletes, syncRemoteDeletes, null, null, syncedState, 32, 5, Files.createTempDirectory("peergos-sync"), crypto, () -> false, DirectorySync::log);
        Assert.assertNotNull(syncedState.byPath(filename));

        // delete local file and check remote is not deleted
        Files.delete(base1.resolve(filename));
        DirectorySync.syncDir(localFs, remoteFs, syncLocalDeletes, syncRemoteDeletes, null, null, syncedState, 32, 5, Files.createTempDirectory("peergos-sync"), crypto, () -> false, DirectorySync::log);
        Assert.assertNotNull(syncedState.byPath(filename));
        Assert.assertTrue(base2.resolve(filename).toFile().exists());
        Assert.assertTrue(syncedState.hasLocalDelete(filename));
        DirectorySync.syncDir(localFs, remoteFs, syncLocalDeletes, syncRemoteDeletes, null, null, syncedState, 32, 5, Files.createTempDirectory("peergos-sync"), crypto, () -> false, DirectorySync::log);
        Assert.assertNotNull(syncedState.byPath(filename));
        Assert.assertTrue(syncedState.hasLocalDelete(filename));
        Assert.assertTrue(base2.resolve(filename).toFile().exists());

        // modify the remote file (it should be copied to local)
        byte[] data2 = new byte[fileSize + 1024 * 1024];
        new Random(28).nextBytes(data2);
        Files.delete(base2.resolve(filename));
        Files.write(base2.resolve(filename), data2, StandardOpenOption.CREATE);
        DirectorySync.syncDir(localFs, remoteFs, syncLocalDeletes, syncRemoteDeletes, null, null, syncedState, 32, 5, Files.createTempDirectory("peergos-sync"), crypto, () -> false, DirectorySync::log);
        Assert.assertArrayEquals(Files.readAllBytes(base2.resolve(filename)), data2);
        Assert.assertArrayEquals(Files.readAllBytes(base1.resolve(filename)), data2);
        Assert.assertFalse(syncedState.hasLocalDelete(filename));
        Assert.assertEquals(1, syncedState.allFilePaths().size());
    }

    @Test
    public void ignoreRemoteDeleteBeforeConflict() throws Exception {
        ignoreRemoteDeleteBeforeConflict(6 * 1024 * 1024);
        ignoreRemoteDeleteBeforeConflict(1024);
    }

    public void ignoreRemoteDeleteBeforeConflict(int fileSize) throws Exception {
        Path base1 = Files.createTempDirectory("peergos-sync");
        Path base2 = Files.createTempDirectory("peergos-sync");

        LocalFileSystem localFs = new LocalFileSystem(base1, Main.initCrypto().hasher);
        LocalFileSystem remoteFs = new LocalFileSystem(base2, Main.initCrypto().hasher);
        SyncState syncedState = new JdbcTreeState(":memory:");

        boolean syncLocalDeletes = true;
        boolean syncRemoteDeletes = false;
        DirectorySync.syncDir(localFs, remoteFs, syncLocalDeletes, syncRemoteDeletes, null, null, syncedState, 32, 5, Files.createTempDirectory("peergos-sync"), crypto, () -> false, DirectorySync::log);

        byte[] data = new byte[fileSize];
        new Random(42).nextBytes(data);
        String filename = "file.bin";
        Files.write(base1.resolve(filename), data, StandardOpenOption.CREATE);

        DirectorySync.syncDir(localFs, remoteFs, syncLocalDeletes, syncRemoteDeletes, null, null, syncedState, 32, 5, Files.createTempDirectory("peergos-sync"), crypto, () -> false, DirectorySync::log);
        Assert.assertNotNull(syncedState.byPath(filename));

        // delete remote file and check local is not deleted
        Files.delete(base2.resolve(filename));
        DirectorySync.syncDir(localFs, remoteFs, syncLocalDeletes, syncRemoteDeletes, null, null, syncedState, 32, 5, Files.createTempDirectory("peergos-sync"), crypto, () -> false, DirectorySync::log);
        Assert.assertNotNull(syncedState.byPath(filename));
        Assert.assertTrue(base1.resolve(filename).toFile().exists());
        Assert.assertTrue(syncedState.hasRemoteDelete(filename));
        DirectorySync.syncDir(localFs, remoteFs, syncLocalDeletes, syncRemoteDeletes, null, null, syncedState, 32, 5, Files.createTempDirectory("peergos-sync"), crypto, () -> false, DirectorySync::log);
        Assert.assertNotNull(syncedState.byPath(filename));
        Assert.assertTrue(syncedState.hasRemoteDelete(filename));
        Assert.assertTrue(base1.resolve(filename).toFile().exists());

        // add a different remote file with the same name (local should be renamed, and then new remote synced)
        byte[] data2 = new byte[fileSize + 1024 * 1024];
        new Random(28).nextBytes(data2);
        Files.write(base2.resolve(filename), data2, StandardOpenOption.CREATE);
        DirectorySync.syncDir(localFs, remoteFs, syncLocalDeletes, syncRemoteDeletes, null, null, syncedState, 32, 5, Files.createTempDirectory("peergos-sync"), crypto, () -> false, DirectorySync::log);
        Assert.assertArrayEquals(Files.readAllBytes(base2.resolve(filename)), data2);
        Assert.assertArrayEquals(Files.readAllBytes(base1.resolve(filename)), data2);
        Assert.assertFalse(syncedState.hasRemoteDelete(filename));
        Set<String> paths = syncedState.allFilePaths();
        Assert.assertEquals(2, paths.size());
        String renamed = paths.stream().filter(p -> !p.equals(filename)).findFirst().get();
        Assert.assertArrayEquals(Files.readAllBytes(base1.resolve(renamed)), data);
        Assert.assertArrayEquals(Files.readAllBytes(base2.resolve(renamed)), data);
    }

    @Test
    public void ignoreRemoteDeleteBeforeRestore() throws Exception {
        ignoreRemoteDeleteBeforeRestore(6 * 1024 * 1024);
        ignoreRemoteDeleteBeforeRestore(1024);
    }

    public void ignoreRemoteDeleteBeforeRestore(int fileSize) throws Exception {
        Path base1 = Files.createTempDirectory("peergos-sync");
        Path base2 = Files.createTempDirectory("peergos-sync");

        LocalFileSystem localFs = new LocalFileSystem(base1, Main.initCrypto().hasher);
        LocalFileSystem remoteFs = new LocalFileSystem(base2, Main.initCrypto().hasher);
        SyncState syncedState = new JdbcTreeState(":memory:");

        boolean syncLocalDeletes = true;
        boolean syncRemoteDeletes = false;
        DirectorySync.syncDir(localFs, remoteFs, syncLocalDeletes, syncRemoteDeletes, null, null, syncedState, 32, 5, Files.createTempDirectory("peergos-sync"), crypto, () -> false, DirectorySync::log);

        byte[] data = new byte[fileSize];
        new Random(42).nextBytes(data);
        String filename = "file.bin";
        Files.write(base1.resolve(filename), data, StandardOpenOption.CREATE);

        DirectorySync.syncDir(localFs, remoteFs, syncLocalDeletes, syncRemoteDeletes, null, null, syncedState, 32, 5, Files.createTempDirectory("peergos-sync"), crypto, () -> false, DirectorySync::log);
        Assert.assertNotNull(syncedState.byPath(filename));

        // delete remote file and check local is not deleted
        Files.delete(base2.resolve(filename));
        DirectorySync.syncDir(localFs, remoteFs, syncLocalDeletes, syncRemoteDeletes, null, null, syncedState, 32, 5, Files.createTempDirectory("peergos-sync"), crypto, () -> false, DirectorySync::log);
        Assert.assertNotNull(syncedState.byPath(filename));
        Assert.assertTrue(base1.resolve(filename).toFile().exists());
        Assert.assertTrue(syncedState.hasRemoteDelete(filename));
        DirectorySync.syncDir(localFs, remoteFs, syncLocalDeletes, syncRemoteDeletes, null, null, syncedState, 32, 5, Files.createTempDirectory("peergos-sync"), crypto, () -> false, DirectorySync::log);
        Assert.assertNotNull(syncedState.byPath(filename));
        Assert.assertTrue(syncedState.hasRemoteDelete(filename));
        Assert.assertTrue(base1.resolve(filename).toFile().exists());

        // restore the remote file (it should be removed from the delete list)
        Files.write(base2.resolve(filename), data, StandardOpenOption.CREATE);
        DirectorySync.syncDir(localFs, remoteFs, syncLocalDeletes, syncRemoteDeletes, null, null, syncedState, 32, 5, Files.createTempDirectory("peergos-sync"), crypto, () -> false, DirectorySync::log);
        Assert.assertArrayEquals(Files.readAllBytes(base2.resolve(filename)), data);
        Assert.assertArrayEquals(Files.readAllBytes(base1.resolve(filename)), data);
        Assert.assertFalse(syncedState.hasRemoteDelete(filename));
        Assert.assertEquals(1, syncedState.allFilePaths().size());
    }

    @Test
    public void ignoreRemoteDeleteBeforeRemoteModification() throws Exception {
        ignoreRemoteDeleteBeforeRemoteModification(6 * 1024 * 1024);
        ignoreRemoteDeleteBeforeRemoteModification(1024);
    }

    public void ignoreRemoteDeleteBeforeRemoteModification(int fileSize) throws Exception {
        Path base1 = Files.createTempDirectory("peergos-sync");
        Path base2 = Files.createTempDirectory("peergos-sync");

        LocalFileSystem localFs = new LocalFileSystem(base1, Main.initCrypto().hasher);
        LocalFileSystem remoteFs = new LocalFileSystem(base2, Main.initCrypto().hasher);
        SyncState syncedState = new JdbcTreeState(":memory:");

        boolean syncLocalDeletes = true;
        boolean syncRemoteDeletes = false;
        DirectorySync.syncDir(localFs, remoteFs, syncLocalDeletes, syncRemoteDeletes, null, null, syncedState, 32, 5, Files.createTempDirectory("peergos-sync"), crypto, () -> false, DirectorySync::log);

        byte[] data = new byte[fileSize];
        new Random(42).nextBytes(data);
        String filename = "file.bin";
        Files.write(base1.resolve(filename), data, StandardOpenOption.CREATE);

        DirectorySync.syncDir(localFs, remoteFs, syncLocalDeletes, syncRemoteDeletes, null, null, syncedState, 32, 5, Files.createTempDirectory("peergos-sync"), crypto, () -> false, DirectorySync::log);
        Assert.assertNotNull(syncedState.byPath(filename));

        // delete remote file and check local is not deleted
        Files.delete(base2.resolve(filename));
        DirectorySync.syncDir(localFs, remoteFs, syncLocalDeletes, syncRemoteDeletes, null, null, syncedState, 32, 5, Files.createTempDirectory("peergos-sync"), crypto, () -> false, DirectorySync::log);
        Assert.assertNotNull(syncedState.byPath(filename));
        Assert.assertTrue(base1.resolve(filename).toFile().exists());
        Assert.assertTrue(syncedState.hasRemoteDelete(filename));
        DirectorySync.syncDir(localFs, remoteFs, syncLocalDeletes, syncRemoteDeletes, null, null, syncedState, 32, 5, Files.createTempDirectory("peergos-sync"), crypto, () -> false, DirectorySync::log);
        Assert.assertNotNull(syncedState.byPath(filename));
        Assert.assertTrue(syncedState.hasRemoteDelete(filename));
        Assert.assertTrue(base1.resolve(filename).toFile().exists());

        // modify the local file (it should be copied to remote)
        byte[] data2 = new byte[fileSize + 1024 * 1024];
        new Random(28).nextBytes(data2);
        Files.delete(base1.resolve(filename));
        Files.write(base1.resolve(filename), data2, StandardOpenOption.CREATE);
        DirectorySync.syncDir(localFs, remoteFs, syncLocalDeletes, syncRemoteDeletes, null, null, syncedState, 32, 5, Files.createTempDirectory("peergos-sync"), crypto, () -> false, DirectorySync::log);
        Assert.assertArrayEquals(Files.readAllBytes(base2.resolve(filename)), data2);
        Assert.assertArrayEquals(Files.readAllBytes(base1.resolve(filename)), data2);
        Assert.assertFalse(syncedState.hasRemoteDelete(filename));
        Assert.assertEquals(1, syncedState.allFilePaths().size());
    }

    @Test
    public void diffRangesAcrossLevel1Boundary() {
        // level1 holds one ChunkHashList per 1024 chunks (== 5 GiB). A file that grew from
        // 1024 chunks (one group) to 1025 chunks (two groups) has more level1 groups than the
        // old version, so diffRanges must not index past the shorter list. The first group is
        // identical here, so the only diff is the single new chunk (index 1024).
        List<ChunkHashList> grownLevel1 = Arrays.asList(
                new ChunkHashList(new byte[1024 * 32]),
                new ChunkHashList(new byte[32]));
        List<ChunkHashList> oldLevel1 = Arrays.asList(
                new ChunkHashList(new byte[1024 * 32]));
        byte[] rootA = new byte[32]; rootA[0] = 1;
        byte[] rootB = new byte[32]; rootB[0] = 2;
        HashTree grown = new HashTree(new RootHash(rootA), grownLevel1, Collections.emptyList(), Collections.emptyList());
        HashTree old = new HashTree(new RootHash(rootB), oldLevel1, Collections.emptyList(), Collections.emptyList());

        FileState grownFs = new FileState("big.bin", 1000, 1025L * Chunk.LEGACY_SIZE, grown);
        FileState oldFs = new FileState("big.bin", 1000, 1024L * Chunk.LEGACY_SIZE, old);

        List<Pair<Long, Long>> diff = grownFs.diffRanges(oldFs);
        Assert.assertEquals(1, diff.size());
        Assert.assertEquals(1024L * Chunk.LEGACY_SIZE, (long) diff.get(0).left);
        Assert.assertEquals(1025L * Chunk.LEGACY_SIZE, (long) diff.get(0).right);
    }

    @Test
    public void diffRangesOfAShrunkFileStayWithinIt() {
        byte[] same = new byte[32 * 3];
        byte[] longer = new byte[32 * 8];
        byte[] root1 = new byte[32]; root1[0] = 1;
        byte[] root2 = new byte[32]; root2[0] = 2;
        HashTree shrunkTree = new HashTree(new RootHash(root1), List.of(new ChunkHashList(same)), Collections.emptyList(), Collections.emptyList());
        HashTree oldTree = new HashTree(new RootHash(root2), List.of(new ChunkHashList(longer)), Collections.emptyList(), Collections.emptyList());
        long chunk = Chunk.LEGACY_SIZE;

        FileState old = new FileState("f.bin", 1000, 8 * chunk, oldTree);
        byte[] partialLast = new byte[32 * 3];
        partialLast[32 * 2] = 1;
        HashTree partialLastTree = new HashTree(new RootHash(root1), List.of(new ChunkHashList(partialLast)), Collections.emptyList(), Collections.emptyList());
        FileState withinLastChunk = new FileState("f.bin", 1000, 3 * chunk - 10, partialLastTree);
        Assert.assertEquals(List.of(new Pair<>(2 * chunk, 3 * chunk - 10)), withinLastChunk.diffRanges(old));

        // every remaining chunk is unchanged, so there is nothing to copy, only the truncate
        FileState atBoundary = new FileState("f.bin", 1000, 3 * chunk, shrunkTree);
        Assert.assertEquals(List.of(new Pair<>(3 * chunk, 3 * chunk)), atBoundary.diffRanges(old));

        HashTree emptyTree = new HashTree(new RootHash(root1), List.of(new ChunkHashList(new byte[32])), Collections.emptyList(), Collections.emptyList());
        FileState empty = new FileState("f.bin", 1000, 0, emptyTree);
        Assert.assertEquals(List.of(new Pair<>(0L, 0L)), empty.diffRanges(old));
    }

    @Test
    public void aStoppedSyncStopsScanning() throws Exception {
        // Pausing used to stop only the transfers: the walk carried on hashing every file and
        // logging a skip line for each one, which on a large folder is minutes of work after
        // the user asked for it to stop.
        Path dir = Files.createTempDirectory("peergos-sync");
        byte[] data = new byte[1024];
        new Random(1).nextBytes(data);
        Files.createDirectory(dir.resolve("sub"));
        for (int i = 0; i < 25; i++)
            Files.write(dir.resolve("sub").resolve("f" + i + ".bin"), data, StandardOpenOption.CREATE);
        LocalFileSystem fs = new LocalFileSystem(dir, crypto.hasher);
        SyncState scanned = new JdbcTreeState(":memory:");
        SyncState synced = new JdbcTreeState(":memory:");

        boolean threw = false;
        try {
            DirectorySync.buildDirState(fs, scanned, synced, () -> true);
        } catch (RuntimeException e) {
            threw = true;
        }
        Assert.assertTrue("A stopped sync must abort the scan", threw);
        Assert.assertEquals("and must not keep scanning files after it is stopped", 0, scanned.filesCount());
    }

    @Test
    public void cancelledCopyStaysResumable() throws Exception {
        // Reproduces the race where a queued parallel download runs after the user cancelled:
        // applyCopyOp sees isCancelled==true at its top guard and never writes. It must abort
        // (throw) rather than complete normally, otherwise copyFileDiffAndTruncate clears the
        // retry journal and the caller marks the (never-written) file synced -> next run treats
        // it as a local delete and deletes the only remaining (remote) copy.
        Path base1 = Files.createTempDirectory("peergos-sync"); // target (local)
        Path base2 = Files.createTempDirectory("peergos-sync"); // source (remote)
        LocalFileSystem targetFs = new LocalFileSystem(base1, crypto.hasher);
        LocalFileSystem srcFs = new LocalFileSystem(base2, crypto.hasher);
        SyncState syncDb = new JdbcTreeState(":memory:");

        byte[] data = new byte[1024 * 1024];
        new Random(1).nextBytes(data);
        Files.write(base2.resolve("f.bin"), data, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        HashTree hash = srcFs.hashFile(Paths.get("f.bin"), Optional.empty(), "f.bin", syncDb, data.length, Chunk.LEGACY_SIZE);
        FileState remote = new FileState("f.bin", srcFs.getLastModified(Paths.get("f.bin")), data.length, hash);
        List<CopyOp> ops = List.of(new CopyOp(true, srcFs.resolve("f.bin"), targetFs.resolve("f.bin"), remote, null,
                0, data.length, peergos.shared.user.fs.ResumeUploadProps.random(crypto)));

        boolean threw = false;
        try {
            DirectorySync.copyFileDiffAndTruncate(srcFs, targetFs, ops, syncDb, () -> true, DirectorySync::log);
        } catch (RuntimeException e) {
            threw = true;
        }
        Assert.assertTrue("A cancelled copy must abort rather than complete normally", threw);
        Assert.assertEquals("A cancelled copy must remain in the retry journal", 1, syncDb.getInProgressCopies().size());
    }

    /** The process dying, which leaves no chance to record anything */
    private static class Crash extends Error {}

    /** Syncs a file, unless original is null, changes it on the second side, and interrupts the download of the change
     *  after a number of writes, by cancelling it or by crashing, leaving the copy to be resumed by the next sync.
     */
    private static void interruptDownload(Path base1, Path base2, SyncState state, byte[] original, byte[] changed, int writes,
                                          boolean crash) throws Exception {
        interruptDownload(base1, base2, state, "f.bin", original, changed, writes, crash);
    }

    private static void interruptDownload(Path base1, Path base2, SyncState state, String relPath, byte[] original, byte[] changed,
                                          int writes, boolean crash) throws Exception {
        AtomicInteger checks = new AtomicInteger();
        try {
            download(base1, base2, state, relPath, original, changed, () -> {
                if (checks.incrementAndGet() <= writes)
                    return false;
                if (crash)
                    throw new Crash();
                return true;
            });
            Assert.fail("The download was not interrupted");
        } catch (RuntimeException | Crash expected) {}
        Assert.assertFalse(state.getInProgressCopies().isEmpty());
    }

    /** Syncs a file, unless original is null, changes it on the second side, and copies the change to the first */
    private static void download(Path base1, Path base2, SyncState state, String relPath, byte[] original, byte[] changed,
                                 java.util.function.Supplier<Boolean> isCancelled) throws Exception {
        Path rel = Paths.get(relPath);
        if (original != null) {
            Files.write(base1.resolve(rel), original);
            syncBoth(base1, base2, state);
        }
        Files.createDirectories(base2.resolve(rel).getParent());
        Files.write(base2.resolve(rel), changed);
        Files.setLastModifiedTime(base2.resolve(rel), java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() / 1000 * 1000 + 5_000));
        LocalFileSystem localFs = new LocalFileSystem(base1, crypto.hasher), remoteFs = new LocalFileSystem(base2, crypto.hasher);
        FileState synced = state.byPath(relPath);
        int chunkSize = synced != null ? synced.chunkSize : Chunk.LEGACY_SIZE;
        HashTree hash = remoteFs.hashFile(rel, Optional.empty(), relPath, state, changed.length, chunkSize);
        FileState remote = new FileState(relPath, remoteFs.getLastModified(rel), changed.length, hash, chunkSize);
        List<CopyOp> ops = remote.diffRanges(synced).stream()
                .map(d -> new CopyOp(true, remoteFs.resolve(relPath), localFs.resolve(relPath), remote, synced, d.left, d.right,
                        ResumeUploadProps.random(crypto)))
                .collect(Collectors.toList());
        DirectorySync.copyFileDiffAndTruncate(remoteFs, localFs, ops, state, isCancelled, DirectorySync::log);
    }

    private static byte[] randomText(int size, int seed) {
        Random r = new Random(seed);
        byte[] data = new byte[size];
        for (int i = 0; i < size; i++)
            data[i] = (byte) ('a' + r.nextInt(26));
        return data;
    }

    @Test
    public void editAfterAnInterruptedDownloadIsKept() throws Exception {
        // resuming the download wrote over the edit, as a half written file and an edited one looked the same
        for (boolean crash : List.of(false, true)) {
            Path base1 = Files.createTempDirectory("peergos-sync"), base2 = Files.createTempDirectory("peergos-sync");
            SyncState state = new JdbcTreeState(":memory:");
            byte[] changed = randomText(64 * 1024, 2);
            interruptDownload(base1, base2, state, randomText(64 * 1024, 1), changed, 5, crash);

            Files.write(base1.resolve("f.bin"), "an edit".getBytes());
            syncBoth(base1, base2, state);

            Map<String, String> expected = Map.of("f.bin", new String(changed), "f[conflict-0].bin", "an edit");
            Assert.assertEquals("crash " + crash, expected, tree(base1));
            Assert.assertEquals("crash " + crash, expected, tree(base2));
        }
    }

    @Test
    public void sameSizeEditAfterAnInterruptedDownloadIsKept() throws Exception {
        // within the chunk being written, so only the record of where the download stopped shows it
        Path base1 = Files.createTempDirectory("peergos-sync"), base2 = Files.createTempDirectory("peergos-sync");
        SyncState state = new JdbcTreeState(":memory:");
        byte[] changed = randomText(64 * 1024, 2);
        interruptDownload(base1, base2, state, randomText(64 * 1024, 1), changed, 5, false);

        byte[] edit = randomText(64 * 1024, 3);
        Files.write(base1.resolve("f.bin"), edit);
        // modification times are to the second, and a person's edit comes later than the one that stopped the download
        Files.setLastModifiedTime(base1.resolve("f.bin"), java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 3_000));
        syncBoth(base1, base2, state);

        Map<String, String> expected = Map.of("f.bin", new String(changed), "f[conflict-0].bin", new String(edit));
        Assert.assertEquals(expected, tree(base1));
        Assert.assertEquals(expected, tree(base2));
    }

    @Test
    public void interruptedDownloadResumes() throws Exception {
        for (boolean crash : List.of(false, true)) {
            // the copy stopped part way through a chunk, and past the end of the old version
            Path base1 = Files.createTempDirectory("peergos-sync"), base2 = Files.createTempDirectory("peergos-sync");
            SyncState state = new JdbcTreeState(":memory:");
            byte[] changed = randomText(96 * 1024, 2);
            interruptDownload(base1, base2, state, randomText(64 * 1024, 1), changed, 20, crash);
            Assert.assertArrayEquals("crash " + crash, randomText(64 * 1024, 1), Files.readAllBytes(base1.resolve("f.bin")));

            syncBoth(base1, base2, state);
            Map<String, String> expected = Map.of("f.bin", new String(changed));
            Assert.assertEquals("crash " + crash, expected, tree(base1));
            Assert.assertEquals("crash " + crash, expected, tree(base2));

            // and part way through a chunk within the old version
            Path base3 = Files.createTempDirectory("peergos-sync"), base4 = Files.createTempDirectory("peergos-sync");
            SyncState state2 = new JdbcTreeState(":memory:");
            byte[] sameSize = randomText(64 * 1024, 3);
            interruptDownload(base3, base4, state2, randomText(64 * 1024, 1), sameSize, 5, crash);
            syncBoth(base3, base4, state2);
            Assert.assertEquals("crash " + crash, Map.of("f.bin", new String(sameSize)), tree(base3));
            Assert.assertEquals("crash " + crash, Map.of("f.bin", new String(sameSize)), tree(base4));
        }
    }

    @Test
    public void crashWhileResumingAnInterruptedDownload() throws Exception {
        // the resumed download had written past where the first one stopped, so it looked like an edit, and the part
        // written was kept as a conflict copy
        Path base1 = Files.createTempDirectory("peergos-sync"), base2 = Files.createTempDirectory("peergos-sync");
        SyncState state = new JdbcTreeState(":memory:");
        byte[] changed = randomText(96 * 1024, 2);
        interruptDownload(base1, base2, state, randomText(64 * 1024, 1), changed, 5, false);

        AtomicInteger checks = new AtomicInteger();
        try {
            DirectorySync.syncDir(new LocalFileSystem(base1, crypto.hasher), new LocalFileSystem(base2, crypto.hasher), true, true,
                    null, null, state, 32, 5, Files.createTempDirectory("peergos-sync"), crypto, () -> {
                        if (checks.incrementAndGet() > 20)
                            throw new Crash();
                        return false;
                    }, DirectorySync::log);
            Assert.fail("The resumed download did not crash");
        } catch (Crash expected) {}
        Assert.assertArrayEquals(randomText(64 * 1024, 1), Files.readAllBytes(base1.resolve("f.bin")));

        syncBoth(base1, base2, state);
        Map<String, String> expected = Map.of("f.bin", new String(changed));
        Assert.assertEquals(expected, tree(base1));
        Assert.assertEquals(expected, tree(base2));
    }

    @Test
    public void editOfAFileTheOtherSideMovedWithTheSameEdit() throws Exception {
        // the edit was uploaded to the old path, and then taken for the source of the move, leaving it on one side only
        Path base1 = Files.createTempDirectory("peergos-sync"), base2 = Files.createTempDirectory("peergos-sync");
        SyncState state = new JdbcTreeState(":memory:");
        write(base1, "b/f", "original");
        syncBoth(base1, base2, state);

        write(base1, "b/f", "the same edit");
        Files.move(base2.resolve("b"), base2.resolve("e"));
        write(base2, "e/f", "the same edit");
        syncBoth(base1, base2, state);
        Map<String, String> synced = tree(base1);
        Assert.assertEquals(synced, tree(base2));
        Assert.assertEquals("the same edit", synced.get("e/f"));
    }

    @Test
    public void folderReplacedByAFileWhileItsDeleteSyncs() throws Exception {
        // the folder's delete deleted the file instead
        Path base1 = Files.createTempDirectory("peergos-sync"), base2 = Files.createTempDirectory("peergos-sync");
        SyncState state = new JdbcTreeState(":memory:");
        Files.createDirectories(base1.resolve("d"));
        syncBoth(base1, base2, state);

        Files.delete(base1.resolve("d"));
        DirectorySync.syncDir(new LocalFileSystem(base1, crypto.hasher), new LocalFileSystem(base2, crypto.hasher), true, true,
                null, null, state, 32, 5, Files.createTempDirectory("peergos-sync"), crypto, () -> false, m -> {
                    if (m.contains("delete dir d")) {
                        try {
                            Files.delete(base2.resolve("d"));
                            Files.write(base2.resolve("d"), "a new file".getBytes());
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    }
                });
        syncBoth(base1, base2, state);
        Map<String, String> expected = Map.of("d", "a new file");
        Assert.assertEquals(expected, tree(base1));
        Assert.assertEquals(expected, tree(base2));
    }

    @Test
    public void folderMovedOverOneDeletedOnTheOtherSideWhenDeletesAreNotSynced() throws Exception {
        // the file moved over the one kept here was taken for an edit of it, so the one kept was overwritten
        for (boolean inDrive : List.of(true, false)) {
            Path base1 = Files.createTempDirectory("peergos-sync"), base2 = Files.createTempDirectory("peergos-sync");
            SyncState state = new JdbcTreeState(":memory:");
            write(base1, "a/f", "kept");
            write(base1, "b/f", "moved");
            syncKeepingDeletes(base1, base2, state);

            Path moved = inDrive ? base2 : base1;
            deleteTree(moved.resolve("a"));
            Files.move(moved.resolve("b"), moved.resolve("a"));
            syncKeepingDeletes(base1, base2, state);
            syncKeepingDeletes(base1, base2, state);
            for (Path base : List.of(base1, base2))
                Assert.assertTrue("in Drive " + inDrive, tree(base).values().containsAll(List.of("kept", "moved")));
        }
    }

    @Test
    public void fileAddedOnTheOtherSideWhileANewFileThereUploads() throws Exception {
        // the bulk upload of new small files replaced the file put at the same path after the scan
        Path base1 = Files.createTempDirectory("peergos-sync"), base2 = Files.createTempDirectory("peergos-sync");
        SyncState state = new JdbcTreeState(":memory:");
        write(base1, "keep", "keep");
        syncBoth(base1, base2, state);

        write(base1, "f", "new here");
        try {
            DirectorySync.syncDir(new LocalFileSystem(base1, crypto.hasher), new LocalFileSystem(base2, crypto.hasher), true, true,
                    null, null, state, 32, 5, Files.createTempDirectory("peergos-sync"), crypto, () -> false, m -> {
                        if (m.contains("remote files in")) {
                            try {
                                write(base2, "f", "new there");
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        }
                    });
        } catch (Exception e) {
            // a sync that finds a file where it was about to put one leaves both to the next
        }
        syncBoth(base1, base2, state);
        Assert.assertEquals(tree(base1), tree(base2));
        Assert.assertTrue(tree(base1).toString(), tree(base1).values().containsAll(List.of("keep", "new here", "new there")));
    }

    @Test
    public void editAfterTheScanOfAFileDeletedOnTheOtherSide() throws Exception {
        // the delete was synced over the edit
        Path base1 = Files.createTempDirectory("peergos-sync"), base2 = Files.createTempDirectory("peergos-sync");
        SyncState state = new JdbcTreeState(":memory:");
        write(base1, "f", "original");
        syncBoth(base1, base2, state);

        Files.delete(base2.resolve("f"));
        DirectorySync.syncDir(new LocalFileSystem(base1, crypto.hasher), new LocalFileSystem(base2, crypto.hasher), true, true,
                null, null, state, 32, 5, Files.createTempDirectory("peergos-sync"), crypto, () -> false, m -> {
                    if (m.contains("remote files in")) {
                        try {
                            write(base1, "f", "edited after the scan");
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    }
                });
        // a record of it as synced would be taken for a copy in Drive by a pass with nothing changed there
        Assert.assertNull(state.byPath("f"));
        syncBoth(base1, base2, state);
        Map<String, String> expected = Map.of("f", "edited after the scan");
        Assert.assertEquals(expected, tree(base1));
        Assert.assertEquals(expected, tree(base2));
    }

    @Test(timeout = 60_000)
    public void sourceShrinksDuringADownload() throws Exception {
        // the copy kept reading nothing from the end of the source, forever
        Path base1 = Files.createTempDirectory("peergos-sync"), base2 = Files.createTempDirectory("peergos-sync");
        SyncState state = new JdbcTreeState(":memory:");
        syncBoth(base1, base2, state);
        byte[] shorter = randomText(1000, 3);
        AtomicInteger checks = new AtomicInteger();
        try {
            download(base1, base2, state, "f.bin", null, randomText(64 * 1024, 2), () -> {
                if (checks.incrementAndGet() == 5) {
                    try {
                        Files.write(base2.resolve("f.bin"), shorter);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }
                return false;
            });
            Assert.fail("A download of a source that shrank completed");
        } catch (IOException | RuntimeException expected) {}

        syncBoth(base1, base2, state);
        Map<String, String> expected = Map.of("f.bin", new String(shorter));
        Assert.assertEquals(expected, tree(base1));
        Assert.assertEquals(expected, tree(base2));
    }

    @Test
    public void folderMadeWhereAnInitialUploadIsGoing() throws Exception {
        // the part uploaded was visible, so it could be replaced, which the next sync took for a delete of the original
        Path base1 = Files.createTempDirectory("peergos-sync"), base2 = Files.createTempDirectory("peergos-sync");
        SyncState state = new JdbcTreeState(":memory:");
        byte[] data = randomText(3 * 1024 * 1024, 1);
        Files.write(base1.resolve("big"), data);
        try {
            DirectorySync.syncDir(new LocalFileSystem(base1, crypto.hasher), new LocalFileSystem(base2, crypto.hasher), true, true,
                    null, null, state, 32, 5, Files.createTempDirectory("peergos-sync"), crypto, () -> false, m -> {
                        if (m.startsWith("Downloaded 1 /") && ! Files.isDirectory(base2.resolve("big"))) {
                            try {
                                Files.deleteIfExists(base2.resolve("big"));
                                Files.createDirectory(base2.resolve("big"));
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        }
                    });
        } catch (RuntimeException e) {
            // the folder came first
        }
        syncBoth(base1, base2, state);
        syncBoth(base1, base2, state);
        Map<String, String> synced = tree(base1);
        Assert.assertEquals(synced, tree(base2));
        Assert.assertTrue(synced.keySet().toString(), synced.containsValue(new String(data)));
    }

    @Test
    public void changeAfterTheScanOfAFileDeletedHere() throws Exception {
        // the other side's file was deleted in bulk as the sync saw it, losing a change made to it since
        for (boolean toFolder : List.of(false, true)) {
            Path base1 = Files.createTempDirectory("peergos-sync"), base2 = Files.createTempDirectory("peergos-sync");
            SyncState state = new JdbcTreeState(":memory:");
            write(base1, "f", "original");
            syncBoth(base1, base2, state);

            Files.delete(base1.resolve("f"));
            DirectorySync.syncDir(new LocalFileSystem(base1, crypto.hasher), new LocalFileSystem(base2, crypto.hasher), true, true,
                    null, null, state, 32, 5, Files.createTempDirectory("peergos-sync"), crypto, () -> false, m -> {
                        if (m.contains("remote files in")) {
                            try {
                                if (toFolder) {
                                    Files.delete(base2.resolve("f"));
                                    write(base2, "f/g", "in the folder");
                                } else
                                    write(base2, "f", "edited after the scan");
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        }
                    });
            Assert.assertNull("folder " + toFolder, state.byPath("f"));
            syncBoth(base1, base2, state);
            Map<String, String> expected = toFolder ? Map.of("f", "dir", "f/g", "in the folder") : Map.of("f", "edited after the scan");
            Assert.assertEquals("folder " + toFolder, expected, tree(base1));
            Assert.assertEquals("folder " + toFolder, expected, tree(base2));
        }
    }

    @Test
    public void fileDeletedOnBothSidesWhileTheDeleteSyncs() throws Exception {
        // the sync kept a record of the file as synced, which a pass that reuses that record for Drive took for a file
        // there still
        for (boolean here : List.of(true, false)) {
            Path base1 = Files.createTempDirectory("peergos-sync"), base2 = Files.createTempDirectory("peergos-sync");
            SyncState state = new JdbcTreeState(":memory:");
            write(base1, "d/f", "original");
            write(base1, "d/g", "stays");
            syncBoth(base1, base2, state);

            Path deletedFirst = here ? base2 : base1, deletedSince = here ? base1 : base2;
            Files.delete(deletedFirst.resolve("d/f"));
            DirectorySync.syncDir(new LocalFileSystem(base1, crypto.hasher), new LocalFileSystem(base2, crypto.hasher), true, true,
                    null, null, state, 32, 5, Files.createTempDirectory("peergos-sync"), crypto, () -> false, m -> {
                        if (m.contains("remote files in")) {
                            try {
                                Files.delete(deletedSince.resolve("d/f"));
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        }
                    });
            Assert.assertNull("here " + here, state.byPath("d/f"));
            Map<String, String> expected = Map.of("d", "dir", "d/g", "stays");
            Assert.assertEquals("here " + here, expected, tree(base1));
            Assert.assertEquals("here " + here, expected, tree(base2));
        }
    }

    @Test
    public void sourceRewrittenDuringADownload() throws Exception {
        // the download took part of each version, under the hash of the first, which the next sync kept as a conflict
        Path base1 = Files.createTempDirectory("peergos-sync"), base2 = Files.createTempDirectory("peergos-sync");
        SyncState state = new JdbcTreeState(":memory:");
        syncBoth(base1, base2, state);
        byte[] rewritten = randomText(64 * 1024, 3);
        AtomicInteger checks = new AtomicInteger();
        try {
            download(base1, base2, state, "f.bin", null, randomText(64 * 1024, 2), () -> {
                if (checks.incrementAndGet() == 5) {
                    try {
                        Files.write(base2.resolve("f.bin"), rewritten);
                        Files.setLastModifiedTime(base2.resolve("f.bin"), java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 60_000));
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }
                return false;
            });
            Assert.fail("A download of a source that changed completed");
        } catch (ConcurrentChangeException expected) {}

        syncBoth(base1, base2, state);
        Map<String, String> expected = Map.of("f.bin", new String(rewritten));
        Assert.assertEquals(expected, tree(base1));
        Assert.assertEquals(expected, tree(base2));
    }

    @Test
    public void smallFileGrowsAfterTheScan() throws Exception {
        // the bulk upload took as much of the new version as the scan had seen, under the hash of the old one
        Path base1 = Files.createTempDirectory("peergos-sync"), base2 = Files.createTempDirectory("peergos-sync");
        SyncState state = new JdbcTreeState(":memory:");
        syncBoth(base1, base2, state);
        write(base1, "f", "small");
        // older than the upload, so that what was uploaded is seen as it is
        Files.setLastModifiedTime(base1.resolve("f"), java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() - 60_000));
        byte[] grown = randomText(64 * 1024, 4);
        try {
            DirectorySync.syncDir(new LocalFileSystem(base1, crypto.hasher), new LocalFileSystem(base2, crypto.hasher), true, true,
                    null, null, state, 32, 5, Files.createTempDirectory("peergos-sync"), crypto, () -> false, m -> {
                        if (m.contains("remote files in")) {
                            try {
                                Files.write(base1.resolve("f"), grown);
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        }
                    });
        } catch (RuntimeException e) {
            // the upload saw the change
        }
        syncBoth(base1, base2, state);
        Map<String, String> expected = Map.of("f", new String(grown));
        Assert.assertEquals(expected, tree(base1));
        Assert.assertEquals(expected, tree(base2));
    }

    @Test
    public void failedDownloadStopsTheOthersBeforeTheSyncReturns() throws Exception {
        // the sync failed with the first, and left the others running into the next sync
        Path base1 = Files.createTempDirectory("peergos-sync"), base2 = Files.createTempDirectory("peergos-sync");
        SyncState state = new JdbcTreeState(":memory:");
        syncBoth(base1, base2, state);
        for (int i = 0; i < 6; i++)
            Files.write(base2.resolve("f" + i), randomText(3 * 1024 * 1024, i));
        AtomicBoolean returned = new AtomicBoolean(false);
        AtomicInteger lateLines = new AtomicInteger();
        try {
            DirectorySync.syncDir(new LocalFileSystem(base1, crypto.hasher), new LocalFileSystem(base2, crypto.hasher), true, true,
                    null, null, state, 32, 5, Files.createTempDirectory("peergos-sync"), crypto, () -> false, m -> {
                        if (returned.get())
                            lateLines.incrementAndGet();
                        if (m.contains("Copying f0"))
                            throw new IllegalStateException("a failed download");
                        // the others are still going when the first fails
                        if (m.contains("Copying f")) {
                            try {
                                Thread.sleep(1_000);
                            } catch (InterruptedException e) {}
                        }
                    });
            Assert.fail("The sync didn't fail");
        } catch (IllegalStateException expected) {}
        returned.set(true);
        Thread.sleep(2_000);
        Assert.assertEquals(0, lateLines.get());

        syncBoth(base1, base2, state);
        Assert.assertEquals(tree(base2), tree(base1));
    }

    @Test
    public void newFolderWhereAFileWasRenamedFrom() throws Exception {
        // the new folder's small files were bulk uploaded before the rename, into the file still at that path
        Path base1 = Files.createTempDirectory("peergos-sync"), base2 = Files.createTempDirectory("peergos-sync");
        SyncState state = new JdbcTreeState(":memory:");
        write(base1, "x.txt", "file");
        syncBoth(base1, base2, state);

        Files.move(base1.resolve("x.txt"), base1.resolve("y.txt"));
        write(base1, "x.txt/doc.pdf", "doc");
        syncBoth(base1, base2, state);
        Map<String, String> expected = Map.of("x.txt", "dir", "x.txt/doc.pdf", "doc", "y.txt", "file");
        Assert.assertEquals(expected, tree(base1));
        Assert.assertEquals(expected, tree(base2));
    }

    /** Downloads a change in the middle of a file large enough to have its diff staged into base1, with fs1 */
    private static byte[] downloadLargeFileChange(Path base1, Path base2, SyncState state, LocalFileSystem fs1,
                                                  java.util.function.Supplier<Boolean> isCancelled) throws Exception {
        byte[] original = randomText(12 * 1024 * 1024, 1);
        Files.write(base1.resolve("big.bin"), original);
        syncBoth(base1, base2, state);
        byte[] changed = original.clone();
        System.arraycopy(randomText(1000, 2), 0, changed, 6 * 1024 * 1024, 1000);
        Files.write(base2.resolve("big.bin"), changed);
        Files.setLastModifiedTime(base2.resolve("big.bin"), java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() / 1000 * 1000 + 5_000));
        LocalFileSystem fs2 = new LocalFileSystem(base2, crypto.hasher);
        FileState synced = state.byPath("big.bin");
        Path rel = Paths.get("big.bin");
        HashTree hash = fs2.hashFile(rel, Optional.empty(), "big.bin", state, changed.length, synced.chunkSize);
        FileState remote = new FileState("big.bin", fs2.getLastModified(rel), changed.length, hash, synced.chunkSize);
        List<CopyOp> ops = remote.diffRanges(synced).stream()
                .map(d -> new CopyOp(true, fs2.resolve("big.bin"), fs1.resolve("big.bin"), remote, synced, d.left, d.right,
                        ResumeUploadProps.random(crypto)))
                .collect(Collectors.toList());
        Assert.assertTrue("only part of it changes", ops.stream().mapToLong(op -> op.diffEnd - op.diffStart).sum() < changed.length);
        try {
            DirectorySync.copyFileDiffAndTruncate(fs2, fs1, ops, state, isCancelled, DirectorySync::log);
            Assert.fail("The download was not interrupted");
        } catch (RuntimeException | Crash expected) {}
        Assert.assertFalse(state.getInProgressCopies().isEmpty());
        Assert.assertArrayEquals("the file is as it was until all of the change is here", original, Files.readAllBytes(base1.resolve("big.bin")));
        return changed;
    }

    private static List<Path> staged(Path base) throws IOException {
        try (Stream<Path> files = Files.list(base.resolve(LocalFileSystem.PARTIAL_COPIES_DIR))) {
            return files.collect(Collectors.toList());
        }
    }

    @Test
    public void largeFileChangeIsStagedAsItsDiff() throws Exception {
        // the whole file was copied aside to write the change into, which for a large file is a lot of disk for a small change
        for (boolean crash : List.of(false, true)) {
            Path base1 = Files.createTempDirectory("peergos-sync"), base2 = Files.createTempDirectory("peergos-sync");
            SyncState state = new JdbcTreeState(":memory:");
            AtomicInteger checks = new AtomicInteger();
            byte[] changed = downloadLargeFileChange(base1, base2, state, new LocalFileSystem(base1, crypto.hasher, 1024 * 1024), () -> {
                if (checks.incrementAndGet() <= 5)
                    return false;
                if (crash)
                    throw new Crash();
                return true;
            });
            List<Path> staged = staged(base1);
            Assert.assertEquals(1, staged.size());
            Assert.assertTrue(staged.get(0).getFileName().toString().endsWith(".diff"));

            DirectorySync.syncDir(new LocalFileSystem(base1, crypto.hasher, 1024 * 1024), new LocalFileSystem(base2, crypto.hasher), true, true,
                    null, null, state, 32, 5, Files.createTempDirectory("peergos-sync"), crypto, () -> false, DirectorySync::log);
            Assert.assertArrayEquals("crash " + crash, changed, Files.readAllBytes(base1.resolve("big.bin")));
            Assert.assertFalse(Files.exists(base1.resolve(LocalFileSystem.PARTIAL_COPIES_DIR)));
        }
    }

    @Test
    public void stopWhileAStagedDiffIsPutInPlace() throws Exception {
        // the file is part written once its change starts going in, which the next sync took for an edit made meanwhile
        Path base1 = Files.createTempDirectory("peergos-sync"), base2 = Files.createTempDirectory("peergos-sync");
        SyncState state = new JdbcTreeState(":memory:");
        LocalFileSystem stopsBeforePuttingInPlace = new LocalFileSystem(base1, crypto.hasher, 1024 * 1024) {
            @Override
            public void completeCopy(Path p, List<Pair<Long, Long>> ranges, long size) {
                throw new Crash();
            }
        };
        byte[] changed = downloadLargeFileChange(base1, base2, state, stopsBeforePuttingInPlace, () -> false);
        // as if it stopped part way through putting the change in
        Path diff = staged(base1).get(0);
        Files.createFile(diff.resolveSibling(diff.getFileName().toString().replace(".diff", ".applying")));
        try (java.nio.channels.FileChannel f = java.nio.channels.FileChannel.open(base1.resolve("big.bin"), java.nio.file.StandardOpenOption.WRITE)) {
            f.write(java.nio.ByteBuffer.wrap(Arrays.copyOfRange(changed, 6 * 1024 * 1024, 6 * 1024 * 1024 + 500)), 6 * 1024 * 1024);
        }

        DirectorySync.syncDir(new LocalFileSystem(base1, crypto.hasher, 1024 * 1024), new LocalFileSystem(base2, crypto.hasher), true, true,
                null, null, state, 32, 5, Files.createTempDirectory("peergos-sync"), crypto, () -> false, DirectorySync::log);
        Assert.assertArrayEquals(changed, Files.readAllBytes(base1.resolve("big.bin")));
        try (Stream<Path> files = Files.list(base1)) {
            Assert.assertEquals(List.of(base1.resolve("big.bin")), files.collect(Collectors.toList()));
        }
    }

    @Test
    public void folderMovedAfterAnInterruptedDownload() throws Exception {
        // the part written moved with its folder, out of reach of the resume, and was synced as a file of its own
        for (boolean crash : List.of(false, true)) {
            Path base1 = Files.createTempDirectory("peergos-sync"), base2 = Files.createTempDirectory("peergos-sync");
            SyncState state = new JdbcTreeState(":memory:");
            write(base1, "d/old.txt", "old");
            syncBoth(base1, base2, state);
            byte[] data = randomText(64 * 1024, 2);
            interruptDownload(base1, base2, state, "d/f.bin", null, data, 5, crash);

            Files.move(base1.resolve("d"), base1.resolve("e"));
            syncBoth(base1, base2, state);
            Map<String, String> expected = Map.of("d", "dir", "d/f.bin", new String(data), "e", "dir", "e/old.txt", "old");
            Assert.assertEquals("crash " + crash, expected, tree(base1));
            Assert.assertEquals("crash " + crash, expected, tree(base2));
        }
    }

    @Test
    public void fileWhereAnInterruptedDownloadsFolderWas() throws Exception {
        // resuming tried to create the folder again on every sync, and failed it
        for (boolean crash : List.of(false, true)) {
            Path base1 = Files.createTempDirectory("peergos-sync"), base2 = Files.createTempDirectory("peergos-sync");
            SyncState state = new JdbcTreeState(":memory:");
            byte[] data = randomText(64 * 1024, 2);
            interruptDownload(base1, base2, state, "b/x.txt/doc.pdf", null, data, 5, crash);

            write(base1, "b", "file");
            syncBoth(base1, base2, state);
            Map<String, String> synced = tree(base1);
            Assert.assertEquals("crash " + crash, synced, tree(base2));
            Assert.assertTrue("crash " + crash + " " + synced.keySet(),
                    synced.containsValue(new String(data)) && synced.containsValue("file"));
        }
    }

    @Test
    public void syncStateResetAfterAnInterruptedDownload() throws Exception {
        // with no record of the copy, the part written was kept as a conflict copy
        for (boolean crash : List.of(false, true)) {
            Path base1 = Files.createTempDirectory("peergos-sync"), base2 = Files.createTempDirectory("peergos-sync");
            byte[] data = randomText(64 * 1024, 2);
            interruptDownload(base1, base2, new JdbcTreeState(":memory:"), "f.bin", null, data, 5, crash);

            syncBoth(base1, base2, new JdbcTreeState(":memory:"));
            Map<String, String> expected = Map.of("f.bin", new String(data));
            Assert.assertEquals("crash " + crash, expected, tree(base1));
            Assert.assertEquals("crash " + crash, expected, tree(base2));
        }
    }

    @Test
    public void editDuringADownloadIsKept() throws Exception {
        // the download replaced the file when it finished, losing the edit
        Path base1 = Files.createTempDirectory("peergos-sync"), base2 = Files.createTempDirectory("peergos-sync");
        SyncState state = new JdbcTreeState(":memory:");
        byte[] changed = randomText(64 * 1024, 2);
        AtomicInteger checks = new AtomicInteger();
        try {
            download(base1, base2, state, "f.bin", randomText(64 * 1024, 1), changed, () -> {
                if (checks.incrementAndGet() == 5) {
                    try {
                        Files.write(base1.resolve("f.bin"), "an edit".getBytes());
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }
                return false;
            });
            Assert.fail("The download replaced an edit made while it ran");
        } catch (ConcurrentChangeException expected) {}

        syncBoth(base1, base2, state);
        Map<String, String> expected = Map.of("f.bin", new String(changed), "f[conflict-0].bin", "an edit");
        Assert.assertEquals(expected, tree(base1));
        Assert.assertEquals(expected, tree(base2));
    }

    @Test
    public void interruptedDownloadOfADeletedFile() throws Exception {
        // the part written was synced back as a new file
        for (boolean crash : List.of(false, true)) {
            Path base1 = Files.createTempDirectory("peergos-sync"), base2 = Files.createTempDirectory("peergos-sync");
            SyncState state = new JdbcTreeState(":memory:");
            syncBoth(base1, base2, state);
            interruptDownload(base1, base2, state, null, randomText(64 * 1024, 2), 5, crash);

            Files.delete(base2.resolve("f.bin"));
            syncBoth(base1, base2, state);
            Assert.assertEquals("crash " + crash, Map.of(), tree(base1));
            Assert.assertEquals("crash " + crash, Map.of(), tree(base2));
        }
    }

    @Test
    public void interruptedDownloadOfAFileChangedAgain() throws Exception {
        // the part written past the end of the old version no longer matched the source, so it looked like an edit,
        // and was kept as a conflict copy
        for (boolean crash : List.of(false, true)) {
            Path base1 = Files.createTempDirectory("peergos-sync"), base2 = Files.createTempDirectory("peergos-sync");
            SyncState state = new JdbcTreeState(":memory:");
            interruptDownload(base1, base2, state, randomText(64 * 1024, 1), randomText(96 * 1024, 2), 20, crash);

            byte[] latest = randomText(1000, 3);
            Files.write(base2.resolve("f.bin"), latest);
            Files.setLastModifiedTime(base2.resolve("f.bin"), java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() / 1000 * 1000 + 10_000));
            syncBoth(base1, base2, state);
            Map<String, String> expected = Map.of("f.bin", new String(latest));
            Assert.assertEquals("crash " + crash, expected, tree(base1));
            Assert.assertEquals("crash " + crash, expected, tree(base2));
        }
    }


    @Test
    public void shrinkFile() throws Exception {
        Path base1 = Files.createTempDirectory("peergos-sync");
        Path base2 = Files.createTempDirectory("peergos-sync");

        LocalFileSystem localFs = new LocalFileSystem(base1, Main.initCrypto().hasher);
        LocalFileSystem remoteFs = new LocalFileSystem(base2, Main.initCrypto().hasher);
        SyncState syncedState = new JdbcTreeState(":memory:");

        DirectorySync.syncDir(localFs, remoteFs, true, true, null, null, syncedState, 32, 5, Files.createTempDirectory("peergos-sync"), crypto, () -> false, DirectorySync::log);

        int fileSize = 6 * 1024 * 1024;
        byte[] data = new byte[fileSize];
        new Random(42).nextBytes(data);
        String filename = "document.txt";
        Files.write(base1.resolve(filename), data, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        DirectorySync.syncDir(localFs, remoteFs, true, true, null, null, syncedState, 32, 5, Files.createTempDirectory("peergos-sync"), crypto, () -> false, DirectorySync::log);

        // User edits the file locally, making it SHORTER
        Thread.sleep(10);
        byte[] newData = new byte[2 * 1024 * 1024];
        new Random(99).nextBytes(newData);
        Files.delete(base1.resolve(filename));
        Files.write(base1.resolve(filename), newData, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

        DirectorySync.syncDir(localFs, remoteFs, true, true, null, null, syncedState, 32, 5, Files.createTempDirectory("peergos-sync"), crypto, () -> false, DirectorySync::log);

        System.out.println("SHRINK local size=" + Files.size(base1.resolve(filename)) + " remote size=" + Files.size(base2.resolve(filename)) + " expected=" + newData.length);
        Assert.assertArrayEquals(newData, Files.readAllBytes(base2.resolve(filename)));
        Assert.assertArrayEquals(newData, Files.readAllBytes(base1.resolve(filename)));
    }


    @Test
    public void modifyLargeFile() throws Exception {
        modifyLargeFile(6 * 1024 * 1024);
    }

    public void modifyLargeFile(int fileSize) throws Exception {
        Path base1 = Files.createTempDirectory("peergos-sync");
        Path base2 = Files.createTempDirectory("peergos-sync");

        LocalFileSystem localFs = new LocalFileSystem(base1, Main.initCrypto().hasher);
        LocalFileSystem remoteFs = new LocalFileSystem(base2, Main.initCrypto().hasher);
        SyncState syncedState = new JdbcTreeState(":memory:");

        boolean syncLocalDeletes = true;
        boolean syncRemoteDeletes = true;

        DirectorySync.syncDir(localFs, remoteFs, syncLocalDeletes, syncRemoteDeletes, null, null, syncedState, 32, 5, Files.createTempDirectory("peergos-sync"), crypto, () -> false, DirectorySync::log);

        // Create and sync a file
        byte[] data = new byte[fileSize];
        new Random(42).nextBytes(data);
        String filename = "document.txt";
        Files.write(base1.resolve(filename), data, StandardOpenOption.CREATE);
        DirectorySync.syncDir(localFs, remoteFs, syncLocalDeletes, syncRemoteDeletes, null, null, syncedState, 32, 5, Files.createTempDirectory("peergos-sync"), crypto, () -> false, DirectorySync::log);

        FileState synced1 = syncedState.byPath(filename);
        Assert.assertNotNull(synced1);

        // User edits the file locally (real content change)
        Thread.sleep(10);
        byte[] newData = new byte[fileSize + 1024];
        new Random(99).nextBytes(newData);
        Files.write(base1.resolve(filename), newData, StandardOpenOption.CREATE);

        // Sync the change
        DirectorySync.syncDir(localFs, remoteFs, syncLocalDeletes, syncRemoteDeletes, null, null, syncedState, 32, 5, Files.createTempDirectory("peergos-sync"), crypto, () -> false, DirectorySync::log);

        // Verify the file is synced correctly
        Assert.assertArrayEquals(newData, Files.readAllBytes(base1.resolve(filename)));
        Assert.assertArrayEquals(newData, Files.readAllBytes(base2.resolve(filename)));
    }

    /**
     * A local file system that dies once the initial sync phase has finished walking it, so a
     * pass never reaches the main phase. Stands in for the sync process being killed part way
     * through (on Android WorkManager stops a Worker after 10 minutes, and the initial phase
     * takes far longer than that on a big tree). The initial phase walks the subtree once, and
     * buildDirState walks it again, so dying on the second walk leaves exactly the state a
     * killed pass would: whatever the initial phase recorded, and no completed sync.
     */
    private static class DyingLocalFileSystem extends LocalFileSystem {
        private final AtomicInteger walks = new AtomicInteger(0);

        public DyingLocalFileSystem(Path root, peergos.shared.crypto.hash.Hasher hasher) {
            super(root, hasher);
        }

        @Override
        public Optional<PublicKeyHash> applyToSubtree(Consumer<FileProps> file, Consumer<FileProps> dir, boolean parallel) throws IOException {
            if (walks.incrementAndGet() > 1)
                throw new RuntimeException("Simulated process kill");
            return super.applyToSubtree(file, dir, parallel);
        }
    }

    /**
     * Signal's backup dir holds a shared media store (files/**, all > 1 MiB) plus one dir per
     * backup containing main (6 MiB) and two small files, metadata and files. Each cycle it
     * creates a new backup dir and deletes the oldest.
     *
     * The initial sync phase only seeds the sync state db with files over 1 MiB
     * (DirectorySync line 395). While no pass has completed, small files therefore have no
     * row. If Signal deletes a backup dir in that window, the next completing pass sees
     * synced == null && local == null for metadata and files, calls them remote additions,
     * and pulls them back down — so the backup dir is never deleted from Peergos.
     */
    @Test
    public void signalBackupRotationDuringInitialSync() throws Exception {
        Path base1 = Files.createTempDirectory("peergos-sync"); // phone
        Path base2 = Files.createTempDirectory("peergos-sync"); // peergos
        Path root = PathUtil.get("Backups", "SignalBackups");
        int big = 1024 * 1024 + 1024; // over the initial phase's 1 MiB threshold
        int small = 4096;             // under it

        // The remote is already fully populated (the pair was re-added, or its state db reset),
        // so both sides start byte identical with an empty sync state db.
        List<String> backups = List.of("signal-backup-1", "signal-backup-2", "signal-backup-3");
        List<String> bigFiles = new ArrayList<>(List.of("files/07/aaa", "files/06/bbb"));
        List<String> smallFiles = new ArrayList<>();
        for (String b : backups) {
            bigFiles.add(b + "/main");
            smallFiles.add(b + "/metadata");
            smallFiles.add(b + "/files");
        }
        int seed = 0;
        for (String rel : bigFiles)
            writeBothSides(base1, base2, root.resolve(rel), randomData(seed++, big));
        for (String rel : smallFiles)
            writeBothSides(base1, base2, root.resolve(rel), randomData(seed++, small));

        SyncState syncedState = new JdbcTreeState(":memory:");
        Path peergosDir = Files.createTempDirectory("peergos-sync");
        LocalFileSystem remoteFs = new LocalFileSystem(base2, crypto.hasher);

        // Passes keep getting killed part way through, so the initial phase seeds the sync state
        // db but no pass ever completes.
        for (int run = 0; run < 3; run++) {
            try {
                DirectorySync.syncDir(new DyingLocalFileSystem(base1, crypto.hasher), remoteFs, true, true,
                        null, null, syncedState, 32, 5, peergosDir, crypto, () -> false, m -> {});
                Assert.fail("pass should have been killed before completing");
            } catch (RuntimeException expected) {
                // killed mid pass
            }
        }
        Assert.assertFalse("no pass has completed", syncedState.hasCompletedSync());
        // whatever the initial phase chooses to seed, every file it has seen exists on both
        // sides unchanged, so a later local delete must propagate rather than be undone
        for (String rel : bigFiles)
            Assert.assertNotNull("big file seeded: " + rel, syncedState.byPath(root.resolve(rel).toString().replace('\\', '/')));

        // Signal rotates: the two oldest backup dirs are deleted locally.
        List<String> deleted = List.of("signal-backup-1", "signal-backup-2");
        for (String b : deleted)
            deleteRecursive(base1.resolve(root).resolve(b));

        // Now a pass finally runs to completion.
        LocalFileSystem localFs = new LocalFileSystem(base1, crypto.hasher);
        // downloads are dispatched to the common pool, so this is written from several threads
        List<String> ops = Collections.synchronizedList(new ArrayList<>());
        DirectorySync.syncDir(localFs, remoteFs, true, true, null, null, syncedState, 32, 5,
                peergosDir, crypto, () -> false, ops::add);

        // and a second, to show whatever state we land in is stable rather than self correcting
        List<String> secondRunOps = Collections.synchronizedList(new ArrayList<>());
        DirectorySync.syncDir(localFs, remoteFs, true, true, null, null, syncedState, 32, 5,
                peergosDir, crypto, () -> false, secondRunOps::add);

        StringBuilder diagnostic = new StringBuilder();
        for (String op : ops)
            diagnostic.append("\n  first pass: ").append(op);
        for (String op : secondRunOps)
            diagnostic.append("\n  second pass: ").append(op);
        for (String b : deleted)
            diagnostic.append("\n  local ").append(b).append(": ").append(listTree(base1.resolve(root).resolve(b)))
                    .append("\n  remote ").append(b).append(": ").append(listTree(base2.resolve(root).resolve(b)));

        for (String b : deleted) {
            Assert.assertFalse("locally deleted backup must be deleted from Peergos: " + b + diagnostic,
                    base2.resolve(root).resolve(b).toFile().exists());
            Assert.assertFalse("locally deleted backup must not be recreated locally: " + b + diagnostic,
                    base1.resolve(root).resolve(b).toFile().exists());
        }
        // the surviving backup and the shared media store are untouched
        Assert.assertTrue(base2.resolve(root).resolve("signal-backup-3").resolve("main").toFile().exists());
        Assert.assertTrue(base2.resolve(root).resolve("files/07/aaa").toFile().exists());
    }

    private static byte[] randomData(int seed, int size) {
        byte[] data = new byte[size];
        new Random(seed).nextBytes(data);
        return data;
    }

    private static void writeBothSides(Path base1, Path base2, Path relPath, byte[] data) throws IOException {
        for (Path base : List.of(base1, base2)) {
            Path target = base.resolve(relPath);
            Files.createDirectories(target.getParent());
            Files.write(target, data, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        }
        // identical mod times, as they would be after a successful sync
        Files.setLastModifiedTime(base2.resolve(relPath), Files.getLastModifiedTime(base1.resolve(relPath)));
    }

    private static void deleteRecursive(Path p) throws IOException {
        if (Files.isDirectory(p)) {
            try (Stream<Path> kids = Files.list(p)) {
                for (Path kid : kids.collect(Collectors.toList()))
                    deleteRecursive(kid);
            }
        }
        Files.delete(p);
    }

    private static List<String> listTree(Path base) throws IOException {
        if (! Files.exists(base))
            return Collections.emptyList();
        try (Stream<Path> all = Files.walk(base)) {
            return all.map(p -> base.relativize(p).toString())
                    .filter(s -> ! s.isEmpty())
                    .sorted()
                    .collect(Collectors.toList());
        }
    }

    @Test
    public void treeStateStore() throws IOException {
        Crypto crypto = Main.initCrypto();
        Path tmp = Files.createTempDirectory("peergos-sync-test");
        JdbcTreeState synced = new JdbcTreeState(tmp.resolve("syndb.sql").toString());
        Assert.assertFalse(synced.hasCompletedSync());
        synced.setCompletedSync(true);
        Assert.assertTrue(synced.hasCompletedSync());
        HashTree hash = HashTree.build(Arrays.asList(new byte[32]), Chunk.LEGACY_SIZE, crypto.hasher).join();
        String path = "some-path";
        FileState state1 = new FileState(path, 12345000, 12345, hash);
        synced.add(state1);
        FileState retrieved = synced.byPath(path);
        Assert.assertEquals(retrieved.modificationTime, state1.modificationTime);
        Assert.assertEquals(retrieved.size, state1.size);
        FileState state2 = new FileState(path, 12346000, 12346, hash);
        synced.add(state2);
        retrieved = synced.byPath(path);
        Assert.assertEquals(retrieved.modificationTime, state2.modificationTime);
        Assert.assertEquals(retrieved.size, state2.size);
        Cid c = new Cid(1, Cid.Codec.DagCbor, Multihash.Type.sha2_256, new byte[32]);
        PublicKeyHash writer = new PublicKeyHash(c);
        CommittedWriterData base = new CommittedWriterData(MaybeMultihash.of(c), Optional.empty(), Optional.of(3L));
        Snapshot original = new Snapshot(writer, base);
        synced.setSnapshot("/some/dir", original);
        synced.setSnapshot("/some/dir", original);
        Snapshot s = synced.getSnapshot("/some/dir");
        Assert.assertTrue(s.equals(original));
    }

    @Test
    public void pauseBetweenRunnerCheckAndPassIsNotLost() {
        SyncRunner.StatusHolder status = new SyncRunner.StatusHolder();

        // ThreadBased has already passed its top-of-loop pause check.
        Assert.assertFalse(status.isPaused());
        status.pause();

        // DirectorySync resets cancellation when starting a pass.
        status.resume();

        Assert.assertTrue(status.isPaused());
        Assert.assertTrue(status.isCancelled());

        status.unpause();
        Assert.assertFalse(status.isPaused());
        Assert.assertFalse(status.isCancelled());
    }

    @Test
    public void syncStatusAggregation() {
        // no pairs configured => nothing to report, rather than "all good"
        Assert.assertEquals(SyncStatus.NONE, SyncStatus.aggregate(Collections.emptyList(), false, false));
        Assert.assertEquals(SyncStatus.NONE, SyncStatus.aggregate(Collections.emptyList(), true, false));
        // and nothing to pause either
        Assert.assertEquals(SyncStatus.NONE, SyncStatus.aggregate(Collections.emptyList(), false, true));

        Assert.assertEquals(SyncStatus.SYNCED, SyncStatus.aggregate(List.of(SyncStatus.SYNCED, SyncStatus.SYNCED), false, false));
        Assert.assertEquals(SyncStatus.SYNCING, SyncStatus.aggregate(List.of(SyncStatus.SYNCED, SyncStatus.SYNCING), false, false));
        // an error anywhere wins over a sync in progress
        Assert.assertEquals(SyncStatus.ERROR, SyncStatus.aggregate(List.of(SyncStatus.SYNCING, SyncStatus.ERROR), false, false));
        // a global error with all pairs happy still reports an error
        Assert.assertEquals(SyncStatus.ERROR, SyncStatus.aggregate(List.of(SyncStatus.SYNCED), true, false));

        // a pause is what the tray shows, whatever the last pass left the pairs on
        Assert.assertEquals(SyncStatus.PAUSED, SyncStatus.aggregate(List.of(SyncStatus.SYNCED), false, true));
        Assert.assertEquals(SyncStatus.PAUSED, SyncStatus.aggregate(List.of(SyncStatus.SYNCING), false, true));
        // including one that failed, which the folder itself still reports
        Assert.assertEquals(SyncStatus.PAUSED, SyncStatus.aggregate(List.of(SyncStatus.ERROR), false, true));
        Assert.assertEquals(SyncStatus.PAUSED, SyncStatus.aggregate(List.of(SyncStatus.SYNCED), true, true));
    }

    @Test
    public void pairStatusState() throws IOException {
        Path peergosDir = Files.createTempDirectory("peergos-sync-test");
        String hash = "1234";
        PairStatus status = new PairStatus(peergosDir, hash);
        Assert.assertEquals(SyncStatus.SYNCED, status.getStatus());

        status.setStatus("Syncing /local to+from /remote");
        status.setStatus(SyncStatus.SYNCING);
        Assert.assertEquals(SyncStatus.SYNCING, status.getStatus());

        // the state survives a round trip through disk
        Assert.assertEquals(SyncStatus.SYNCING, new PairStatus(peergosDir, hash).getStatus());
        status.setStatus(SyncStatus.ERROR);
        Assert.assertEquals(SyncStatus.ERROR, new PairStatus(peergosDir, hash).getStatus());

        // a status file written by an older version has no state, and defaults to SYNCED
        Path file = PairStatus.statusPath(peergosDir, hash);
        Files.write(file, "{\"msg\":\"Dir sync took 3s\",\"error\":\"\",\"time\":\"2026-07-17T11:04:22\"}".getBytes(StandardCharsets.UTF_8));
        PairStatus old = new PairStatus(peergosDir, hash);
        Assert.assertEquals(SyncStatus.SYNCED, old.getStatus());
        Assert.assertEquals("Dir sync took 3s", old.getMessage());
    }

    @Test
    public void progressIsReportedOncePerMiB() throws Exception {
        // The local filesystem reads in 4 KiB, and each read used to report progress: a message
        // every 4 KiB says the same thing 255 times out of 256, and every one of them is logged
        // and written to the pair's status file.
        int fileSize = 5 * 1024 * 1024;
        byte[] data = new byte[fileSize];
        new Random(42).nextBytes(data);
        Path base = Files.createTempDirectory("peergos-sync");
        LocalFileSystem fs = new LocalFileSystem(base, Main.initCrypto().hasher);

        List<String> reported = new ArrayList<>();
        fs.setBytes(PathUtil.get("big.bin"), 0, AsyncReader.build(data), fileSize, Optional.empty(),
                Optional.of(LocalDateTime.now()), Optional.empty(), ResumeUploadProps.random(crypto), () -> false, reported::add);

        Assert.assertEquals(fileSize, Files.size(base.resolve("big.bin")));
        Assert.assertEquals("one message per MiB", 5, reported.size());
        Assert.assertEquals("no message repeats the previous one",
                reported.size(), new LinkedHashSet<>(reported).size());
        Assert.assertTrue(reported.get(0), reported.get(0).startsWith("Downloaded 1 / 5 MiB of "));
        Assert.assertTrue(reported.get(4), reported.get(4).startsWith("Downloaded 5 / 5 MiB of "));
    }

    @Test
    public void redundantStatusWritesAreSkipped() throws Exception {
        // A pass clears the error before each folder and sets the state twice, so most of those
        // calls say what the file already says. Each one used to rewrite it.
        Path dir = Files.createTempDirectory("peergos-sync");
        Files.createDirectories(PairLogger.logDir(dir));
        PairStatus status = new PairStatus(dir, "testpair");
        Path file = PairStatus.statusPath(dir, "testpair");

        status.setStatus("Checking files on this device");
        status.setStatus(SyncStatus.SYNCING);
        Assert.assertTrue(Files.exists(file));

        // a write recreates the file, so whether it comes back is the check: a redundant one
        // would write the same bytes, and file times and keys are not portable enough to tell
        Files.delete(file);
        status.setError(null);
        status.setStatus(SyncStatus.SYNCING);
        Assert.assertFalse("nothing changed, so nothing was written", Files.exists(file));

        status.setStatus(SyncStatus.SYNCED);
        Assert.assertTrue("a real change still reaches the file", Files.exists(file));

        // the message carries the clock the view shows next to it, so repeating it is still
        // worth a write even though the text has not changed
        Files.delete(file);
        status.setStatus("Dir sync took 2s");
        Files.delete(file);
        status.setStatus("Dir sync took 2s");
        Assert.assertTrue("the same message still restamps the time", Files.exists(file));
    }
}
