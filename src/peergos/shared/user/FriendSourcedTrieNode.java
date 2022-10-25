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
    private GroupAdder groupAdder;

    public FriendSourcedTrieNode(IncomingCapCache cache,
                                 String ownerName,
                                 EntryPoint sharedDir,
                                 GroupAdder groupAdder,
                                 Crypto crypto) {
        this.cache = cache;
        this.ownerName = ownerName;
        this.sharedDir = sharedDir;
        this.crypto = crypto;
        this.groups = new ArrayList<>();
        this.groupAdder = groupAdder;
    }

    public interface GroupAdder {
        CompletableFuture<Snapshot> add(CapabilityWithPath cap, String owner, NetworkAccess network, Snapshot s, Committer c);
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
                                                                           GroupAdder groupAdder,
                                                                           NetworkAccess network,
                                                                           Crypto crypto) {
        return Futures.of(Optional.of(new FriendSourcedTrieNode(cache, e.ownerName, e, groupAdder, crypto)));
    }

    public CompletableFuture<Snapshot> getLatestVersion(NetworkAccess network) {
        return cache.getLatestVersion(sharedDir, network);
    }

    /**
     *
     * @param crypto
     * @param network
     * @return Any new capabilities from the friend and the previously processed size of caps in bytes
     */
    public synchronized CompletableFuture<Pair<Snapshot, CapsDiff>> ensureUptodate(Snapshot s,
                                                                                   Committer c,
                                                                                   Crypto crypto,
                                                                                   NetworkAccess network) {
        return cache.ensureFriendUptodate(ownerName, sharedDir, groups, s, c, network);
    }

    public synchronized CompletableFuture<CapsDiff> getCaps(ProcessedCaps current,
                                                            Snapshot s,
                                                            NetworkAccess network) {
        return cache.getCapsFrom(ownerName, sharedDir, groups, current, s,network);
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

    public CompletableFuture<Pair<Snapshot, CapsDiff>> updateIncludingGroups(Snapshot s,
                                                                             Committer c,
                                                                             NetworkAccess network) {
        return ensureUptodate(s, c, crypto, network)
                .thenCompose(p -> {
                    List<CapabilityWithPath> newGroups = p.right.getNewCaps().stream()
                            .filter(cap -> cap.path.startsWith("/" + ownerName + "/" + UserContext.SHARED_DIR_NAME))
                            .collect(Collectors.toList());
                    for (CapabilityWithPath groupCap : newGroups) {
                        addGroup(new EntryPoint(groupCap.cap, ownerName));
                    }
                    if (newGroups.isEmpty())
                        return Futures.of(p);
                    return Futures.reduceAll(newGroups, p.left, (b, cap) -> groupAdder.add(cap, ownerName, network, b, c), (a, b) -> b)
                            .thenCompose(res -> ensureUptodate(res, c, crypto, network));
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
        Path file = PathUtil.get(ownerName + path);
        return network.synchronizer.applyComplexUpdate(cache.owner(), cache.signingPair(), (v, c) -> getLatestVersion(network)
                .thenCompose(s -> updateIncludingGroups(v.mergeAndOverwriteWith(s), c, network)).thenApply(p -> p.left))
                .thenCompose(v -> cache.getByPath(file, v, hasher, network))
                .thenApply(opt -> opt.map(f -> convert(f, path)))
                .exceptionally(t ->  Optional.empty());
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
        Path file = PathUtil.get(ownerName + path);
        return cache.getByPath(file, version, hasher, network)
                .thenApply(opt -> opt.map(f -> convert(f, path)));
    }

    private static String canonicalise(String path) {
        if (path.endsWith("/"))
            return path.substring(0, path.length() - 1);
        return path;
    }

    @Override
    public synchronized CompletableFuture<Set<FileWrapper>> getChildren(String path,
                                                                        Hasher hasher,
                                                                        NetworkAccess network) {
        FileProperties.ensureValidPath(path);
        Path dir = PathUtil.get(ownerName + path);
        return network.synchronizer.applyComplexUpdate(cache.owner(), cache.signingPair(), (v, c) -> getLatestVersion(network)
                .thenCompose(s -> updateIncludingGroups(v.mergeAndOverwriteWith(s), c, network)).thenApply(p -> p.left))
                .thenCompose(v -> cache.getChildren(dir, v, hasher, network))
                .thenApply(children -> children.stream()
                        .map(f -> convert(f, canonicalise(path) + "/" + f.getName()))
                        .collect(Collectors.toSet()))
                .exceptionally(t -> Collections.emptySet());
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
                        .map(f -> convert(f, canonicalise(path) + "/" + f.getName()))
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
