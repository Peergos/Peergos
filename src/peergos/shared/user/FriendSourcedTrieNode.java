package peergos.shared.user;

import peergos.shared.*;
import peergos.shared.crypto.random.*;
import peergos.shared.user.fs.*;

import java.util.*;
import java.util.concurrent.*;

public class FriendSourcedTrieNode implements TrieNode {

    private final FileTreeNode sharedDir;
    private TrieNode root;
    private int capCount;

    public FriendSourcedTrieNode(FileTreeNode sharedDir, TrieNode root, int capCount) {
        this.sharedDir = sharedDir;
        this.root = root;
        this.capCount = capCount;
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
                            network, random, fragmenter, true).thenApply(caps -> {
                        return Optional.of(new FriendSourcedTrieNode(sharedDirOpt.get(), caps.stream()
                                .reduce(TrieNodeImpl.empty(),
                                        (root, cap) -> root.put(cap.path, UserContext.convert(e.owner, cap)),
                                        (a, b) -> a),
                                caps.size()));
                    });
                });
    }

    private CompletableFuture<Boolean> ensureUptodate() {
        // check there are no new capabilities in the friend's shared directory
        // TODO
        return CompletableFuture.completedFuture(true);
    }

    @Override
    public synchronized CompletableFuture<Optional<FileTreeNode>> getByPath(String path, NetworkAccess network) {
        return ensureUptodate().thenCompose(x -> root.getByPath(path, network));
    }

    @Override
    public synchronized CompletableFuture<Set<FileTreeNode>> getChildren(String path, NetworkAccess network) {
        return ensureUptodate().thenCompose(x -> root.getChildren(path, network));
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
