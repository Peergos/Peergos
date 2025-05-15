package peergos.server.tests;

import org.junit.Assert;
import org.junit.Test;
import peergos.server.Main;
import peergos.server.sync.*;
import peergos.shared.Crypto;
import peergos.shared.user.fs.HashTree;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Random;
import java.util.Set;

public class SyncTests {

    @Test
    public void moves() throws Exception {
        Path tmp = Files.createTempDirectory("peergos-sync");
        Path base1 = Files.createTempDirectory("peergos-sync");
        Path base2 = Files.createTempDirectory("peergos-sync");

        LocalFileSystem localFs = new LocalFileSystem(Main.initCrypto().hasher);
        SyncState syncedState = new JdbcTreeState(":memory:");

        DirectorySync.syncDirs(localFs, base1, localFs, base2, true, true, null, null, syncedState, tmp, 32, 5, DirectorySync::log);

        byte[] data = new byte[6 * 1024 * 1024];
        new Random(42).nextBytes(data);
        String filename = "file.bin";
        Files.write(base1.resolve(filename), data, StandardOpenOption.CREATE);

        DirectorySync.syncDirs(localFs, base1, localFs, base2, true, true, null, null, syncedState, tmp, 32, 5, DirectorySync::log);
        Assert.assertNotNull(syncedState.byPath(filename));

        // move file to a subdir
        Path subdir = base1.resolve("subdir");
        subdir.toFile().mkdirs();
        Files.move(base1.resolve(filename), subdir.resolve(filename));
        DirectorySync.syncDirs(localFs, base1, localFs, base2, true, true, null, null, syncedState, tmp, 32, 5, DirectorySync::log);
        Assert.assertNull(syncedState.byPath(filename));
        String fileRelPath = subdir.getFileName().resolve(filename).toString().replaceAll("\\\\", "/");
        Assert.assertNotNull(syncedState.byPath(fileRelPath));

        // sync should be stable
        DirectorySync.syncDirs(localFs, base1, localFs, base2, true, true, null, null, syncedState, tmp, 32, 5, DirectorySync::log);
        Assert.assertNull(syncedState.byPath(filename));
        Assert.assertNotNull(syncedState.byPath(fileRelPath));

        // move the file back
        Files.move(subdir.resolve(filename), base1.resolve(filename));
        DirectorySync.syncDirs(localFs, base1, localFs, base2, true, true, null, null, syncedState, tmp, 32, 5, DirectorySync::log);
        Assert.assertNotNull(syncedState.byPath(filename));
        Assert.assertNull(syncedState.byPath(fileRelPath));

        // check stability
        DirectorySync.syncDirs(localFs, base1, localFs, base2, true, true, null, null, syncedState, tmp, 32, 5, DirectorySync::log);
        Assert.assertNotNull(syncedState.byPath(filename));
        Assert.assertNull(syncedState.byPath(fileRelPath));

        Assert.assertTrue(syncedState.getInProgressCopies().isEmpty());
    }

    @Test
    public void ignoreLocalDeleteBeforeConflict() throws Exception {
        ignoreLocalDeleteBeforeConflict(6 * 1024 * 1024);
        ignoreLocalDeleteBeforeConflict(1024);
    }

    public void ignoreLocalDeleteBeforeConflict(int fileSize) throws Exception {
        Path tmp = Files.createTempDirectory("peergos-sync");
        Path base1 = Files.createTempDirectory("peergos-sync");
        Path base2 = Files.createTempDirectory("peergos-sync");

        LocalFileSystem localFs = new LocalFileSystem(Main.initCrypto().hasher);
        SyncState syncedState = new JdbcTreeState(":memory:");

        boolean syncLocalDeletes = false;
        boolean syncRemoteDeletes = true;
        DirectorySync.syncDirs(localFs, base1, localFs, base2, syncLocalDeletes, syncRemoteDeletes, null, null, syncedState, tmp, 32, 5, DirectorySync::log);

        byte[] data = new byte[fileSize];
        new Random(42).nextBytes(data);
        String filename = "file.bin";
        Files.write(base1.resolve(filename), data, StandardOpenOption.CREATE);

        DirectorySync.syncDirs(localFs, base1, localFs, base2, syncLocalDeletes, syncRemoteDeletes, null, null, syncedState, tmp, 32, 5, DirectorySync::log);
        Assert.assertNotNull(syncedState.byPath(filename));

        // delete local file and check remote is not deleted
        base1.resolve(filename).toFile().delete();
        DirectorySync.syncDirs(localFs, base1, localFs, base2, syncLocalDeletes, syncRemoteDeletes, null, null, syncedState, tmp, 32, 5, DirectorySync::log);
        Assert.assertNotNull(syncedState.byPath(filename));
        Assert.assertTrue(base2.resolve(filename).toFile().exists());
        Assert.assertFalse(base1.resolve(filename).toFile().exists());
        Assert.assertTrue(syncedState.hasLocalDelete(filename));
        DirectorySync.syncDirs(localFs, base1, localFs, base2, syncLocalDeletes, syncRemoteDeletes, null, null, syncedState, tmp, 32, 5, DirectorySync::log);
        Assert.assertNotNull(syncedState.byPath(filename));
        Assert.assertTrue(syncedState.hasLocalDelete(filename));
        Assert.assertTrue(base2.resolve(filename).toFile().exists());
        Assert.assertFalse(base1.resolve(filename).toFile().exists());

        // add a different local file with the same name (it should be renamed, and then synced)
        byte[] data2 = new byte[fileSize + 1024 * 1024];
        new Random(28).nextBytes(data2);
        Files.write(base1.resolve(filename), data2, StandardOpenOption.CREATE);
        DirectorySync.syncDirs(localFs, base1, localFs, base2, syncLocalDeletes, syncRemoteDeletes, null, null, syncedState, tmp, 32, 5, DirectorySync::log);
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
        Path tmp = Files.createTempDirectory("peergos-sync");
        Path base1 = Files.createTempDirectory("peergos-sync");
        Path base2 = Files.createTempDirectory("peergos-sync");

        LocalFileSystem localFs = new LocalFileSystem(Main.initCrypto().hasher);
        SyncState syncedState = new JdbcTreeState(":memory:");

        boolean syncLocalDeletes = false;
        boolean syncRemoteDeletes = true;
        DirectorySync.syncDirs(localFs, base1, localFs, base2, syncLocalDeletes, syncRemoteDeletes, null, null, syncedState, tmp, 32, 5, DirectorySync::log);

        byte[] data = new byte[fileSize];
        new Random(42).nextBytes(data);
        String filename = "file.bin";
        Files.write(base1.resolve(filename), data, StandardOpenOption.CREATE);

        DirectorySync.syncDirs(localFs, base1, localFs, base2, syncLocalDeletes, syncRemoteDeletes, null, null, syncedState, tmp, 32, 5, DirectorySync::log);
        Assert.assertNotNull(syncedState.byPath(filename));

        // delete local file and check remote is not deleted
        base1.resolve(filename).toFile().delete();
        DirectorySync.syncDirs(localFs, base1, localFs, base2, syncLocalDeletes, syncRemoteDeletes, null, null, syncedState, tmp, 32, 5, DirectorySync::log);
        Assert.assertNotNull(syncedState.byPath(filename));
        Assert.assertTrue(base2.resolve(filename).toFile().exists());
        Assert.assertTrue(syncedState.hasLocalDelete(filename));
        DirectorySync.syncDirs(localFs, base1, localFs, base2, syncLocalDeletes, syncRemoteDeletes, null, null, syncedState, tmp, 32, 5, DirectorySync::log);
        Assert.assertNotNull(syncedState.byPath(filename));
        Assert.assertTrue(syncedState.hasLocalDelete(filename));
        Assert.assertTrue(base2.resolve(filename).toFile().exists());

        // restore the local file (it should be removed from the delete list)
        Files.write(base1.resolve(filename), data, StandardOpenOption.CREATE);
        DirectorySync.syncDirs(localFs, base1, localFs, base2, syncLocalDeletes, syncRemoteDeletes, null, null, syncedState, tmp, 32, 5, DirectorySync::log);
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
        Path tmp = Files.createTempDirectory("peergos-sync");
        Path base1 = Files.createTempDirectory("peergos-sync");
        Path base2 = Files.createTempDirectory("peergos-sync");

        LocalFileSystem localFs = new LocalFileSystem(Main.initCrypto().hasher);
        SyncState syncedState = new JdbcTreeState(":memory:");

        boolean syncLocalDeletes = false;
        boolean syncRemoteDeletes = true;
        DirectorySync.syncDirs(localFs, base1, localFs, base2, syncLocalDeletes, syncRemoteDeletes, null, null, syncedState, tmp, 32, 5, DirectorySync::log);

        byte[] data = new byte[fileSize];
        new Random(42).nextBytes(data);
        String filename = "file.bin";
        Files.write(base1.resolve(filename), data, StandardOpenOption.CREATE);

        DirectorySync.syncDirs(localFs, base1, localFs, base2, syncLocalDeletes, syncRemoteDeletes, null, null, syncedState, tmp, 32, 5, DirectorySync::log);
        Assert.assertNotNull(syncedState.byPath(filename));

        // delete local file and check remote is not deleted
        base1.resolve(filename).toFile().delete();
        DirectorySync.syncDirs(localFs, base1, localFs, base2, syncLocalDeletes, syncRemoteDeletes, null, null, syncedState, tmp, 32, 5, DirectorySync::log);
        Assert.assertNotNull(syncedState.byPath(filename));
        Assert.assertTrue(base2.resolve(filename).toFile().exists());
        Assert.assertTrue(syncedState.hasLocalDelete(filename));
        DirectorySync.syncDirs(localFs, base1, localFs, base2, syncLocalDeletes, syncRemoteDeletes, null, null, syncedState, tmp, 32, 5, DirectorySync::log);
        Assert.assertNotNull(syncedState.byPath(filename));
        Assert.assertTrue(syncedState.hasLocalDelete(filename));
        Assert.assertTrue(base2.resolve(filename).toFile().exists());

        // modify the remote file (it should be copied to local)
        byte[] data2 = new byte[fileSize + 1024 * 1024];
        new Random(28).nextBytes(data2);
        base2.resolve(filename).toFile().delete();
        Files.write(base2.resolve(filename), data2, StandardOpenOption.CREATE);
        DirectorySync.syncDirs(localFs, base1, localFs, base2, syncLocalDeletes, syncRemoteDeletes, null, null, syncedState, tmp, 32, 5, DirectorySync::log);
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
        Path tmp = Files.createTempDirectory("peergos-sync");
        Path base1 = Files.createTempDirectory("peergos-sync");
        Path base2 = Files.createTempDirectory("peergos-sync");

        LocalFileSystem localFs = new LocalFileSystem(Main.initCrypto().hasher);
        SyncState syncedState = new JdbcTreeState(":memory:");

        boolean syncLocalDeletes = true;
        boolean syncRemoteDeletes = false;
        DirectorySync.syncDirs(localFs, base1, localFs, base2, syncLocalDeletes, syncRemoteDeletes, null, null, syncedState, tmp, 32, 5, DirectorySync::log);

        byte[] data = new byte[fileSize];
        new Random(42).nextBytes(data);
        String filename = "file.bin";
        Files.write(base1.resolve(filename), data, StandardOpenOption.CREATE);

        DirectorySync.syncDirs(localFs, base1, localFs, base2, syncLocalDeletes, syncRemoteDeletes, null, null, syncedState, tmp, 32, 5, DirectorySync::log);
        Assert.assertNotNull(syncedState.byPath(filename));

        // delete remote file and check local is not deleted
        base2.resolve(filename).toFile().delete();
        DirectorySync.syncDirs(localFs, base1, localFs, base2, syncLocalDeletes, syncRemoteDeletes, null, null, syncedState, tmp, 32, 5, DirectorySync::log);
        Assert.assertNotNull(syncedState.byPath(filename));
        Assert.assertTrue(base1.resolve(filename).toFile().exists());
        Assert.assertTrue(syncedState.hasRemoteDelete(filename));
        DirectorySync.syncDirs(localFs, base1, localFs, base2, syncLocalDeletes, syncRemoteDeletes, null, null, syncedState, tmp, 32, 5, DirectorySync::log);
        Assert.assertNotNull(syncedState.byPath(filename));
        Assert.assertTrue(syncedState.hasRemoteDelete(filename));
        Assert.assertTrue(base1.resolve(filename).toFile().exists());

        // add a different remote file with the same name (local should be renamed, and then new remote synced)
        byte[] data2 = new byte[fileSize + 1024 * 1024];
        new Random(28).nextBytes(data2);
        Files.write(base2.resolve(filename), data2, StandardOpenOption.CREATE);
        DirectorySync.syncDirs(localFs, base1, localFs, base2, syncLocalDeletes, syncRemoteDeletes, null, null, syncedState, tmp, 32, 5, DirectorySync::log);
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
        Path tmp = Files.createTempDirectory("peergos-sync");
        Path base1 = Files.createTempDirectory("peergos-sync");
        Path base2 = Files.createTempDirectory("peergos-sync");

        LocalFileSystem localFs = new LocalFileSystem(Main.initCrypto().hasher);
        SyncState syncedState = new JdbcTreeState(":memory:");

        boolean syncLocalDeletes = true;
        boolean syncRemoteDeletes = false;
        DirectorySync.syncDirs(localFs, base1, localFs, base2, syncLocalDeletes, syncRemoteDeletes, null, null, syncedState, tmp, 32, 5, DirectorySync::log);

        byte[] data = new byte[fileSize];
        new Random(42).nextBytes(data);
        String filename = "file.bin";
        Files.write(base1.resolve(filename), data, StandardOpenOption.CREATE);

        DirectorySync.syncDirs(localFs, base1, localFs, base2, syncLocalDeletes, syncRemoteDeletes, null, null, syncedState, tmp, 32, 5, DirectorySync::log);
        Assert.assertNotNull(syncedState.byPath(filename));

        // delete remote file and check local is not deleted
        base2.resolve(filename).toFile().delete();
        DirectorySync.syncDirs(localFs, base1, localFs, base2, syncLocalDeletes, syncRemoteDeletes, null, null, syncedState, tmp, 32, 5, DirectorySync::log);
        Assert.assertNotNull(syncedState.byPath(filename));
        Assert.assertTrue(base1.resolve(filename).toFile().exists());
        Assert.assertTrue(syncedState.hasRemoteDelete(filename));
        DirectorySync.syncDirs(localFs, base1, localFs, base2, syncLocalDeletes, syncRemoteDeletes, null, null, syncedState, tmp, 32, 5, DirectorySync::log);
        Assert.assertNotNull(syncedState.byPath(filename));
        Assert.assertTrue(syncedState.hasRemoteDelete(filename));
        Assert.assertTrue(base1.resolve(filename).toFile().exists());

        // restore the remote file (it should be removed from the delete list)
        Files.write(base2.resolve(filename), data, StandardOpenOption.CREATE);
        DirectorySync.syncDirs(localFs, base1, localFs, base2, syncLocalDeletes, syncRemoteDeletes, null, null, syncedState, tmp, 32, 5, DirectorySync::log);
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
        Path tmp = Files.createTempDirectory("peergos-sync");
        Path base1 = Files.createTempDirectory("peergos-sync");
        Path base2 = Files.createTempDirectory("peergos-sync");

        LocalFileSystem localFs = new LocalFileSystem(Main.initCrypto().hasher);
        SyncState syncedState = new JdbcTreeState(":memory:");

        boolean syncLocalDeletes = true;
        boolean syncRemoteDeletes = false;
        DirectorySync.syncDirs(localFs, base1, localFs, base2, syncLocalDeletes, syncRemoteDeletes, null, null, syncedState, tmp, 32, 5, DirectorySync::log);

        byte[] data = new byte[fileSize];
        new Random(42).nextBytes(data);
        String filename = "file.bin";
        Files.write(base1.resolve(filename), data, StandardOpenOption.CREATE);

        DirectorySync.syncDirs(localFs, base1, localFs, base2, syncLocalDeletes, syncRemoteDeletes, null, null, syncedState, tmp, 32, 5, DirectorySync::log);
        Assert.assertNotNull(syncedState.byPath(filename));

        // delete remote file and check local is not deleted
        base2.resolve(filename).toFile().delete();
        DirectorySync.syncDirs(localFs, base1, localFs, base2, syncLocalDeletes, syncRemoteDeletes, null, null, syncedState, tmp, 32, 5, DirectorySync::log);
        Assert.assertNotNull(syncedState.byPath(filename));
        Assert.assertTrue(base1.resolve(filename).toFile().exists());
        Assert.assertTrue(syncedState.hasRemoteDelete(filename));
        DirectorySync.syncDirs(localFs, base1, localFs, base2, syncLocalDeletes, syncRemoteDeletes, null, null, syncedState, tmp, 32, 5, DirectorySync::log);
        Assert.assertNotNull(syncedState.byPath(filename));
        Assert.assertTrue(syncedState.hasRemoteDelete(filename));
        Assert.assertTrue(base1.resolve(filename).toFile().exists());

        // modify the local file (it should be copied to remote)
        byte[] data2 = new byte[fileSize + 1024 * 1024];
        new Random(28).nextBytes(data2);
        base1.resolve(filename).toFile().delete();
        Files.write(base1.resolve(filename), data2, StandardOpenOption.CREATE);
        DirectorySync.syncDirs(localFs, base1, localFs, base2, syncLocalDeletes, syncRemoteDeletes, null, null, syncedState, tmp, 32, 5, DirectorySync::log);
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
    }
}
