package peergos.server.tests;

import org.junit.Assert;
import org.junit.Test;
import peergos.server.JavaCrypto;
import peergos.server.Main;
import peergos.server.sync.*;
import peergos.server.sync.SyncFilesystem.FileProps;
import peergos.shared.Crypto;
import peergos.shared.MaybeMultihash;
import peergos.shared.crypto.hash.PublicKeyHash;
import peergos.shared.io.ipfs.Cid;
import peergos.shared.io.ipfs.Multihash;
import peergos.shared.user.CommittedWriterData;
import peergos.shared.user.Snapshot;
import peergos.shared.user.fs.Chunk;
import peergos.shared.user.fs.ChunkHashList;
import peergos.shared.user.fs.HashTree;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class SyncTests {

    private static Crypto crypto = JavaCrypto.init();

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

        FileState grownFs = new FileState("big.bin", 1000, 1025L * Chunk.MAX_SIZE, grown);
        FileState oldFs = new FileState("big.bin", 1000, 1024L * Chunk.MAX_SIZE, old);

        List<Pair<Long, Long>> diff = grownFs.diffRanges(oldFs);
        Assert.assertEquals(1, diff.size());
        Assert.assertEquals(1024L * Chunk.MAX_SIZE, (long) diff.get(0).left);
        Assert.assertEquals(1025L * Chunk.MAX_SIZE, (long) diff.get(0).right);
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
        HashTree hash = srcFs.hashFile(Paths.get("f.bin"), Optional.empty(), "f.bin", syncDb, data.length);
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
        HashTree hash = HashTree.build(Arrays.asList(new byte[32]), crypto.hasher).join();
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
}
