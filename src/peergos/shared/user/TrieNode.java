package peergos.shared.user;

import jsinterop.annotations.*;
import peergos.shared.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.user.fs.*;

import java.util.*;
import java.util.concurrent.*;

@JsType
public interface TrieNode {

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

    Collection<TrieNode> getChildNodes();

    TrieNode getChildNode(String name);

    boolean isEmpty();

    static String canonicalise(String path) {
        path = path.replaceAll("\\\\", "/");
        return (path.startsWith("/") ? path.substring(1) : path);
    }
}
