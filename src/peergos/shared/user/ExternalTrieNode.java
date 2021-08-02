package peergos.shared.user;

import peergos.shared.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.user.fs.*;

import java.util.*;
import java.util.concurrent.*;

public class ExternalTrieNode implements TrieNode {
    private final String dirPath; // without owner
    private final TrieNode root;

    public ExternalTrieNode(String dirPath, TrieNode root) {
        if (dirPath.startsWith("//"))
            dirPath = dirPath.substring(1);
        this.dirPath = dirPath;
        this.root = root;
    }

    private String subPath(String relative) {
        if (dirPath.endsWith("/") && relative.startsWith("/"))
            return dirPath + relative.substring(1);
        return dirPath + relative;
    }

    @Override
    public CompletableFuture<Optional<FileWrapper>> getByPath(String path, Hasher hasher, NetworkAccess network) {
        return root.getByPath(subPath(path), hasher, network);
    }

    @Override
    public CompletableFuture<Optional<FileWrapper>> getByPath(String path, Snapshot version, Hasher hasher, NetworkAccess network) {
        return root.getByPath(subPath(path), version, hasher, network);
    }

    @Override
    public CompletableFuture<Set<FileWrapper>> getChildren(String path, Hasher hasher, NetworkAccess network) {
        return root.getChildren(subPath(path), hasher, network);
    }

    @Override
    public CompletableFuture<Set<FileWrapper>> getChildren(String path, Hasher hasher, Snapshot version, NetworkAccess network) {
        return root.getChildren(subPath(path), hasher, version, network);
    }

    @Override
    public Set<String> getChildNames() {
        throw new IllegalStateException("Invalid operation");
    }

    @Override
    public TrieNode put(String path, EntryPoint e) {
        throw new IllegalStateException("Invalid operation");
    }

    @Override
    public TrieNode putNode(String path, TrieNode t) {
        throw new IllegalStateException("Invalid operation");
    }

    @Override
    public TrieNode removeEntry(String path) {
        throw new IllegalStateException("Invalid operation");
    }

    @Override
    public Collection<TrieNode> getChildNodes() {
        throw new IllegalStateException("Invalid operation");
    }

    @Override
    public TrieNode getChildNode(String name) {
        throw new IllegalStateException("Not valid operation on FriendSourcedTrieNode.");
    }

    @Override
    public boolean isEmpty() {
        throw new IllegalStateException("Invalid operation");
    }
}
