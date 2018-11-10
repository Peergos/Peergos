package peergos.shared.user;
import java.util.logging.*;

import jsinterop.annotations.JsConstructor;
import jsinterop.annotations.JsType;
import peergos.shared.*;
import peergos.shared.user.fs.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

@JsType
public interface TrieNode {

    CompletableFuture<Optional<FileTreeNode>> getByPath(String path, NetworkAccess network);

    CompletableFuture<Set<FileTreeNode>> getChildren(String path, NetworkAccess network);

    Set<String> getChildNames();

    TrieNode put(String path, EntryPoint e);

    TrieNode removeEntry(String path);

    TrieNode addPathMapping(String prefix, String target);

    boolean hasWriteAccess();

    TrieNode clear();
}
