package peergos.shared.user;

import peergos.shared.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.inode.*;
import peergos.shared.social.*;
import peergos.shared.user.fs.*;
import peergos.shared.util.*;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.stream.*;

/** A lookup from path to capabilities shared with you
 *
 *  To avoid reparsing the entire capability list at every login, new capabilities are retrieved along with their path
 *  and stored in a mirror directory tree that is your view of the world. This world mirror is rooted at
 *  /recipient_user/.capabilitycache/world/
 *  If user B shares /A/a-path/some-file.txt (from A) with C, then C will store the SharedItem in
 *  /C/.capabilitycache/world/A/a-path/
 *
 *  How many records and bytes we have processed from each friend (ProcessedCaps) is stored in
 *  /C/.capabilitycache/friend-name$incoming.cbor
 */
public class IncomingCapCache {
    private static final String WORLD_ROOT_NAME = "world";
    private static final String FRIEND_STATE_SUFFIX = "$incoming.cbor";
    private static final String DIR_STATE = "items.cbor";

    private FileWrapper cacheRoot, worldRoot;
    private final Crypto crypto;
    private final Hasher hasher;

    public IncomingCapCache(FileWrapper cacheRoot, FileWrapper worldRoot, Crypto crypto) {
        this.cacheRoot = cacheRoot;
        this.worldRoot = worldRoot;
        this.crypto = crypto;
        this.hasher = crypto.hasher;
    }

    public static CompletableFuture<IncomingCapCache> build(FileWrapper cacheRoot, Crypto crypto, NetworkAccess network) {
        return cacheRoot.getOrMkdirs(Paths.get(WORLD_ROOT_NAME), network, true, crypto)
                .thenApply(worldRoot -> new IncomingCapCache(cacheRoot, worldRoot, crypto));
    }

    public static class ChildElement implements Cborable {
        public final PathElement name;
        public final AbsoluteCapability cap;
        public final List<String> sharers;

        public ChildElement(PathElement name, AbsoluteCapability cap, List<String> sharers) {
            this.name = name;
            this.cap = cap;
            this.sharers = sharers;
        }

        @Override
        public CborObject toCbor() {
            SortedMap<String, Cborable> state = new TreeMap<>();
            state.put("n", new CborObject.CborString(name.name));
            state.put("c", cap);
            state.put("s", new CborObject.CborList(sharers.stream()
                    .map(CborObject.CborString::new)
                    .collect(Collectors.toList())));
            return CborObject.CborMap.build(state);
        }

        public static ChildElement fromCbor(Cborable cbor) {
            if (!(cbor instanceof CborObject.CborMap))
                throw new IllegalStateException("Invalid cbor! " + cbor);
            CborObject.CborMap m = (CborObject.CborMap) cbor;

            String name = m.getString("n");
            AbsoluteCapability cap = m.getObject("c", AbsoluteCapability::fromCbor);
            List<String> sharers = m.getList("s", n -> ((CborObject.CborString)n).value);
            return new ChildElement(new PathElement(name), cap, sharers);
        }
    }

    public static class CapsInDirectory implements Cborable {
        public final List<ChildElement> children;

        public CapsInDirectory(List<ChildElement> children) {
            this.children = children;
        }

        public CompletableFuture<CapsInDirectory> addChild(String filename,
                                                           AbsoluteCapability cap,
                                                           String sharer,
                                                           String owner,
                                                           NetworkAccess network) {
            Optional<ChildElement> existing = children.stream()
                    .filter(c -> c.name.name.equals(filename))
                    .findFirst();
            PathElement name = new PathElement(filename);
            if (existing.isPresent()) {
                ChildElement current = existing.get();
                List<ChildElement> remainder = children.stream()
                        .filter(c -> !c.name.name.equals(filename))
                        .collect(Collectors.toList());
                if (current.cap.equals(cap)) {
                    List<String> combinedSharers = Stream.concat(existing.get().sharers.stream(), Stream.of(sharer))
                            .collect(Collectors.toList());
                    ChildElement updatedChild = new ChildElement(name, cap, combinedSharers);
                    return Futures.of(new CapsInDirectory(Stream.concat(remainder.stream(),
                            Stream.of(updatedChild))
                            .collect(Collectors.toList())));
                }
                if (current.sharers.equals(Arrays.asList(sharer))) {
                    ChildElement updatedChild = new ChildElement(name, cap, current.sharers);
                    return Futures.of(new CapsInDirectory(Stream.concat(remainder.stream(),
                            Stream.of(updatedChild))
                            .collect(Collectors.toList())));
                }
                // need to find highest privilege cap that is still valid
                // the cap could have been rotated, downgraded, or upgraded, or a friend shared a less privileged cap
                return network.retrieveEntryPoint(new EntryPoint(current.cap, owner))
                        .thenApply(oldOpt -> {
                            if (oldOpt.isPresent() && current.cap.isWritable() && !cap.isWritable()) {
                                // use the old cap
                                return this;
                            }
                            ChildElement updatedChild = new ChildElement(name, cap, Collections.singletonList(sharer));
                            return new CapsInDirectory(Stream.concat(remainder.stream(),
                                    Stream.of(updatedChild))
                                    .collect(Collectors.toList()));
                        });
            } else {
                ChildElement newChild = new ChildElement(name, cap, Collections.singletonList(sharer));
                return Futures.of(new CapsInDirectory(Stream.concat(children.stream(), Stream.of(newChild))
                        .collect(Collectors.toList())));
            }
        }

        public Optional<AbsoluteCapability> getChild(String name) {
            return children.stream()
                    .filter(c -> c.name.name.equals(name))
                    .map(c -> c.cap)
                    .findFirst();
        }

        public Set<AbsoluteCapability> getChildren() {
            return children.stream()
                    .map(c -> c.cap)
                    .collect(Collectors.toSet());
        }

        public Set<String> getChildNames() {
            return children.stream()
                    .map(c -> c.name.name)
                    .collect(Collectors.toSet());
        }

        public static CapsInDirectory empty() {
            return new CapsInDirectory(Collections.emptyList());
        }

        public static CapsInDirectory of(String filename, AbsoluteCapability cap, String sharer) {
            return new CapsInDirectory(Collections.singletonList(new ChildElement(new PathElement(filename), cap, Collections.singletonList(sharer))));
        }

        @Override
        public CborObject toCbor() {
            SortedMap<String, Cborable> state = new TreeMap<>();
            state.put("c", new CborObject.CborList(children));
            return CborObject.CborMap.build(state);
        }

        public static CapsInDirectory fromCbor(Cborable cbor) {
            if (!(cbor instanceof CborObject.CborMap))
                throw new IllegalStateException("Invalid cbor! " + cbor);
            CborObject.CborMap m = (CborObject.CborMap) cbor;

            List<ChildElement> children = m.getList("c", ChildElement::fromCbor);
            return new CapsInDirectory(children);
        }
    }

    public CompletableFuture<Optional<FileWrapper>> getByPath(Path file,
                                                              Hasher hasher,
                                                              NetworkAccess network) {
        String finalPath = TrieNode.canonicalise(file.toString());
        List<String> elements = Arrays.asList(finalPath.split("/"));
        return worldRoot.getDescendentByPath(elements.get(0), hasher, network)
                .thenCompose(dirOpt -> {
                    if (dirOpt.isEmpty())
                        return Futures.of(Optional.empty());
                    return getByPath(dirOpt.get(), elements, 1, network);
                });
    }

    private static String pathSuffix(List<String> path, int from) {
        if (from >= path.size())
            return "";
        return path.subList(from, path.size())
                .stream()
                .collect(Collectors.joining("/"));
    }

    private CompletableFuture<Optional<FileWrapper>> getByPath(FileWrapper mirrorDir,
                                                               List<String> path,
                                                               int childIndex,
                                                               NetworkAccess network) {
        Supplier<CompletableFuture<Optional<FileWrapper>>> recurse =
                () -> mirrorDir.getChild(path.get(childIndex), hasher, network)
                        .thenCompose(childOpt -> childOpt.map(c ->
                                getByPath(c, path, childIndex + 1, network))
                                .orElseGet(() -> Futures.of(Optional.empty())));
        if (childIndex >= path.size())
            return getAnyValidParentOfAChild(mirrorDir, hasher, network);
        return mirrorDir.getChild(DIR_STATE, hasher, network)
                .thenCompose(capsOpt -> {
                    if (capsOpt.isEmpty())
                        return recurse.get();
                    return Serialize.readFully(capsOpt.get(), crypto, network)
                            .thenApply(CborObject::fromByteArray)
                            .thenApply(CapsInDirectory::fromCbor)
                            .thenCompose(caps -> caps.getChild(path.get(childIndex))
                                    .map(cap -> network.retrieveEntryPoint(new EntryPoint(cap, path.get(0)))
                                            .thenCompose(fopt -> fopt.isPresent() && fopt.get().isWritable() ?
                                                    fopt.get().getAnyLinkPointer(network)
                                                            .thenApply(linkOpt -> fopt.map(g -> g.withLinkPointer(linkOpt))) :
                                                    Futures.of(fopt))
                                            .thenCompose(fopt -> fopt.map(f ->
                                                    f.getDescendentByPath(pathSuffix(path, childIndex + 1), hasher, network))
                                                    .orElse(Futures.of(Optional.empty()))))
                                    .orElseGet(recurse::get));
                });
    }

    private CompletableFuture<CapsInDirectory> getCaps(FileWrapper dir, Snapshot version, NetworkAccess network) {
        return dir.getChild(version, DIR_STATE, hasher, network)
                .thenCompose(capsOpt -> {
                    if (capsOpt.isEmpty())
                        return Futures.of(CapsInDirectory.empty());
                    return Serialize.readFully(capsOpt.get(), crypto, network)
                            .thenApply(CborObject::fromByteArray)
                            .thenApply(CapsInDirectory::fromCbor);
                });
    }

    private CompletableFuture<Optional<FileWrapper>> getAnyValidParentOfAChild(FileWrapper dir,
                                                                              Hasher hasher,
                                                                              NetworkAccess network) {
        return dir.getChild(DIR_STATE, hasher, network)
                .thenCompose(capsOpt -> {
                    String ownerName = dir.getOwnerName();
                    if (capsOpt.isPresent())
                        return Serialize.readFully(capsOpt.get(), crypto, network)
                                .thenApply(CborObject::fromByteArray)
                                .thenApply(CapsInDirectory::fromCbor)
                                .thenCompose(caps -> network.retrieveEntryPoint(new EntryPoint(caps.children.stream()
                                        .findFirst().get().cap, ownerName)))
                                .thenCompose(fileOpt -> fileOpt.map(f -> f.retrieveParent(network))
                                        .orElse(Futures.of(Optional.empty())));
                    return dir.getChildren(hasher, network)
                            .thenCompose(children -> Futures.findFirst(children,
                                    c -> getAnyValidParentOfAChild(c, hasher, network)))
                            .thenCompose(copt -> copt.map(f -> f.retrieveParent(network))
                                    .orElse(Futures.of(Optional.empty())));
                });
    }

    private CompletableFuture<Set<FileWrapper>> getIndirectChildren(FileWrapper mirrorDir,
                                                                    Set<String> toExclude,
                                                                    Hasher hasher,
                                                                    NetworkAccess network) {
        return mirrorDir.getChildren(hasher, network)
                .thenApply(children -> children.stream()
                        .filter(c -> ! toExclude.contains(c.getName()) && ! c.getName().equals(DIR_STATE))
                        .collect(Collectors.toSet()))
                .thenCompose(remainingDirs -> Futures.combineAll(remainingDirs.stream()
                        .map(child -> getAnyValidParentOfAChild(child, hasher, network))
                        .collect(Collectors.toList())))
                .thenApply(res -> res.stream()
                        .flatMap(Optional::stream)
                        .collect(Collectors.toSet()));
    }

    public CompletableFuture<Set<FileWrapper>> getChildren(Path dir,
                                                           Snapshot version,
                                                           Hasher hasher,
                                                           NetworkAccess network) {
        String finalPath = TrieNode.canonicalise(dir.toString());
        List<String> elements = Arrays.asList(finalPath.split("/"));
        return worldRoot.getDescendentByPath(elements.get(0), version, hasher, network)
                .thenCompose(dirOpt -> {
                    if (dirOpt.isEmpty())
                        return Futures.of(Collections.emptySet());
                    return getChildren(dirOpt.get(), elements, 1, version, hasher, network);
                });
    }

    private CompletableFuture<Set<FileWrapper>> getChildren(FileWrapper mirrorDir,
                                                            List<String> path,
                                                            int childIndex,
                                                            Snapshot version,
                                                            Hasher hasher,
                                                            NetworkAccess network) {
        Supplier<CompletableFuture<Set<FileWrapper>>> recurse =
                () -> mirrorDir.getChild(version, path.get(childIndex), hasher, network)
                        .thenCompose(childOpt -> childOpt.map(c ->
                                getChildren(c, path, childIndex + 1, version, hasher, network))
                                .orElseGet(() -> Futures.of(Collections.emptySet())));
        if (childIndex == path.size())
            return getCaps(mirrorDir, version, network)
                    .thenCompose(caps -> Futures.combineAll(caps.getChildren().stream()
                            .map(cap -> network.retrieveEntryPoint(new EntryPoint(cap, path.get(0))))
                            .collect(Collectors.toSet()))
                            .thenApply(kids -> kids.stream().flatMap(Optional::stream).collect(Collectors.toSet()))
                            .thenCompose(direct -> getIndirectChildren(mirrorDir,
                                    direct.stream()
                                            .map(FileWrapper::getName)
                                            .collect(Collectors.toSet()), hasher, network)
                                    .thenApply(indirectChildren -> Stream.concat(direct.stream(), indirectChildren.stream())
                                            .collect(Collectors.toSet()))));

        return mirrorDir.getChild(version, DIR_STATE, hasher, network)
                .thenCompose(capsOpt -> {
                    if (capsOpt.isEmpty())
                        return recurse.get();
                    return Serialize.readFully(capsOpt.get(), crypto, network)
                            .thenApply(CborObject::fromByteArray)
                            .thenApply(CapsInDirectory::fromCbor)
                            .thenCompose(caps -> caps.getChild(path.get(childIndex))
                                    .map(cap -> network.getFile(new EntryPoint(cap, path.get(0)), version)
                                            .thenCompose(fopt -> fopt.map(f ->
                                                    f.getDescendentByPath(pathSuffix(path, childIndex + 1), version, hasher, network)
                                                            .thenCompose(dir -> dir.map(d -> d.getChildren(hasher, network))
                                                                    .orElse(Futures.of(Collections.emptySet()))))
                                                    .orElse(Futures.of(Collections.emptySet()))))
                                    .orElseGet(recurse::get));
                });
    }

    public Snapshot getVersion() {
        return worldRoot.version;
    }

    public CompletableFuture<CapsDiff> ensureFriendUptodate(String friend, EntryPoint sharedDir, NetworkAccess network) {
        return getAndUpdateRoot(network)
                .thenCompose(root -> root.getDescendentByPath(friend + FRIEND_STATE_SUFFIX, hasher, network)
                        .thenCompose(stateOpt -> {
                            if (stateOpt.isEmpty())
                                return Futures.of(ProcessedCaps.empty());
                            return Serialize.readFully(stateOpt.get(), crypto, network)
                                    .thenApply(arr -> ProcessedCaps.fromCbor(CborObject.fromByteArray(arr)));
                        }))
                .thenCompose(currentState -> ensureUptodate(friend, sharedDir, currentState, crypto, network));
    }

    private synchronized CompletableFuture<CapsDiff> ensureUptodate(String friend,
                                                                   EntryPoint originalSharedDir,
                                                                   ProcessedCaps current,
                                                                   Crypto crypto,
                                                                   NetworkAccess network) {
        // check there are no new capabilities in the friend's shared directory
        return NetworkAccess.getLatestEntryPoint(originalSharedDir, network)
                .thenCompose(sharedDir -> CapabilityStore.getReadOnlyCapabilityFileSize(sharedDir.file, crypto, network)
                        .thenCompose(bytes -> {
                            if (bytes == current.readCapBytes) {
                                return getNewWritableCaps(Optional.of(sharedDir.file), current.writeCapBytes, crypto, network)
                                        .thenApply(w -> new FriendSourcedTrieNode.ReadAndWriteCaps(CapabilitiesFromUser.empty(), w));
                            } else {
                                return CapabilityStore.loadReadAccessSharingLinksFromIndex(null, sharedDir.file,
                                        null, network, crypto, current.readCapBytes, false, true)
                                        .thenCompose(newReadCaps -> getNewWritableCaps(Optional.of(sharedDir.file),
                                                current.writeCapBytes, crypto, network)
                                                .thenApply(writeable -> new FriendSourcedTrieNode.ReadAndWriteCaps(newReadCaps, writeable))
                                        );
                            }
                        }))
                .thenApply(newCaps -> new CapsDiff(current.readCapBytes, current.writeCapBytes, newCaps))
                .thenCompose(diff -> addNewCapsToMirror(friend, current, diff, network))
                .thenCompose(diff -> getAndUpdateWorldRoot(network)
                        .thenApply(y -> diff));
    }

    private synchronized CompletableFuture<CapabilitiesFromUser> getNewWritableCaps(Optional<FileWrapper> sharedDirOpt,
                                                                                    long byteOffsetWrite,
                                                                                    Crypto crypto,
                                                                                    NetworkAccess network) {
        return CapabilityStore.getEditableCapabilityFileSize(sharedDirOpt.get(), crypto, network)
                .thenCompose(editFilesize -> {
                    if (editFilesize == byteOffsetWrite)
                        return CompletableFuture.completedFuture(CapabilitiesFromUser.empty());
                    return CapabilityStore.loadWriteAccessSharingLinksFromIndex(null, sharedDirOpt.get(),
                            null, network, crypto, byteOffsetWrite, false, true);
                });
    }

    private CompletableFuture<CapsDiff> addNewCapsToMirror(String friend,
                                                           ProcessedCaps current,
                                                           CapsDiff diff,
                                                           NetworkAccess network) {
        List<CapabilityWithPath> readCaps = diff.newCaps.readCaps.getRetrievedCapabilities();
        List<CapabilityWithPath> writeCaps = diff.newCaps.writeCaps.getRetrievedCapabilities();
        List<CapabilityWithPath> all = Stream.concat(readCaps.stream(), writeCaps.stream())
                .collect(Collectors.toList());

        // Add all new caps to mirror tree
        return Futures.reduceAll(all, worldRoot, (r, c) -> addCapToMirror(friend, r, c, crypto, network), (a, b) -> b)
                .thenCompose(updatedWorldRoot -> {
                    this.worldRoot = updatedWorldRoot;
                    // Commit our position in the friend's cap stream
                    ProcessedCaps updated = current.add(diff);
                    byte[] raw = updated.serialize();
                    AsyncReader reader = AsyncReader.build(raw);
                    return getAndUpdateRoot(network)
                            .thenCompose(root -> root.uploadOrReplaceFile(friend + FRIEND_STATE_SUFFIX, reader, raw.length,
                                    network, crypto, x -> {
                                    }, crypto.random.randomBytes(RelativeCapability.MAP_KEY_LENGTH)))
                            .thenApply(x -> diff);
                });
    }

    private static CompletableFuture<FileWrapper> addCapToMirror(String friend,
                                                                 FileWrapper root,
                                                                 CapabilityWithPath cap,
                                                                 Crypto crypto,
                                                                 NetworkAccess network) {
        Path fullPath = Paths.get(cap.path);
        Path parentPath = fullPath.getParent();
        String owner = fullPath.getName(0).toString();
        String filename = fullPath.getFileName().toString();
        return root.getOrMkdirs(parentPath, network, false, crypto)
                .thenCompose(parent -> parent.getChild(DIR_STATE, crypto.hasher, network)
                        .thenCompose(capsOpt -> {
                            if (capsOpt.isEmpty()) {
                                CapsInDirectory single = CapsInDirectory.of(filename, cap.cap, friend);
                                byte[] raw = single.serialize();
                                AsyncReader reader = AsyncReader.build(raw);
                                return parent.uploadOrReplaceFile(DIR_STATE, reader, raw.length, network, crypto,
                                        x -> {}, crypto.random.randomBytes(RelativeCapability.MAP_KEY_LENGTH));
                            }
                            return Serialize.readFully(capsOpt.get(), crypto, network)
                                    .thenApply(CborObject::fromByteArray)
                                    .thenApply(CapsInDirectory::fromCbor)
                                    .thenCompose(existing -> existing.addChild(filename, cap.cap, friend, owner, network))
                                    .thenCompose(updated -> {
                                        byte[] raw = updated.serialize();
                                        AsyncReader reader = AsyncReader.build(raw);
                                        return capsOpt.get().overwriteFile(reader, raw.length, network, crypto, x -> {});
                                    });
                        })
                ).thenCompose(x -> root.getUpdated(network));
    }

    private synchronized CompletableFuture<FileWrapper> getAndUpdateWorldRoot(NetworkAccess network) {
        return worldRoot.getUpdated(network).thenApply(updated -> {
            this.worldRoot = updated;
            return updated;
        });
    }

    private synchronized CompletableFuture<FileWrapper> getAndUpdateRoot(NetworkAccess network) {
        return cacheRoot.getUpdated(network).thenApply(updated -> {
            this.cacheRoot = updated;
            return updated;
        });
    }
}
