package peergos.shared.user;

import jsinterop.annotations.JsType;
import peergos.shared.*;
import peergos.shared.user.fs.*;

import java.util.*;
import java.util.concurrent.*;

@JsType
public interface TrieNode {

    CompletableFuture<Optional<FileWrapper>> getByPath(String path, NetworkAccess network);

    CompletableFuture<Set<FileWrapper>> getChildren(String path, NetworkAccess network);

    Set<String> getChildNames();

    TrieNode put(String path, EntryPoint e);

    TrieNode putNode(String path, TrieNode t);

    TrieNode removeEntry(String path);

    boolean isEmpty();

    static String canonicalise(String path) {
        return path.startsWith("/") ? path.substring(1) : path;
    }
}
