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

    private CompletableFuture<Optional<FileWrapper>> getFriendRoot(NetworkAccess network) {
        return NetworkAccess.getLatestEntryPoint(sharedDir, network)
                .thenCompose(sharedDir -> {
                    return sharedDir.file.retrieveParent(network)
                            .thenCompose(sharedOpt -> {
                                if (! sharedOpt.isPresent()) {
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
                .thenCompose(x -> cache.getByPath(file, hasher, network))
                .thenApply(opt -> opt.map(f -> convert(f, path)));
    }

    @Override
    public synchronized CompletableFuture<Optional<FileWrapper>> getByPath(String path, Snapshot version, Hasher hasher, NetworkAccess network) {
        FileProperties.ensureValidPath(path);
        if (path.isEmpty() || path.equals("/"))
            return getFriendRoot(network)
                    .thenApply(opt -> opt.map(f -> f.withTrieNode(this)));
        Path file = Paths.get(ownerName + path);
        Path parent = file.getParent();
        return ensureUptodate(crypto, network)
                .thenCompose(x -> cache.getCapsInDirectory(parent, network)
                        .thenCompose(caps -> caps.getChild(file.getFileName().toString())
                                .map(c -> network.getFile(new EntryPoint(c, ownerName), version)
                                        .thenApply(opt -> opt.map(f -> convert(f, path))))
                                .orElse(Futures.of(Optional.empty()))));
    }

    @Override
    public synchronized CompletableFuture<Set<FileWrapper>> getChildren(String path, Hasher hasher, NetworkAccess network) {
        FileProperties.ensureValidPath(path);
        Path dir = Paths.get(ownerName + path);
        return ensureUptodate(crypto, network)
                .thenCompose(x -> cache.getCapsInDirectory(dir, network)
                        .thenCompose(caps -> Futures.combineAll(caps.getChildren().stream()
                                .map(c -> network.retrieveEntryPoint(new EntryPoint(c, ownerName))
                                        .thenApply(opt -> opt.map(f -> convert(f, path + "/" + f.getName()))))
                                .collect(Collectors.toList()))))
                .thenApply(res -> res.stream()
                        .flatMap(Optional::stream)
                        .collect(Collectors.toSet()))
                .thenCompose(directChildren -> cache.getIndirectChildren(dir,
                        directChildren.stream()
                                .map(FileWrapper::getName)
                                .collect(Collectors.toSet()), hasher, network)
                        .thenApply(indirectChildren -> Stream.concat(directChildren.stream(), indirectChildren.stream())
                                .collect(Collectors.toSet())));
    }

    @Override
    public synchronized CompletableFuture<Set<FileWrapper>> getChildren(String path, Hasher hasher, Snapshot version, NetworkAccess network) {
        FileProperties.ensureValidPath(path);
        Path dir = Paths.get(ownerName + path);
        return ensureUptodate(crypto, network)
                .thenCompose(x -> cache.getCapsInDirectory(dir, network)
                        .thenCompose(caps -> Futures.combineAll(caps.getChildren().stream()
                                .map(c -> network.getFile(new EntryPoint(c, ownerName), version)
                                        .thenApply(opt -> opt.map(f -> convert(f, path + "/" + f.getName()))))
                                .collect(Collectors.toList()))))
                .thenApply(res -> res.stream()
                        .flatMap(Optional::stream)
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
    }
}
