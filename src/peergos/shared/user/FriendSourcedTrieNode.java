package peergos.shared.user;

import peergos.shared.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.user.fs.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

public class FriendSourcedTrieNode implements TrieNode {

    public final String ownerName;
    private final FileWrapper cacheDir;
    private final EntryPoint sharedDir;
    private final Crypto crypto;
    private TrieNode root;
    private long byteOffsetReadOnly;
    private long byteOffsetWrite;

    public FriendSourcedTrieNode(FileWrapper cacheDir,
                                 String ownerName,
                                 EntryPoint sharedDir,
                                 TrieNode root,
                                 long byteOffsetReadOnly,
                                 long byteOffsetWrite,
                                 Crypto crypto) {
        this.cacheDir = cacheDir;
        this.ownerName = ownerName;
        this.sharedDir = sharedDir;
        this.root = root;
        this.byteOffsetReadOnly = byteOffsetReadOnly;
        this.byteOffsetWrite = byteOffsetWrite;
        this.crypto = crypto;
    }

    public static CompletableFuture<Optional<FriendSourcedTrieNode>> build(FileWrapper cacheDir,
                                                                           EntryPoint e,
                                                                           NetworkAccess network,
                                                                           Crypto crypto) {
        return loadCachedCaps(cacheDir, e, network, crypto)
                .thenApply(res -> Optional.of(new FriendSourcedTrieNode(cacheDir,
                        e.ownerName,
                        e,
                        Stream.of(res.readCaps, res.writeCaps)
                                .flatMap(c -> c.getRetrievedCapabilities().stream())
                                .reduce(TrieNodeImpl.empty(),
                                        (root, cap) -> root.put(trimOwner(cap.path), new EntryPoint(cap.cap, e.ownerName)),
                                        (a, b) -> a),
                        res.readCaps.getBytesRead(), res.writeCaps.getBytesRead(), crypto)));
    }

    public CompletableFuture<ReadAndWriteCaps> loadCachedCaps(NetworkAccess network, Crypto crypto) {
        return loadCachedCaps(cacheDir, sharedDir, network, crypto);
    }

    /**
     *
     * @param cacheDir
     * @param e
     * @param network
     * @param crypto
     * @return the read and write caps cached from this user
     */
    public static CompletableFuture<ReadAndWriteCaps> loadCachedCaps(FileWrapper cacheDir,
                                                                     EntryPoint e,
                                                                     NetworkAccess network,
                                                                     Crypto crypto) {
        return CapabilityStore.loadCachedReadOnlyLinks(cacheDir, e.ownerName, network, crypto)
                .thenCompose(readCaps -> CapabilityStore.loadCachedWriteableLinks(cacheDir, e.ownerName, network, crypto)
                        .thenApply(writeCaps -> new ReadAndWriteCaps(readCaps, writeCaps)));
    }

    public static CompletableFuture<Optional<FriendSourcedTrieNode>> buildAndUpdate(FileWrapper cacheDir,
                                                                                    EntryPoint e,
                                                                                    NetworkAccess network,
                                                                                    Crypto crypto) {
        return network.retrieveEntryPoint(e)
                .thenCompose(sharedDirOpt -> {
                    if (!sharedDirOpt.isPresent())
                        return CompletableFuture.completedFuture(Optional.empty());
                    return CapabilityStore.loadReadOnlyLinks(cacheDir, sharedDirOpt.get(), e.ownerName,
                            network, crypto, true, true)
                            .thenCompose(readCaps -> {
                                return CapabilityStore.loadWriteableLinks(cacheDir, sharedDirOpt.get(), e.ownerName,
                                        network, crypto, true, true)
                                        .thenApply(writeCaps -> {
                                            List<CapabilityWithPath> allCaps = new ArrayList<>();
                                            allCaps.addAll(readCaps.getRetrievedCapabilities());
                                            allCaps.addAll(writeCaps.getRetrievedCapabilities());
                                            return Optional.of(new FriendSourcedTrieNode(cacheDir,
                                                    e.ownerName,
                                                    e,
                                                    allCaps.stream()
                                                            .reduce(TrieNodeImpl.empty(),
                                                                    (root, cap) -> root.put(trimOwner(cap.path), new EntryPoint(cap.cap, e.ownerName)),
                                                                    (a, b) -> a),
                                                    readCaps.getBytesRead(), writeCaps.getBytesRead(), crypto));
                                        });
                            });
                });
    }

    /**
     *
     * @param crypto
     * @param network
     * @return Any new capabilities from the friend and the previously processed size of caps in bytes
     */
    public synchronized CompletableFuture<CapsDiff> ensureUptodate(Crypto crypto,
                                                                           NetworkAccess network) {
        // check there are no new capabilities in the friend's shared directory
        return NetworkAccess.getLatestEntryPoint(sharedDir, network)
                .thenCompose(sharedDir -> {
                    return CapabilityStore.getReadOnlyCapabilityFileSize(sharedDir.file, crypto, network)
                            .thenCompose(bytes -> {
                                if (bytes == byteOffsetReadOnly) {
                                    return addEditableCapabilities(Optional.of(sharedDir.file), crypto, network)
                                            .thenApply(w -> new ReadAndWriteCaps(CapabilitiesFromUser.empty(), w));
                                } else {
                                    return CapabilityStore.loadReadAccessSharingLinksFromIndex(cacheDir, sharedDir.file,
                                            ownerName, network, crypto, byteOffsetReadOnly, true, true)
                                            .thenCompose(newReadCaps -> {
                                                byteOffsetReadOnly += newReadCaps.getBytesRead();
                                                root = newReadCaps.getRetrievedCapabilities().stream()
                                                        .reduce(root,
                                                                (root, cap) -> root.put(trimOwner(cap.path), new EntryPoint(cap.cap, ownerName)),
                                                                (a, b) -> a);
                                                return addEditableCapabilities(Optional.of(sharedDir.file), crypto, network)
                                                        .thenApply(writeable -> new ReadAndWriteCaps(newReadCaps, writeable));
                                            });
                                }
                            });
                }).thenApply(newCaps -> new CapsDiff(byteOffsetReadOnly - newCaps.readCaps.getBytesRead(),
                        byteOffsetWrite - newCaps.writeCaps.getBytesRead(), newCaps));
    }

    private synchronized CompletableFuture<CapabilitiesFromUser> addEditableCapabilities(Optional<FileWrapper> sharedDirOpt,
                                                                            Crypto crypto,
                                                                            NetworkAccess network) {
        return CapabilityStore.getEditableCapabilityFileSize(sharedDirOpt.get(), crypto, network)
                .thenCompose(editFilesize -> {
                    if (editFilesize == byteOffsetWrite)
                        return CompletableFuture.completedFuture(CapabilitiesFromUser.empty());
                    return CapabilityStore.loadWriteAccessSharingLinksFromIndex(cacheDir, sharedDirOpt.get(),
                            ownerName, network, crypto, byteOffsetWrite, true, true)
                            .thenApply(newWriteCaps -> {
                                byteOffsetWrite += newWriteCaps.getBytesRead();
                                root = newWriteCaps.getRetrievedCapabilities().stream()
                                        .reduce(root,
                                                (root, cap) -> root.put(trimOwner(cap.path), new EntryPoint(cap.cap, ownerName)),
                                                (a, b) -> a);
                                return newWriteCaps;
                            });
                });
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

    private static String trimOwner(String path) {
        path = TrieNode.canonicalise(path);
        return path.substring(path.indexOf("/") + 1);
    }

    @Override
    public synchronized CompletableFuture<Optional<FileWrapper>> getByPath(String path, Hasher hasher, NetworkAccess network) {
        FileProperties.ensureValidPath(path);
        if (path.isEmpty() || path.equals("/"))
            return getFriendRoot(network)
                    .thenApply(opt -> opt.map(f -> f.withTrieNode(this)));
        return ensureUptodate(crypto, network).thenCompose(x -> root.getByPath(path, hasher, network));
    }

    @Override
    public synchronized CompletableFuture<Optional<FileWrapper>> getByPath(String path, Snapshot version, Hasher hasher, NetworkAccess network) {
        FileProperties.ensureValidPath(path);
        if (path.isEmpty() || path.equals("/"))
            return getFriendRoot(network)
                    .thenApply(opt -> opt.map(f -> f.withTrieNode(this)));
        return ensureUptodate(crypto, network).thenCompose(x -> root.getByPath(path, version, hasher, network));
    }

    @Override
    public synchronized CompletableFuture<Set<FileWrapper>> getChildren(String path, Hasher hasher, NetworkAccess network) {
        FileProperties.ensureValidPath(path);
        return ensureUptodate(crypto, network)
                .thenCompose(x -> root.getChildren(path, hasher, network));
    }

    @Override
    public synchronized CompletableFuture<Set<FileWrapper>> getChildren(String path, Hasher hasher, Snapshot version, NetworkAccess network) {
        FileProperties.ensureValidPath(path);
        return root.getChildren(path, hasher, version, network);
    }

    @Override
    public synchronized Set<String> getChildNames() {
        return root.getChildNames();
    }

    @Override
    public synchronized TrieNode put(String path, EntryPoint e) {
        FileProperties.ensureValidPath(path);
        root = root.put(path, e);
        return this;
    }

    @Override
    public synchronized TrieNode putNode(String path, TrieNode t) {
        FileProperties.ensureValidPath(path);
        root = root.putNode(path, t);
        return this;
    }

    @Override
    public synchronized TrieNode removeEntry(String path) {
        root = root.removeEntry(path);
        return this;
    }

    @Override
    public Collection<TrieNode> getChildNodes() {
        return root.getChildNodes();
    }

    @Override
    public boolean isEmpty() {
        return root.isEmpty();
    }

    public static class CapsDiff {
        public final long priorReadByteOffset, priorWriteByteOffset;
        public final ReadAndWriteCaps newCaps;

        public CapsDiff(long priorReadByteOffset, long priorWriteByteOffset, ReadAndWriteCaps newCaps) {
            this.priorReadByteOffset = priorReadByteOffset;
            this.priorWriteByteOffset = priorWriteByteOffset;
            this.newCaps = newCaps;
        }

        public boolean isEmpty() {
            return newCaps.readCaps.getBytesRead() == 0 && newCaps.writeCaps.getBytesRead() == 0;
        }

        public long updatedReadBytes() {
            return priorReadByteOffset + newCaps.readCaps.getBytesRead();
        }

        public long updatedWriteBytes() {
            return priorWriteByteOffset + newCaps.writeCaps.getBytesRead();
        }

        public long priorBytes() {
            return priorReadByteOffset + priorWriteByteOffset;
        }
    }

    public static class ReadAndWriteCaps {
        public final CapabilitiesFromUser readCaps, writeCaps;

        public ReadAndWriteCaps(CapabilitiesFromUser readCaps, CapabilitiesFromUser writeCaps) {
            this.readCaps = readCaps;
            this.writeCaps = writeCaps;
        }
    }
}
