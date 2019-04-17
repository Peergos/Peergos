package peergos.server.tests.simulation;


import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Iterator;
import java.util.List;
import java.util.function.Consumer;

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

    List<Path> ls(Path path);


    default void walk(Path path, Consumer<Path> func)  {
        //DFS
        for (Path child : ls(path)) {
            walk(child, func);
        }
        func.accept(path);
    }

    default void walk(Consumer<Path> func)  {
        walk(Paths.get("/"+  user()), func);
    }

}

