package peergos.shared.user;

import peergos.shared.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.social.*;
import peergos.shared.user.fs.*;
import peergos.shared.util.*;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.stream.*;

public class FriendSourcedTrieNode implements TrieNode {

    public final String ownerName;
    private final IncomingCapCache cache;
    private final EntryPoint sharedDir;
    private final Crypto crypto;
    private final List<EntryPoint> groups;
    private BiFunction<CapabilityWithPath, String, CompletableFuture<Boolean>> groupAdder;

    public FriendSourcedTrieNode(IncomingCapCache cache,
                                 String ownerName,
                                 EntryPoint sharedDir,
                                 BiFunction<CapabilityWithPath, String, CompletableFuture<Boolean>> groupAdder,
                                 Crypto crypto) {
        this.cache = cache;
        this.ownerName = ownerName;
        this.sharedDir = sharedDir;
        this.crypto = crypto;
        this.groups = new ArrayList<>();
        this.groupAdder = groupAdder;
    }

    public synchronized void addGroup(EntryPoint group) {
        if (! groups.contains(group))
            groups.add(group);
    }

    public static CompletableFuture<Optional<FriendSourcedTrieNode>> build(IncomingCapCache cache,
                                                                           EntryPoint e,
                                                                           NetworkAccess network,
                                                                           Crypto crypto) {
        return Futures.of(Optional.of(new FriendSourcedTrieNode(cache, e.ownerName, e, null, crypto)));
    }

    public static CompletableFuture<Optional<FriendSourcedTrieNode>> build(IncomingCapCache cache,
                                                                           EntryPoint e,
                                                                           BiFunction<CapabilityWithPath, String, CompletableFuture<Boolean>> groupAdder,
                                                                           NetworkAccess network,
                                                                           Crypto crypto) {
        return Futures.of(Optional.of(new FriendSourcedTrieNode(cache, e.ownerName, e, groupAdder, crypto)));
    }

    /**
     *
     * @param crypto
     * @param network
     * @return Any new capabilities from the friend and the previously processed size of caps in bytes
     */
    public synchronized CompletableFuture<CapsDiff> ensureUptodate(Crypto crypto,
                                                                   NetworkAccess network) {
        return cache.ensureFriendUptodate(ownerName, sharedDir, groups, network);
    }

    public synchronized CompletableFuture<CapsDiff> getCaps(ProcessedCaps current,
                                                            NetworkAccess network) {
        return cache.getCapsFrom(ownerName, sharedDir, groups, current, network);
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
        return file.withTrieNode(new ExternalTrieNode(path, this));
    }

    public CompletableFuture<Boolean> updateIncludingGroups(NetworkAccess network) {
        return ensureUptodate(crypto, network)
                .thenCompose(x -> {
                    List<CapabilityWithPath> newGroups = x.getNewCaps().stream()
                            .filter(c -> c.path.startsWith("/" + ownerName + "/" + UserContext.SHARED_DIR_NAME))
                            .collect(Collectors.toList());
                    for (CapabilityWithPath groupCap : newGroups) {
                        addGroup(new EntryPoint(groupCap.cap, ownerName));
                    }
                    if (newGroups.isEmpty())
                        return Futures.of(true);
                    return Futures.reduceAll(newGroups, true, (b, c) -> groupAdder.apply(c, ownerName), (a, b) -> b)
                            .thenCompose(y -> ensureUptodate(crypto, network))
                            .thenApply(z -> true);
                });
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
        return updateIncludingGroups(network)
                .thenCompose(y -> cache.getByPath(file, cache.getVersion(), hasher, network))
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
        return updateIncludingGroups(network)
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
    public TrieNode getChildNode(String name) {
        throw new IllegalStateException("Not valid operation on FriendSourcedTrieNode.");
    }

    @Override
    public boolean isEmpty() {
        throw new IllegalStateException("Not valid operation on FriendSourcedTrieNode.");
    }
}
