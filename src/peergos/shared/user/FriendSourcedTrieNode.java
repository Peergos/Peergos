package peergos.shared.user;

import peergos.shared.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.user.fs.*;
import peergos.shared.util.*;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

public class FriendSourcedTrieNode implements TrieNode {

    public final String ownerName;
    private final IncomingCapCache cache;
    private final EntryPoint sharedDir;
    private final Crypto crypto;

    public FriendSourcedTrieNode(IncomingCapCache cache,
                                 String ownerName,
                                 EntryPoint sharedDir,
                                 Crypto crypto) {
        this.cache = cache;
        this.ownerName = ownerName;
        this.sharedDir = sharedDir;
        this.crypto = crypto;
    }

    public static CompletableFuture<Optional<FriendSourcedTrieNode>> build(IncomingCapCache cache,
                                                                           EntryPoint e,
                                                                           NetworkAccess network,
                                                                           Crypto crypto) {
        return Futures.of(Optional.of(new FriendSourcedTrieNode(cache, e.ownerName, e, crypto)));
    }

    /**
     *
     * @param crypto
     * @param network
     * @return Any new capabilities from the friend and the previously processed size of caps in bytes
     */
    public synchronized CompletableFuture<CapsDiff> ensureUptodate(Crypto crypto,
                                                                   NetworkAccess network) {
        return cache.ensureFriendUptodate(ownerName, sharedDir, network);
    }

    public synchronized CompletableFuture<CapsDiff> getCaps(long readByteOffset,
                                                            long writeByteOffset,
                                                            NetworkAccess network) {
        return cache.getCapsFrom(ownerName, sharedDir, readByteOffset, writeByteOffset, network);
    }

    private CompletableFuture<Optional<FileWrapper>> getFriendRoot(NetworkAccess network) {
        return NetworkAccess.getLatestEntryPoint(sharedDir, network)
                .thenCompose(sharedDir -> {
                    return sharedDir.file.retrieveParent(network)
                            .thenCompose(sharedOpt -> {
                                if (sharedOpt.isEmpty()) {
                                    CompletableFuture<Optional<FileWrapper>> empty = CompletableFuture.completedFuture(Optional.empty());
                                    return empty;
                                }
                                return sharedOpt.get().retrieveParent(network);
                            });
                }).exceptionally(t -> {
                    System.out.println("Couldn't retrieve entry point for friend: " + sharedDir.ownerName + ". Did they remove you as a follower?");
                    return Optional.empty();
                });
    }

    private FileWrapper convert(FileWrapper file, String path) {
        return file.withTrieNode(new FriendDirTrieNode(path, this));
    }

    @Override
    public synchronized CompletableFuture<Optional<FileWrapper>> getByPath(String path,
                                                                           Hasher hasher,
                                                                           NetworkAccess network) {
        FileProperties.ensureValidPath(path);
        if (path.isEmpty() || path.equals("/"))
            return getFriendRoot(network)
                    .thenApply(opt -> opt.map(f -> f.withTrieNode(this)));
        Path file = Paths.get(ownerName + path);
        return ensureUptodate(crypto, network)
                .thenCompose(x -> cache.getByPath(file, cache.getVersion(), hasher, network))
                .thenApply(opt -> opt.map(f -> convert(f, path)));
    }

    @Override
    public synchronized CompletableFuture<Optional<FileWrapper>> getByPath(String path,
                                                                           Snapshot version,
                                                                           Hasher hasher,
                                                                           NetworkAccess network) {
        FileProperties.ensureValidPath(path);
        if (path.isEmpty() || path.equals("/"))
            return getFriendRoot(network)
                    .thenApply(opt -> opt.map(f -> f.withTrieNode(this)));
        Path file = Paths.get(ownerName + path);
        return cache.getByPath(file, version, hasher, network)
                .thenApply(opt -> opt.map(f -> convert(f, path)));
    }

    @Override
    public synchronized CompletableFuture<Set<FileWrapper>> getChildren(String path,
                                                                        Hasher hasher,
                                                                        NetworkAccess network) {
        FileProperties.ensureValidPath(path);
        Path dir = Paths.get(ownerName + path);
        return ensureUptodate(crypto, network)
                .thenCompose(x -> cache.getChildren(dir, cache.getVersion(), hasher, network))
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
        Path dir = Paths.get(ownerName + path);
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
    public boolean isEmpty() {
        throw new IllegalStateException("Not valid operation on FriendSourcedTrieNode.");
    }

    public static class ReadAndWriteCaps {
        public final CapabilitiesFromUser readCaps, writeCaps;

        public ReadAndWriteCaps(CapabilitiesFromUser readCaps, CapabilitiesFromUser writeCaps) {
            this.readCaps = readCaps;
            this.writeCaps = writeCaps;
        }

        public static ReadAndWriteCaps empty() {
            CapabilitiesFromUser empty = CapabilitiesFromUser.empty();
            return new ReadAndWriteCaps(empty, empty);
        }
    }
}
