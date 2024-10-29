package peergos.server.tests;

import org.junit.Assert;
import org.junit.Test;
import peergos.server.sync.DirectorySync;
import peergos.server.sync.JdbcTreeState;
import peergos.server.sync.LocalFileSystem;
import peergos.server.sync.SyncState;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Random;

public class SyncTests {

    @Test
    public void moves() throws Exception {
        Path base1 = Files.createTempDirectory("peergos-sync");
        Path base2 = Files.createTempDirectory("peergos-sync");

        LocalFileSystem localFs = new LocalFileSystem();
        SyncState syncedState = new JdbcTreeState(":memory:");

        DirectorySync.syncDirs(localFs, base1, localFs, base2, syncedState);

        byte[] data = new byte[1024 * 1024];
        new Random(42).nextBytes(data);
        String filename = "file.bin";
        Files.write(base1.resolve(filename), data, StandardOpenOption.CREATE);

        DirectorySync.syncDirs(localFs, base1, localFs, base2, syncedState);
        Assert.assertTrue(syncedState.byPath(filename) != null);

        // move file to a subdir
        Path subdir = base1.resolve("subdir");
        subdir.toFile().mkdirs();
        Files.move(base1.resolve(filename), subdir.resolve(filename));
        DirectorySync.syncDirs(localFs, base1, localFs, base2, syncedState);
        Assert.assertTrue(syncedState.byPath(filename) == null);
        String fileRelPath = subdir.getFileName().resolve(filename).toString();
        Assert.assertTrue(syncedState.byPath(fileRelPath) != null);

        // sync should be stable
        DirectorySync.syncDirs(localFs, base1, localFs, base2, syncedState);
        Assert.assertTrue(syncedState.byPath(filename) == null);
        Assert.assertTrue(syncedState.byPath(fileRelPath) != null);

        // move the file back
        Files.move(subdir.resolve(filename), base1.resolve(filename));
        DirectorySync.syncDirs(localFs, base1, localFs, base2, syncedState);
        Assert.assertTrue(syncedState.byPath(filename) != null);
        Assert.assertTrue(syncedState.byPath(fileRelPath) == null);

        // check stability
        DirectorySync.syncDirs(localFs, base1, localFs, base2, syncedState);
        Assert.assertTrue(syncedState.byPath(filename) != null);
        Assert.assertTrue(syncedState.byPath(fileRelPath) == null);

        Assert.assertTrue(syncedState.getInProgressCopies().isEmpty());
    }
}
