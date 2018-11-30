package peergos.shared.user;

import peergos.shared.*;
import peergos.shared.crypto.random.*;
import peergos.shared.user.fs.*;

import java.util.*;
import java.util.concurrent.*;

public class FriendSourcedTrieNode implements TrieNode {

    private final String owner;
    private final FileTreeNode cacheDir;
    private final EntryPoint sharedDir;
    private final SafeRandom random;
    private final Fragmenter fragmenter;
    private TrieNode root;
    private long capCount;

    public FriendSourcedTrieNode(FileTreeNode cacheDir,
                                 String owner,
                                 EntryPoint sharedDir,
                                 TrieNode root,
                                 long capCount,
                                 SafeRandom random,
                                 Fragmenter fragmenter) {
        this.cacheDir = cacheDir;
        this.owner = owner;
        this.sharedDir = sharedDir;
        this.root = root;
        this.capCount = capCount;
        this.random = random;
        this.fragmenter = fragmenter;
    }

    public static CompletableFuture<Optional<FriendSourcedTrieNode>> build(FileTreeNode cacheDir,
                                                                           EntryPoint e,
                                                                           NetworkAccess network,
                                                                           SafeRandom random,
                                                                           Fragmenter fragmenter) {
        return network.retrieveEntryPoint(e)
                .thenCompose(sharedDirOpt -> {
                    if (! sharedDirOpt.isPresent())
                        return CompletableFuture.completedFuture(Optional.empty());
                    return FastSharing.loadSharingLinks(cacheDir, sharedDirOpt.get(), e.owner,
                            network, random, fragmenter, true)
                            .thenApply(caps ->
                                    Optional.of(new FriendSourcedTrieNode(cacheDir,
                                            e.owner,
                                            e,
                                            caps.getRetrievedCapabilities().stream()
                                                    .reduce(TrieNodeImpl.empty(),
                                                            (root, cap) -> root.put(trimOwner(cap.path), UserContext.convert(e.owner, cap)),
                                                            (a, b) -> a),
                                            caps.getRecordsRead(),
                                            random, fragmenter)));
                });
    }

    private synchronized CompletableFuture<Boolean> ensureUptodate(NetworkAccess network) {
        // check there are no new capabilities in the friend's shared directory
        return network.retrieveEntryPoint(sharedDir)
                .thenCompose(sharedDirOpt -> {
                    if (!sharedDirOpt.isPresent())
                        return CompletableFuture.completedFuture(true);
                    return FastSharing.getCapabilityCount(sharedDirOpt.get(), network)
                            .thenCompose(count -> {
                                if (count == capCount)
                                    return CompletableFuture.completedFuture(true);
                                return FastSharing.loadSharingLinksFromIndex(cacheDir, sharedDirOpt.get(), owner, network, random, fragmenter, capCount, true)
                                        .thenApply(newCaps -> {
                                            capCount += newCaps.getRecordsRead();
                                            root = newCaps.getRetrievedCapabilities().stream()
                                                    .reduce(root,
                                                            (root, cap) -> root.put(trimOwner(cap.path), UserContext.convert(owner, cap)),
                                                            (a, b) -> a);
                                            return true;
                                        });
                            });
                });
    }

    private CompletableFuture<Optional<FileTreeNode>> getFriendRoot(NetworkAccess network) {
        return network.retrieveEntryPoint(sharedDir)
                .thenCompose(sharedDirOpt -> {
                    if (! sharedDirOpt.isPresent())
                        return CompletableFuture.completedFuture(Optional.empty());
                    return sharedDirOpt.get().retrieveParent(network)
                            .thenCompose(sharedOpt -> {
                                if (! sharedOpt.isPresent())
                                    return CompletableFuture.completedFuture(Optional.empty());
                                return sharedOpt.get().retrieveParent(network);
                            });
                });
    }

    private static String trimOwner(String path) {
        if (path.startsWith("/"))
            path = path.substring(1);
        return path.substring(path.indexOf("/") + 1);
    }

    @Override
    public synchronized CompletableFuture<Optional<FileTreeNode>> getByPath(String path, NetworkAccess network) {
        if (path.isEmpty() || path.equals("/"))
            return getFriendRoot(network);
        return ensureUptodate(network).thenCompose(x -> root.getByPath(path, network));
    }

    @Override
    public synchronized CompletableFuture<Set<FileTreeNode>> getChildren(String path, NetworkAccess network) {
        return ensureUptodate(network).thenCompose(x -> root.getChildren(path, network));
    }

    @Override
    public synchronized Set<String> getChildNames() {
        return root.getChildNames();
    }

    @Override
    public synchronized TrieNode put(String path, EntryPoint e) {
        root = root.put(path, e);
        return this;
    }

    @Override
    public synchronized TrieNode put(String path, TrieNode t) {
        root = root.put(path, t);
        return this;
    }

    @Override
    public synchronized TrieNode removeEntry(String path) {
        root = root.removeEntry(path);
        return this;
    }

    @Override
    public TrieNode addPathMapping(String prefix, String target) {
        throw new IllegalStateException("Unimplemented!");
    }

    @Override
    public boolean hasWriteAccess() {
        return false;
    }

    @Override
    public boolean isEmpty() {
        return root.isEmpty();
    }
}
