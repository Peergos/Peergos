package peergos.server.tests.simulation;


import java.nio.file.Path;

public interface FileSystem {
/**
 * Implement the same File System model as the crypt-tree
 * on the native file-system, for testing/fuzzing.
 */
    public enum Permission {
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

    void grant(Path path, String user, Permission permission);

    void revoke(Path path, String user, Permission permission);

    Stat stat(Path path);

    void mkdir(Path path);
}

