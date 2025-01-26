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

public class SyncTests {

    @Test
    public void moves() throws Exception {
        Path base1 = Files.createTempDirectory("peergos-sync");
        Path base2 = Files.createTempDirectory("peergos-sync");

        LocalFileSystem localFs = new LocalFileSystem(Main.initCrypto().hasher);
        SyncState syncedState = new JdbcTreeState(":memory:");

        DirectorySync.syncDirs(localFs, base1, localFs, base2, null, null, syncedState, 32, 5);

        byte[] data = new byte[6 * 1024 * 1024];
        new Random(42).nextBytes(data);
        String filename = "file.bin";
        Files.write(base1.resolve(filename), data, StandardOpenOption.CREATE);

        DirectorySync.syncDirs(localFs, base1, localFs, base2, null, null, syncedState, 32, 5);
        Assert.assertTrue(syncedState.byPath(filename) != null);

        // move file to a subdir
        Path subdir = base1.resolve("subdir");
        subdir.toFile().mkdirs();
        Files.move(base1.resolve(filename), subdir.resolve(filename));
        DirectorySync.syncDirs(localFs, base1, localFs, base2, null, null, syncedState, 32, 5);
        Assert.assertTrue(syncedState.byPath(filename) == null);
        String fileRelPath = subdir.getFileName().resolve(filename).toString();
        Assert.assertTrue(syncedState.byPath(fileRelPath) != null);

        // sync should be stable
        DirectorySync.syncDirs(localFs, base1, localFs, base2, null, null, syncedState, 32, 5);
        Assert.assertTrue(syncedState.byPath(filename) == null);
        Assert.assertTrue(syncedState.byPath(fileRelPath) != null);

        // move the file back
        Files.move(subdir.resolve(filename), base1.resolve(filename));
        DirectorySync.syncDirs(localFs, base1, localFs, base2, null, null, syncedState, 32, 5);
        Assert.assertTrue(syncedState.byPath(filename) != null);
        Assert.assertTrue(syncedState.byPath(fileRelPath) == null);

        // check stability
        DirectorySync.syncDirs(localFs, base1, localFs, base2, null, null, syncedState, 32, 5);
        Assert.assertTrue(syncedState.byPath(filename) != null);
        Assert.assertTrue(syncedState.byPath(fileRelPath) == null);

        Assert.assertTrue(syncedState.getInProgressCopies().isEmpty());
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
        Assert.assertTrue(retrieved.modificationTime == state1.modificationTime);
        Assert.assertTrue(retrieved.size == state1.size);
        FileState state2 = new FileState(path, 12346000, 12346, hash);
        synced.add(state2);
        retrieved = synced.byPath(path);
        Assert.assertTrue(retrieved.modificationTime == state2.modificationTime);
        Assert.assertTrue(retrieved.size == state2.size);
    }
}
