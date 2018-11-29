package peergos.shared.user;

import jsinterop.annotations.JsType;
import peergos.shared.*;
import peergos.shared.user.fs.*;

import java.util.*;
import java.util.concurrent.*;

@JsType
public interface TrieNode {

    CompletableFuture<Optional<FileTreeNode>> getByPath(String path, NetworkAccess network);

    CompletableFuture<Set<FileTreeNode>> getChildren(String path, NetworkAccess network);

    Set<String> getChildNames();

    TrieNode put(String path, EntryPoint e);

    TrieNode put(String path, TrieNode t);

    TrieNode removeEntry(String path);

    TrieNode addPathMapping(String prefix, String target);

    boolean hasWriteAccess();

    boolean isEmpty();
}
