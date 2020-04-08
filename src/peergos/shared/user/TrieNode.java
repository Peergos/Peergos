package peergos.shared.user;

import jsinterop.annotations.*;
import peergos.shared.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.user.fs.*;

import java.util.*;
import java.util.concurrent.*;

@JsType
public interface TrieNode {

    default void ensureValidPath(String path) {
        if (path.length() > FileProperties.MAX_PATH_SIZE)
            throw new IllegalArgumentException("Path too long. Paths must be smaller than " + FileProperties.MAX_PATH_SIZE);
    }

    CompletableFuture<Optional<FileWrapper>> getByPath(String path, Hasher hasher, NetworkAccess network);

    @JsIgnore
    CompletableFuture<Optional<FileWrapper>> getByPath(String path, Snapshot version, Hasher hasher, NetworkAccess network);

    CompletableFuture<Set<FileWrapper>> getChildren(String path, Hasher hasher, NetworkAccess network);

    @JsIgnore
    CompletableFuture<Set<FileWrapper>> getChildren(String path, Hasher hasher, Snapshot version, NetworkAccess network);

    Set<String> getChildNames();

    TrieNode put(String path, EntryPoint e);

    TrieNode putNode(String path, TrieNode t);

    TrieNode removeEntry(String path);

    boolean isEmpty();

    static String canonicalise(String path) {
        return path.startsWith("/") ? path.substring(1) : path;
    }
}
