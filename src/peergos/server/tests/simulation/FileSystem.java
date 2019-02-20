package peergos.server.tests.simulation;

import peergos.shared.user.fs.FileAccess;
import peergos.shared.user.fs.FileProperties;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public interface FileSystem {
/**
 * Implement the same File System model as the crypt-tree
 * on the native file-system, for testing/fuzzing.
 */
    enum Permission {
        READ, WRITE
    }

    /**
     * All operations are done as this user
     * @return
     */
    String user();

    byte[] read(Path path);

    void write(Path path, byte[] data);

    void delete(Path path);

    void grant(Path path, List<String> users, Permission permission);

    void revoke(Path path, List<String> users, Permission permission);

    Stat stat(Path path);
}

