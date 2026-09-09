package peergos.server.tests;

import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;
import peergos.server.net.SyncConfigHandler;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** The sync config holds a secret link per pair, so it must never be written readable by anyone else. */
public class SyncConfigWriteTests {

    private static boolean posix(Path dir) {
        return dir.getFileSystem().supportedFileAttributeViews().contains("posix");
    }

    private static List<String> contents(Path dir) throws Exception {
        try (Stream<Path> kids = Files.list(dir)) {
            return kids.map(p -> p.getFileName().toString()).sorted().collect(Collectors.toList());
        }
    }

    @Test
    public void configIsOwnerOnlyAndLeavesNothingBehind() throws Exception {
        Path dir = Files.createTempDirectory("peergos-sync-config");
        Assume.assumeTrue(posix(dir));
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxr-xr-x"));
        // a config from before this was fixed, to pin the repair on upgrade
        Path target = dir.resolve(SyncConfigHandler.SYNC_CONFIG_FILENAME);
        Files.write(target, "{}".getBytes(StandardCharsets.UTF_8));
        Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rw-r--r--"));

        byte[] payload = "{\"pairs\":[]}".getBytes(StandardCharsets.UTF_8);
        SyncConfigHandler.writeConfig(dir, payload);

        Assert.assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(target)));
        Assert.assertArrayEquals("the config survives the swap", payload, Files.readAllBytes(target));
        Assert.assertEquals("no temp file is left behind",
                List.of(SyncConfigHandler.SYNC_CONFIG_FILENAME), contents(dir));
    }

    /** The pre-json config was written under the umask and holds the same links, so it can't be left there. */
    @Test
    public void theSupersededConfigIsRemoved() throws Exception {
        Path dir = Files.createTempDirectory("peergos-sync-config");
        Path old = dir.resolve(SyncConfigHandler.OLD_SYNC_CONFIG_FILENAME);
        Files.write(old, "links = secretlink\n".getBytes(StandardCharsets.UTF_8));

        SyncConfigHandler.writeConfig(dir, "{\"pairs\":[]}".getBytes(StandardCharsets.UTF_8));

        Assert.assertFalse(Files.exists(old));
        Assert.assertEquals(List.of(SyncConfigHandler.SYNC_CONFIG_FILENAME), contents(dir));
    }
}
