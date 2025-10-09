package peergos.server.tests;

import org.junit.Assert;
import org.junit.Test;
import peergos.server.JavaCrypto;
import peergos.server.Main;
import peergos.server.sync.*;
import peergos.shared.Crypto;
import peergos.shared.MaybeMultihash;
import peergos.shared.crypto.hash.PublicKeyHash;
import peergos.shared.io.ipfs.Cid;
import peergos.shared.io.ipfs.Multihash;
import peergos.shared.user.CommittedWriterData;
import peergos.shared.user.Snapshot;
import peergos.shared.user.fs.HashTree;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.*;

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

        DirectorySync.syncDir(localFs, remoteFs, syncLocalDeletes, syncRemoteDeletes, null, null, syncedState, 32, 5, crypto, () -> false, DirectorySync::log);

        byte[] data = new byte[filesize];
        new Random(42).nextBytes(data);
        Files.write(base1.resolve(originalFilename), data, StandardOpenOption.CREATE);

        DirectorySync.syncDir(localFs, remoteFs, syncLocalDeletes, syncRemoteDeletes, null, null, syncedState, 32, 5, crypto, () -> false, DirectorySync::log);
        Assert.assertNotNull(syncedState.byPath(originalFilename));

        // rename file
        Files.move(base1.resolve(originalFilename), base1.resolve(newFilename));
        DirectorySync.syncDir(localFs, remoteFs, syncLocalDeletes, syncRemoteDeletes, null, null, syncedState, 32, 5, crypto, () -> false, DirectorySync::log);
        Assert.assertNull(syncedState.byPath(originalFilename));
        Assert.assertNotNull(syncedState.byPath(newFilename));

        // sync should be stable
        DirectorySync.syncDir(localFs, remoteFs, syncLocalDeletes, syncRemoteDeletes, null, null, syncedState, 32, 5, crypto, () -> false, DirectorySync::log);
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

        DirectorySync.syncDir(localFs, remoteFs, syncLocalDeletes, syncRemoteDeletes, null, null, syncedState, 32, 5, crypto, () -> false, DirectorySync::log);

        byte[] data = new byte[filesize];
        new Random(42).nextBytes(data);
        for (int i=0; i < nCopies; i++)
            Files.write(base1.resolve(i + "_" + originalFilename), data, StandardOpenOption.CREATE);

        DirectorySync.syncDir(localFs, remoteFs, syncLocalDeletes, syncRemoteDeletes, null, null, syncedState, 32, 5, crypto, () -> false, DirectorySync::log);
        for (int i=0; i < nCopies; i++)
            Assert.assertNotNull(syncedState.byPath(i + "_" + originalFilename));
        Assert.assertEquals(syncedState.allFilePaths().size(), nCopies);

        // rename file
        for (int i=0; i < nRenames; i++)
            Files.move(base1.resolve(i + "_" + originalFilename), base1.resolve(i + "_" + newFilename));
        List<String> ops = new ArrayList<>();
        DirectorySync.syncDir(localFs, remoteFs, syncLocalDeletes, syncRemoteDeletes, null, null, syncedState, 32, 5, crypto, () -> false, ops::add);
        for (int i=0; i < nRenames; i++) {
            Assert.assertNull(syncedState.byPath(i + "_" + originalFilename));
            Assert.assertNotNull(syncedState.byPath(i + "_" + newFilename));
        }
        Assert.assertTrue(ops.stream().noneMatch(op -> op.contains("upload")));
        Assert.assertTrue(ops.stream().anyMatch(op -> op.contains("Moving")));

        // sync should be stable
        DirectorySync.syncDir(localFs, remoteFs, syncLocalDeletes, syncRemoteDeletes, null, null, syncedState, 32, 5, crypto, () -> false, DirectorySync::log);
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
        DirectorySync.syncDir(localFs, remoteFs, syncLocalDeletes, syncRemoteDeletes, null, null, syncedState, 32, 5, crypto, () -> false, DirectorySync::log);

        byte[] data = new byte[6 * 1024 * 1024];
        new Random(42).nextBytes(data);
        String filename = "file.bin";
        Files.write(base1.resolve(filename), data, StandardOpenOption.CREATE);

        DirectorySync.syncDir(localFs, remoteFs, syncLocalDeletes, syncRemoteDeletes, null, null, syncedState, 32, 5, crypto, () -> false, DirectorySync::log);
        Assert.assertNotNull(syncedState.byPath(filename));

        // rename file
        String filename2 = "newfile.bin";
        Files.move(base1.resolve(filename), base1.resolve(filename2));
        DirectorySync.syncDir(localFs, remoteFs, syncLocalDeletes, syncRemoteDeletes, null, null, syncedState, 32, 5, crypto, () -> false, DirectorySync::log);
        Assert.assertNull(syncedState.byPath(filename));
        Assert.assertNotNull(syncedState.byPath(filename2));

        // sync should be stable
        DirectorySync.syncDir(localFs, remoteFs, syncLocalDeletes, syncRemoteDeletes, null, null, syncedState, 32, 5, crypto, () -> false, DirectorySync::log);
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

        DirectorySync.syncDir(localFs, remoteFs, true, true, null, null, syncedState, 32, 5, crypto, () -> false, DirectorySync::log);

        byte[] data = new byte[6 * 1024 * 1024];
        new Random(42).nextBytes(data);
        String filename = "file.bin";
        Files.write(base1.resolve(filename), data, StandardOpenOption.CREATE);

        DirectorySync.syncDir(localFs, remoteFs, true, true, null, null, syncedState, 32, 5, crypto, () -> false, DirectorySync::log);
        Assert.assertNotNull(syncedState.byPath(filename));

        // move file to a subdir
        Path subdir = base1.resolve("subdir");
        subdir.toFile().mkdirs();
        Files.move(base1.resolve(filename), subdir.resolve(filename));
        DirectorySync.syncDir(localFs, remoteFs, true, true, null, null, syncedState, 32, 5, crypto, () -> false, DirectorySync::log);
        Assert.assertNull(syncedState.byPath(filename));
        String fileRelPath = subdir.getFileName().resolve(filename).toString().replaceAll("\\\\", "/");
        Assert.assertNotNull(syncedState.byPath(fileRelPath));

        // sync should be stable
        DirectorySync.syncDir(localFs, remoteFs, true, true, null, null, syncedState, 32, 5, crypto, () -> false, DirectorySync::log);
        Assert.assertNull(syncedState.byPath(filename));
        Assert.assertNotNull(syncedState.byPath(fileRelPath));

        // move the file back
        Files.move(subdir.resolve(filename), base1.resolve(filename));
        DirectorySync.syncDir(localFs, remoteFs, true, true, null, null, syncedState, 32, 5, crypto, () -> false, DirectorySync::log);
        Assert.assertNotNull(syncedState.byPath(filename));
        Assert.assertNull(syncedState.byPath(fileRelPath));

        // check stability
        DirectorySync.syncDir(localFs, remoteFs, true, true, null, null, syncedState, 32, 5, crypto, () -> false, DirectorySync::log);
        Assert.assertNotNull(syncedState.byPath(filename));
        Assert.assertNull(syncedState.byPath(fileRelPath));

        Assert.assertTrue(syncedState.getInProgressCopies().isEmpty());
    }

    @Test
    public void androidModTime() throws Exception {
        Path base1 = Files.createTempDirectory("peergos-sync");
        Path base2 = Files.createTempDirectory("peergos-sync");

        LocalFileSystem localFs = new LocalFileSystem(base1, Main.initCrypto().hasher);
        LocalFileSystem remoteFs = new LocalFileSystem(base2, Main.initCrypto().hasher);
        SyncState syncedState = new JdbcTreeState(":memory:");

        DirectorySync.syncDir(localFs, remoteFs, true, true, null, null, syncedState, 32, 5, crypto, () -> false, DirectorySync::log);

        byte[] data = new byte[6 * 1024];
        new Random(42).nextBytes(data);
        String filename = "file.bin";
        Files.write(base2.resolve(filename), data, StandardOpenOption.CREATE);

        DirectorySync.syncDir(localFs, remoteFs, true, true, null, null, syncedState, 32, 5, crypto, () -> false, DirectorySync::log);
        Assert.assertNotNull(syncedState.byPath(filename));

        // simulate Android (base1) not being able to set mod time, and a modification on original source (base2)
        boolean modTimeSet = base1.resolve(filename).toFile().setLastModified(System.currentTimeMillis() + 10_000);
        Files.write(base2.resolve(filename), "add to end".getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);

        // check stability
        List<String> ops = new ArrayList<>();
        DirectorySync.syncDir(localFs, remoteFs, true, true, null, null, syncedState, 32, 5, crypto, () -> false, ops::add);
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
        DirectorySync.syncDir(localFs, remoteFs, syncLocalDeletes, syncRemoteDeletes, null, null, syncedState, 32, 5, crypto, () -> false, DirectorySync::log);

        byte[] data = new byte[fileSize];
        new Random(42).nextBytes(data);
        String filename = "file.bin";
        Files.write(base1.resolve(filename), data, StandardOpenOption.CREATE);

        DirectorySync.syncDir(localFs, remoteFs, syncLocalDeletes, syncRemoteDeletes, null, null, syncedState, 32, 5, crypto, () -> false, DirectorySync::log);
        Assert.assertNotNull(syncedState.byPath(filename));

        // delete local file and check remote is not deleted
        Files.delete(base1.resolve(filename));
        DirectorySync.syncDir(localFs, remoteFs, syncLocalDeletes, syncRemoteDeletes, null, null, syncedState, 32, 5, crypto, () -> false, DirectorySync::log);
        Assert.assertNotNull(syncedState.byPath(filename));
        Assert.assertTrue(base2.resolve(filename).toFile().exists());
        Assert.assertFalse(base1.resolve(filename).toFile().exists());
        Assert.assertTrue(syncedState.hasLocalDelete(filename));
        DirectorySync.syncDir(localFs, remoteFs, syncLocalDeletes, syncRemoteDeletes, null, null, syncedState, 32, 5, crypto, () -> false, DirectorySync::log);
        Assert.assertNotNull(syncedState.byPath(filename));
        Assert.assertTrue(syncedState.hasLocalDelete(filename));
        Assert.assertTrue(base2.resolve(filename).toFile().exists());
        Assert.assertFalse(base1.resolve(filename).toFile().exists());

        // add a different local file with the same name (it should be renamed, and then synced)
        byte[] data2 = new byte[fileSize + 1024 * 1024];
        new Random(28).nextBytes(data2);
        Files.write(base1.resolve(filename), data2, StandardOpenOption.CREATE);
        DirectorySync.syncDir(localFs, remoteFs, syncLocalDeletes, syncRemoteDeletes, null, null, syncedState, 32, 5, crypto, () -> false, DirectorySync::log);
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
        DirectorySync.syncDir(localFs, remoteFs, syncLocalDeletes, syncRemoteDeletes, null, null, syncedState, 32, 5, crypto, () -> false, DirectorySync::log);

        byte[] data = new byte[fileSize];
        new Random(42).nextBytes(data);
        String filename = "file.bin";
        Files.write(base1.resolve(filename), data, StandardOpenOption.CREATE);

        DirectorySync.syncDir(localFs, remoteFs, syncLocalDeletes, syncRemoteDeletes, null, null, syncedState, 32, 5, crypto, () -> false, DirectorySync::log);
        Assert.assertNotNull(syncedState.byPath(filename));

        // delete local file and check remote is not deleted
        Files.delete(base1.resolve(filename));
        DirectorySync.syncDir(localFs, remoteFs, syncLocalDeletes, syncRemoteDeletes, null, null, syncedState, 32, 5, crypto, () -> false, DirectorySync::log);
        Assert.assertNotNull(syncedState.byPath(filename));
        Assert.assertTrue(base2.resolve(filename).toFile().exists());
        Assert.assertTrue(syncedState.hasLocalDelete(filename));
        DirectorySync.syncDir(localFs, remoteFs, syncLocalDeletes, syncRemoteDeletes, null, null, syncedState, 32, 5, crypto, () -> false, DirectorySync::log);
        Assert.assertNotNull(syncedState.byPath(filename));
        Assert.assertTrue(syncedState.hasLocalDelete(filename));
        Assert.assertTrue(base2.resolve(filename).toFile().exists());

        // restore the local file (it should be removed from the delete list)
        Files.write(base1.resolve(filename), data, StandardOpenOption.CREATE);
        DirectorySync.syncDir(localFs, remoteFs, syncLocalDeletes, syncRemoteDeletes, null, null, syncedState, 32, 5, crypto, () -> false, DirectorySync::log);
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
        DirectorySync.syncDir(localFs, remoteFs, syncLocalDeletes, syncRemoteDeletes, null, null, syncedState, 32, 5, crypto, () -> false, DirectorySync::log);

        byte[] data = new byte[fileSize];
        new Random(42).nextBytes(data);
        String filename = "file.bin";
        Files.write(base1.resolve(filename), data, StandardOpenOption.CREATE);

        DirectorySync.syncDir(localFs, remoteFs, syncLocalDeletes, syncRemoteDeletes, null, null, syncedState, 32, 5, crypto, () -> false, DirectorySync::log);
        Assert.assertNotNull(syncedState.byPath(filename));

        // delete local file and check remote is not deleted
        Files.delete(base1.resolve(filename));
        DirectorySync.syncDir(localFs, remoteFs, syncLocalDeletes, syncRemoteDeletes, null, null, syncedState, 32, 5, crypto, () -> false, DirectorySync::log);
        Assert.assertNotNull(syncedState.byPath(filename));
        Assert.assertTrue(base2.resolve(filename).toFile().exists());
        Assert.assertTrue(syncedState.hasLocalDelete(filename));
        DirectorySync.syncDir(localFs, remoteFs, syncLocalDeletes, syncRemoteDeletes, null, null, syncedState, 32, 5, crypto, () -> false, DirectorySync::log);
        Assert.assertNotNull(syncedState.byPath(filename));
        Assert.assertTrue(syncedState.hasLocalDelete(filename));
        Assert.assertTrue(base2.resolve(filename).toFile().exists());

        // modify the remote file (it should be copied to local)
        byte[] data2 = new byte[fileSize + 1024 * 1024];
        new Random(28).nextBytes(data2);
        Files.delete(base2.resolve(filename));
        Files.write(base2.resolve(filename), data2, StandardOpenOption.CREATE);
        DirectorySync.syncDir(localFs, remoteFs, syncLocalDeletes, syncRemoteDeletes, null, null, syncedState, 32, 5, crypto, () -> false, DirectorySync::log);
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
        DirectorySync.syncDir(localFs, remoteFs, syncLocalDeletes, syncRemoteDeletes, null, null, syncedState, 32, 5, crypto, () -> false, DirectorySync::log);

        byte[] data = new byte[fileSize];
        new Random(42).nextBytes(data);
        String filename = "file.bin";
        Files.write(base1.resolve(filename), data, StandardOpenOption.CREATE);

        DirectorySync.syncDir(localFs, remoteFs, syncLocalDeletes, syncRemoteDeletes, null, null, syncedState, 32, 5, crypto, () -> false, DirectorySync::log);
        Assert.assertNotNull(syncedState.byPath(filename));

        // delete remote file and check local is not deleted
        Files.delete(base2.resolve(filename));
        DirectorySync.syncDir(localFs, remoteFs, syncLocalDeletes, syncRemoteDeletes, null, null, syncedState, 32, 5, crypto, () -> false, DirectorySync::log);
        Assert.assertNotNull(syncedState.byPath(filename));
        Assert.assertTrue(base1.resolve(filename).toFile().exists());
        Assert.assertTrue(syncedState.hasRemoteDelete(filename));
        DirectorySync.syncDir(localFs, remoteFs, syncLocalDeletes, syncRemoteDeletes, null, null, syncedState, 32, 5, crypto, () -> false, DirectorySync::log);
        Assert.assertNotNull(syncedState.byPath(filename));
        Assert.assertTrue(syncedState.hasRemoteDelete(filename));
        Assert.assertTrue(base1.resolve(filename).toFile().exists());

        // add a different remote file with the same name (local should be renamed, and then new remote synced)
        byte[] data2 = new byte[fileSize + 1024 * 1024];
        new Random(28).nextBytes(data2);
        Files.write(base2.resolve(filename), data2, StandardOpenOption.CREATE);
        DirectorySync.syncDir(localFs, remoteFs, syncLocalDeletes, syncRemoteDeletes, null, null, syncedState, 32, 5, crypto, () -> false, DirectorySync::log);
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
        DirectorySync.syncDir(localFs, remoteFs, syncLocalDeletes, syncRemoteDeletes, null, null, syncedState, 32, 5, crypto, () -> false, DirectorySync::log);

        byte[] data = new byte[fileSize];
        new Random(42).nextBytes(data);
        String filename = "file.bin";
        Files.write(base1.resolve(filename), data, StandardOpenOption.CREATE);

        DirectorySync.syncDir(localFs, remoteFs, syncLocalDeletes, syncRemoteDeletes, null, null, syncedState, 32, 5, crypto, () -> false, DirectorySync::log);
        Assert.assertNotNull(syncedState.byPath(filename));

        // delete remote file and check local is not deleted
        Files.delete(base2.resolve(filename));
        DirectorySync.syncDir(localFs, remoteFs, syncLocalDeletes, syncRemoteDeletes, null, null, syncedState, 32, 5, crypto, () -> false, DirectorySync::log);
        Assert.assertNotNull(syncedState.byPath(filename));
        Assert.assertTrue(base1.resolve(filename).toFile().exists());
        Assert.assertTrue(syncedState.hasRemoteDelete(filename));
        DirectorySync.syncDir(localFs, remoteFs, syncLocalDeletes, syncRemoteDeletes, null, null, syncedState, 32, 5, crypto, () -> false, DirectorySync::log);
        Assert.assertNotNull(syncedState.byPath(filename));
        Assert.assertTrue(syncedState.hasRemoteDelete(filename));
        Assert.assertTrue(base1.resolve(filename).toFile().exists());

        // restore the remote file (it should be removed from the delete list)
        Files.write(base2.resolve(filename), data, StandardOpenOption.CREATE);
        DirectorySync.syncDir(localFs, remoteFs, syncLocalDeletes, syncRemoteDeletes, null, null, syncedState, 32, 5, crypto, () -> false, DirectorySync::log);
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
        DirectorySync.syncDir(localFs, remoteFs, syncLocalDeletes, syncRemoteDeletes, null, null, syncedState, 32, 5, crypto, () -> false, DirectorySync::log);

        byte[] data = new byte[fileSize];
        new Random(42).nextBytes(data);
        String filename = "file.bin";
        Files.write(base1.resolve(filename), data, StandardOpenOption.CREATE);

        DirectorySync.syncDir(localFs, remoteFs, syncLocalDeletes, syncRemoteDeletes, null, null, syncedState, 32, 5, crypto, () -> false, DirectorySync::log);
        Assert.assertNotNull(syncedState.byPath(filename));

        // delete remote file and check local is not deleted
        Files.delete(base2.resolve(filename));
        DirectorySync.syncDir(localFs, remoteFs, syncLocalDeletes, syncRemoteDeletes, null, null, syncedState, 32, 5, crypto, () -> false, DirectorySync::log);
        Assert.assertNotNull(syncedState.byPath(filename));
        Assert.assertTrue(base1.resolve(filename).toFile().exists());
        Assert.assertTrue(syncedState.hasRemoteDelete(filename));
        DirectorySync.syncDir(localFs, remoteFs, syncLocalDeletes, syncRemoteDeletes, null, null, syncedState, 32, 5, crypto, () -> false, DirectorySync::log);
        Assert.assertNotNull(syncedState.byPath(filename));
        Assert.assertTrue(syncedState.hasRemoteDelete(filename));
        Assert.assertTrue(base1.resolve(filename).toFile().exists());

        // modify the local file (it should be copied to remote)
        byte[] data2 = new byte[fileSize + 1024 * 1024];
        new Random(28).nextBytes(data2);
        Files.delete(base1.resolve(filename));
        Files.write(base1.resolve(filename), data2, StandardOpenOption.CREATE);
        DirectorySync.syncDir(localFs, remoteFs, syncLocalDeletes, syncRemoteDeletes, null, null, syncedState, 32, 5, crypto, () -> false, DirectorySync::log);
        Assert.assertArrayEquals(Files.readAllBytes(base2.resolve(filename)), data2);
        Assert.assertArrayEquals(Files.readAllBytes(base1.resolve(filename)), data2);
        Assert.assertFalse(syncedState.hasRemoteDelete(filename));
        Assert.assertEquals(1, syncedState.allFilePaths().size());
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
}
