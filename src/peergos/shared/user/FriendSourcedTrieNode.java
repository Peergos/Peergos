package peergos.shared.user;

import peergos.shared.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.crypto.random.*;
import peergos.shared.user.fs.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

public class FriendSourcedTrieNode implements TrieNode {

    private final String ownerName;
    private final Supplier<CompletableFuture<FileWrapper>> homeDirSupplier;
    private final EntryPoint sharedDir;
    private final SafeRandom random;
    private final Hasher hasher;
    private TrieNode root;
    private long byteOffsetReadOnly;
    private long byteOffsetWrite;

    public FriendSourcedTrieNode(Supplier<CompletableFuture<FileWrapper>> homeDirSupplier,
                                 String ownerName,
                                 EntryPoint sharedDir,
                                 TrieNode root,
                                 long byteOffsetReadOnly,
                                 long byteOffsetWrite,
                                 SafeRandom random,
                                 Hasher hasher) {
        this.homeDirSupplier = homeDirSupplier;
        this.ownerName = ownerName;
        this.sharedDir = sharedDir;
        this.root = root;
        this.byteOffsetReadOnly = byteOffsetReadOnly;
        this.byteOffsetWrite = byteOffsetWrite;
        this.random = random;
        this.hasher = hasher;
    }

    public static CompletableFuture<Optional<FriendSourcedTrieNode>> build(Supplier<CompletableFuture<FileWrapper>> homeDirSupplier,
                                                                           EntryPoint e,
                                                                           NetworkAccess network,
                                                                           SafeRandom random,
                                                                           Hasher hasher) {
        return network.retrieveEntryPoint(e)
                .thenCompose(sharedDirOpt -> {
                    if (!sharedDirOpt.isPresent())
                        return CompletableFuture.completedFuture(Optional.empty());
                    return CapabilityStore.loadReadAccessSharingLinks(homeDirSupplier, sharedDirOpt.get(), e.ownerName,
                            network, random, hasher, true)
                            .thenCompose(readCaps -> {
                                return CapabilityStore.loadWriteAccessSharingLinks(homeDirSupplier, sharedDirOpt.get(), e.ownerName,
                                        network, random, hasher, true)
                                        .thenApply(writeCaps -> {
                                            List<CapabilityWithPath> allCaps = new ArrayList<>();
                                            allCaps.addAll(readCaps.getRetrievedCapabilities());
                                            allCaps.addAll(writeCaps.getRetrievedCapabilities());
                                            return Optional.of(new FriendSourcedTrieNode(homeDirSupplier,
                                                    e.ownerName,
                                                    e,
                                                    allCaps.stream()
                                                            .reduce(TrieNodeImpl.empty(),
                                                                    (root, cap) -> root.put(trimOwner(cap.path), new EntryPoint(cap.cap, e.ownerName)),
                                                                    (a, b) -> a),
                                                    readCaps.getBytesRead(), writeCaps.getBytesRead(), random, hasher));
                                        });
                            });
                });
    }

    private synchronized CompletableFuture<Boolean> ensureUptodate(NetworkAccess network) {
        // check there are no new capabilities in the friend's shared directory
        return network.retrieveEntryPoint(sharedDir)
                .thenCompose(sharedDirOpt -> {
                    if (!sharedDirOpt.isPresent())
                        return CompletableFuture.completedFuture(true);
                    return CapabilityStore.getReadOnlyCapabilityFileSize(sharedDirOpt.get(), network)
                            .thenCompose(bytes -> {
                                if (bytes == byteOffsetReadOnly) {
                                    return addEditableCapabilities(sharedDirOpt, network);
                                } else {
                                    return CapabilityStore.loadReadAccessSharingLinksFromIndex(homeDirSupplier, sharedDirOpt.get(),
                                            ownerName, network, random, hasher, byteOffsetReadOnly, true)
                                            .thenCompose(newReadCaps -> {
                                                byteOffsetReadOnly += newReadCaps.getBytesRead();
                                                root = newReadCaps.getRetrievedCapabilities().stream()
                                                        .reduce(root,
                                                                (root, cap) -> root.put(trimOwner(cap.path), new EntryPoint(cap.cap, ownerName)),
                                                                (a, b) -> a);
                                                return addEditableCapabilities(sharedDirOpt, network);
                                            });
                                }
                            });
                });
    }

    private synchronized CompletableFuture<Boolean> addEditableCapabilities(Optional<FileWrapper> sharedDirOpt, NetworkAccess network) {
        return CapabilityStore.getEditableCapabilityFileSize(sharedDirOpt.get(), network)
                .thenCompose(editCount -> {
                    if (editCount == byteOffsetWrite)
                        return CompletableFuture.completedFuture(true);
                    return CapabilityStore.loadWriteAccessSharingLinksFromIndex(homeDirSupplier, sharedDirOpt.get(),
                            ownerName, network, random, hasher, byteOffsetWrite, true)
                            .thenApply(newWriteCaps -> {
                                byteOffsetWrite += newWriteCaps.getBytesRead();
                                root = newWriteCaps.getRetrievedCapabilities().stream()
                                        .reduce(root,
                                                (root, cap) -> root.put(trimOwner(cap.path), new EntryPoint(cap.cap, ownerName)),
                                                (a, b) -> a);
                                return true;
                            });
                });
    }

    private CompletableFuture<Optional<FileWrapper>> getFriendRoot(NetworkAccess network) {
        return network.retrieveEntryPoint(sharedDir)
                .thenCompose(sharedDirOpt -> {
                    if (! sharedDirOpt.isPresent())
                        return CompletableFuture.completedFuture(Optional.empty());
                    return sharedDirOpt.get().retrieveParent(network)
                            .thenCompose(sharedOpt -> {
                                if (! sharedOpt.isPresent()) {
                                    CompletableFuture<Optional<FileWrapper>> empty = CompletableFuture.completedFuture(Optional.empty());
                                    return empty;
                                }
                                return sharedOpt.get().retrieveParent(network);
                            });
                });
    }

    private static String trimOwner(String path) {
        path = TrieNode.canonicalise(path);
        return path.substring(path.indexOf("/") + 1);
    }

    @Override
    public synchronized CompletableFuture<Optional<FileWrapper>> getByPath(String path, NetworkAccess network) {
        if (path.isEmpty() || path.equals("/"))
            return getFriendRoot(network)
                    .thenApply(opt -> opt.map(f -> f.withTrieNode(this)));
        return ensureUptodate(network).thenCompose(x -> root.getByPath(path, network));
    }

    @Override
    public synchronized CompletableFuture<Set<FileWrapper>> getChildren(String path, NetworkAccess network) {
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
    public synchronized TrieNode putNode(String path, TrieNode t) {
        root = root.putNode(path, t);
        return this;
    }

    @Override
    public synchronized TrieNode removeEntry(String path) {
        root = root.removeEntry(path);
        return this;
    }

    @Override
    public boolean isEmpty() {
        return root.isEmpty();
    }
}
