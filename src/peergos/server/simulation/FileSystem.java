package peergos.server.simulation;


import peergos.shared.user.fs.*;
import peergos.shared.user.fs.transaction.*;
import peergos.shared.util.*;

import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.stream.*;

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

    byte[] read(Path path, BiConsumer<Long, Long> progressConsumer);

    default byte[] read(Path path) {
        return read(path, (a,b) -> {});
    }

    void write(Path path, byte[] data, Consumer<Long> progressConsumer);

    void writeSubtree(Path path, Stream<FileWrapper.FolderUploadProperties> folders, Function<FileUploadTransaction, CompletableFuture<Boolean>> resumeFile);

    void modify(Path path, byte[] data, Consumer<Long> progressConsumer);

    default void write(Path path, byte[] data) {
        write(path, data, l -> {});
    }

    default void modify(Path path, byte[] data) {
        modify(path, data, l -> {});
    }
    void delete(Path path);

    void grant(Path path, String user, Permission permission);

    void revoke(Path path, String user, Permission permission);

    Stat stat(Path path);

    void mkdir(Path path);

    List<Path> ls(Path path, boolean showHidden);

    default List<Path> ls(Path path) {
        return ls(path, true);
    }

    default void walk(Path path, Consumer<Path> func)  {
        FileProperties fileProperties = stat(path).fileProperties();

        // skip hidden
        if (fileProperties.isHidden)
            return;

        //DFS
        if (fileProperties.isDirectory) {
            List<Path> ls = ls(path);
            for (Path child : ls) {
                walk(child, func);
            }
        }

        func.accept(path);
    }

    default void walk(Consumer<Path> func)  {
        walk(PathUtil.get("/"+  user()), func);
    }

    /**
     *
     * @param other user to follow
     * @param reciprocate uni-directional following when  true, bi-directional when false
     */
    void follow(FileSystem other, boolean reciprocate);

    Path getRandomSharedPath(Random random, FileSystem.Permission permission, String sharee);

    List<String> getSharees(Path path, Permission permission);
}

