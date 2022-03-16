package peergos.shared.user;

import peergos.shared.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.user.fs.*;
import peergos.shared.util.*;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

public class AcquaintanceSourcedTrieNode implements TrieNode {

    private final String ownerName;
    private final IncomingCapCache cache;
    private final Crypto crypto;

    public AcquaintanceSourcedTrieNode(String ownerName,
                                       IncomingCapCache cache,
                                       Crypto crypto) {
        this.ownerName = ownerName;
        this.cache = cache;
        this.crypto = crypto;
    }

    public static CompletableFuture<Optional<AcquaintanceSourcedTrieNode>> build(String ownerName,
                                                                                 IncomingCapCache cache,
                                                                                 Crypto crypto) {
        return Futures.of(Optional.of(new AcquaintanceSourcedTrieNode(ownerName, cache, crypto)));
    }

    private FileWrapper convert(FileWrapper file, String path) {
        return file.withTrieNode(new ExternalTrieNode(path, this));
    }

    @Override
    public synchronized CompletableFuture<Optional<FileWrapper>> getByPath(String path,
                                                                           Hasher hasher,
                                                                           NetworkAccess network) {
        FileProperties.ensureValidPath(path);
        Path file = PathUtil.get(ownerName + path);
        return cache.getByPath(file, cache.getVersion(), hasher, network)
                .thenApply(opt -> opt.map(f -> convert(f, path)));
    }

    @Override
    public synchronized CompletableFuture<Optional<FileWrapper>> getByPath(String path,
                                                                           Snapshot version,
                                                                           Hasher hasher,
                                                                           NetworkAccess network) {
        FileProperties.ensureValidPath(path);
        Path file = PathUtil.get(ownerName + path);
        return cache.getByPath(file, version, hasher, network)
                .thenApply(opt -> opt.map(f -> convert(f, path)));
    }

    @Override
    public synchronized CompletableFuture<Set<FileWrapper>> getChildren(String path,
                                                                        Hasher hasher,
                                                                        NetworkAccess network) {
        FileProperties.ensureValidPath(path);
        Path dir = PathUtil.get(ownerName + path);
        return cache.getChildren(dir, cache.getVersion(), hasher, network)
                .thenApply(children -> children.stream()
                        .map(f -> convert(f, path + "/" + f.getName()))
                        .collect(Collectors.toSet()));
    }

    @Override
    public synchronized CompletableFuture<Set<FileWrapper>> getChildren(String path,
                                                                        Hasher hasher,
                                                                        Snapshot version,
                                                                        NetworkAccess network) {
        FileProperties.ensureValidPath(path);
        Path dir = PathUtil.get(ownerName + path);
        return cache.getChildren(dir, version, hasher, network)
                .thenApply(children -> children.stream()
                        .map(f -> convert(f, path + "/" + f.getName()))
                        .collect(Collectors.toSet()));
    }

    @Override
    public synchronized Set<String> getChildNames() {
        throw new IllegalStateException("Not valid operation on FriendSourcedTrieNode.");
    }

    @Override
    public synchronized TrieNode put(String path, EntryPoint e) {
        throw new IllegalStateException("Not valid operation on FriendSourcedTrieNode.");
    }

    @Override
    public synchronized TrieNode putNode(String path, TrieNode t) {
        throw new IllegalStateException("Not valid operation on FriendSourcedTrieNode.");
    }

    @Override
    public synchronized TrieNode removeEntry(String path) {
        if (TrieNode.canonicalise(path).isEmpty())
            return TrieNodeImpl.empty();
        throw new IllegalStateException("Not valid operation on FriendSourcedTrieNode.");
    }

    @Override
    public Collection<TrieNode> getChildNodes() {
        throw new IllegalStateException("Not valid operation on FriendSourcedTrieNode.");
    }

    @Override
    public TrieNode getChildNode(String name) {
        throw new IllegalStateException("Not valid operation on FriendSourcedTrieNode.");
    }

    @Override
    public boolean isEmpty() {
        throw new IllegalStateException("Not valid operation on FriendSourcedTrieNode.");
    }
}
