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
    private final Map<PublicKeyHash, Pair<byte[], CapsDiff>> pointerCache;
    private final Crypto crypto;
    private final Hasher hasher;

    public IncomingCapCache(FileWrapper cacheRoot, FileWrapper worldRoot, Crypto crypto) {
        this.cacheRoot = cacheRoot;
        this.worldRoot = worldRoot;
        this.crypto = crypto;
        this.hasher = crypto.hasher;
        this.pointerCache = new HashMap<>();
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
                if (current.sharers.equals(Arrays.asList(sharer)) && ! current.cap.isWritable()) {
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
                                                              Snapshot version,
                                                              Hasher hasher,
                                                              NetworkAccess network) {
        String finalPath = TrieNode.canonicalise(file.toString());
        List<String> elements = Arrays.asList(finalPath.split("/"));
        Snapshot union = worldRoot.version.mergeAndOverwriteWith(version);
        return worldRoot.getDescendentByPath(elements.get(0), union, hasher, network)
                .thenCompose(dirOpt -> {
                    if (dirOpt.isEmpty())
                        return Futures.of(Optional.empty());
                    return getByPath(dirOpt.get(), elements, 1, union, network);
                });
    }

    private static String pathSuffix(List<String> path, int from) {
        if (from >= path.size())
            return "";
        return String.join("/", path.subList(from, path.size()));
    }

    private CompletableFuture<Optional<FileWrapper>> getByPath(FileWrapper mirrorDir,
                                                               List<String> path,
                                                               int childIndex,
                                                               Snapshot version,
                                                               NetworkAccess network) {
        Supplier<CompletableFuture<Optional<FileWrapper>>> recurse =
                () -> mirrorDir.getChild(version, path.get(childIndex), hasher, network)
                        .thenCompose(childOpt -> childOpt.map(c ->
                                getByPath(c, path, childIndex + 1, version, network))
                                .orElseGet(() -> Futures.of(Optional.empty())));
        if (childIndex >= path.size())
            return getAnyValidParentOfAChild(mirrorDir, hasher, network);
        return mirrorDir.getChild(version, DIR_STATE, hasher, network)
                .thenCompose(capsOpt -> {
                    if (capsOpt.isEmpty())
                        return recurse.get();
                    return Serialize.readFully(capsOpt.get(), crypto, network)
                            .thenApply(CborObject::fromByteArray)
                            .thenApply(CapsInDirectory::fromCbor)
                            .thenCompose(caps -> caps.getChild(path.get(childIndex))
                                    .map(cap -> network.getFile(new EntryPoint(cap, path.get(0)), version)
                                            .thenCompose(fopt -> {
                                                Function<Optional<FileWrapper>, CompletableFuture<Optional<FileWrapper>>> getDescendant =
                                                        dir -> dir.map(f ->
                                                                f.getDescendentByPath(pathSuffix(path, childIndex + 1), f.version, hasher, network))
                                                                .orElseGet(recurse);

                                                if (fopt.isPresent()) {
                                                    if (fopt.get().isWritable())
                                                        return fopt.get().getAnyLinkPointer(network)
                                                                .thenApply(linkOpt -> fopt.map(g -> g.withLinkPointer(linkOpt)))
                                                                .thenCompose(getDescendant);

                                                    // there might be a descendant that is writable, though they might be
                                                    // descendant caps that have since been revoked
                                                    return mirrorDir.hasChild(path.get(childIndex), hasher, network)
                                                            .thenCompose(hasDescendantCaps -> hasDescendantCaps ?
                                                                    recurse.get()
                                                                            .thenCompose(dopt ->
                                                                                    dopt.map(res -> Futures.of(dopt))
                                                                                            .orElseGet(() -> getDescendant.apply(fopt))) :
                                                                    getDescendant.apply(fopt));
                                                }
                                                return recurse.get();
                                            }))
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
        Supplier<CompletableFuture<Optional<FileWrapper>>> recurse =
                () -> dir.getChildren(hasher, network)
                        .thenCompose(children -> Futures.findFirst(children,
                                c -> getAnyValidParentOfAChild(c, hasher, network)))
                        .thenCompose(copt -> copt.map(f -> f.retrieveParent(network))
                                .orElse(Futures.of(Optional.empty())));

        return dir.getChild(DIR_STATE, hasher, network)
                .thenCompose(capsOpt -> {
                    String ownerName = dir.getOwnerName();
                    if (capsOpt.isPresent())
                        return Serialize.readFully(capsOpt.get(), crypto, network)
                                .thenApply(CborObject::fromByteArray)
                                .thenApply(CapsInDirectory::fromCbor)
                                .thenCompose(caps -> Futures.findFirst(caps.children,
                                        c -> network.retrieveEntryPoint(new EntryPoint(c.cap, ownerName))))
                                .thenCompose(fileOpt -> Futures.asyncExceptionally(() -> fileOpt.map(f -> f.retrieveParent(network))
                                        .orElseGet(recurse), t -> recurse.get()));
                    return recurse.get();
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
        Snapshot union = worldRoot.version.mergeAndOverwriteWith(version);
        return worldRoot.getDescendentByPath(elements.get(0), union, hasher, network)
                .thenCompose(dirOpt -> {
                    if (dirOpt.isEmpty())
                        return Futures.of(Collections.emptySet());
                    return getChildren(dirOpt.get(), elements, 1, union, hasher, network);
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
                                                    f.getDescendentByPath(pathSuffix(path, childIndex + 1), version.mergeAndOverwriteWith(f.version), hasher, network)
                                                            .thenCompose(dir -> dir.map(d -> d.getChildren(hasher, network))
                                                                    .orElse(Futures.of(Collections.emptySet()))))
                                                    .orElseGet(recurse)))
                                    .orElseGet(recurse));
                });
    }

    public Snapshot getVersion() {
        return worldRoot.version;
    }

    public synchronized CompletableFuture<CapsDiff> ensureFriendUptodate(String friend,
                                                                         EntryPoint sharedDir,
                                                                         List<EntryPoint> groups,
                                                                         NetworkAccess network) {
        // if the friend's mutable pointer hasn't changed since our last update we can short circuit early
        PublicKeyHash owner = sharedDir.pointer.owner;
        PublicKeyHash writer = sharedDir.pointer.writer;
        return network.mutable.getPointer(owner, writer)
                .thenCompose(latestPointer -> {
                    if (latestPointer.isEmpty())
                        throw new IllegalStateException("Couldn't get pointer for directory of " + friend);
                    byte[] current = latestPointer.get();
                    Pair<byte[], CapsDiff> cached = pointerCache.get(writer);
                    boolean equal = cached != null && Arrays.equals(current, cached.left);
                    if (equal) {
                        if (cached.right.groupDiffs.size() == groups.size())
                            return Futures.of(cached.right);
                    }

                    return getAndUpdateRoot(network)
                            .thenCompose(root -> root.getDescendentByPath(friend + FRIEND_STATE_SUFFIX, hasher, network)
                                    .thenCompose(stateOpt -> {
                                        if (stateOpt.isEmpty())
                                            return Futures.of(ProcessedCaps.empty());
                                        return Serialize.readFully(stateOpt.get(), crypto, network)
                                                .thenApply(arr -> ProcessedCaps.fromCbor(CborObject.fromByteArray(arr)));
                                    }))
                            .thenCompose(currentState -> ensureUptodate(friend, sharedDir, groups, currentState, crypto, network))
                            .thenApply(res -> {
                                pointerCache.put(writer, new Pair<>(latestPointer.get(), res.flatten()));
                                return res;
                            });
                });
    }

    public CompletableFuture<CapsDiff> getCapsFrom(String friend,
                                                   EntryPoint originalSharedDir,
                                                   List<EntryPoint> groups,
                                                   ProcessedCaps current,
                                                   NetworkAccess network) {
        return retrieveNewCaps(originalSharedDir, current, network, crypto)
                .thenCompose(direct -> Futures.reduceAll(groups,
                        direct,
                        (d, e) -> NetworkAccess.getLatestEntryPoint(e, network)
                                .thenCompose(sharedDir -> retrieveNewCaps(e,
                                        current.groups.getOrDefault(sharedDir.file.getName(), ProcessedCaps.empty()), network, crypto)
                                        .thenApply(diff -> d.mergeGroups(current.createGroupDiff(sharedDir.file.getName(), diff))))
                                .exceptionally(t -> d),
                        (a, b) -> a.mergeGroups(b)));
    }

    private static CompletableFuture<CapsDiff> retrieveNewCaps(EntryPoint sharingDir,
                                                               ProcessedCaps current,
                                                               NetworkAccess network,
                                                               Crypto crypto) {
        return NetworkAccess.getLatestEntryPoint(sharingDir, network)
                .thenCompose(sharedDir ->retrieveNewCaps(sharedDir, current.readCapBytes, current.writeCapBytes, network, crypto))
                .exceptionally(t -> {
                    // we might have been removed from a group or similar
                    t.printStackTrace();
                    return CapsDiff.empty();
                });
    }

    private static CompletableFuture<CapsDiff> retrieveNewCaps(RetrievedEntryPoint sharedDir,
                                                               long readCapBytes,
                                                               long writeCapBytes,
                                                               NetworkAccess network,
                                                               Crypto crypto) {
        return CapabilityStore.loadReadAccessSharingLinksFromIndex(null, sharedDir.file,
                null, network, crypto, readCapBytes, false, true)
                .thenCompose(newReadCaps ->
                        getWritableCaps(sharedDir.file, writeCapBytes, crypto, network)
                                .thenApply(writeable ->
                                        new CapsDiff.ReadAndWriteCaps(newReadCaps, writeable)))
                .thenApply(newCaps -> new CapsDiff(readCapBytes, writeCapBytes, newCaps, Collections.emptyMap()));
    }

    private synchronized CompletableFuture<CapsDiff> ensureUptodate(String friend,
                                                                    EntryPoint originalSharedDir,
                                                                    List<EntryPoint> groups,
                                                                    ProcessedCaps current,
                                                                    Crypto crypto,
                                                                    NetworkAccess network) {
        System.out.println("***** ICC.ensureUptodate(" + groups.size());
        // check there are no new capabilities in the friend's shared directory, or any of their groups
        return getCapsFrom(friend, originalSharedDir, groups, current, network)
                .thenCompose(diff -> addNewCapsToMirror(friend, current, diff, network))
                .thenCompose(diff -> getAndUpdateWorldRoot(network)
                        .thenApply(y -> diff));
    }

    private static synchronized CompletableFuture<CapabilitiesFromUser> getWritableCaps(FileWrapper sharedDir,
                                                                                        long byteOffsetWrite,
                                                                                        Crypto crypto,
                                                                                        NetworkAccess network) {
        return CapabilityStore.getEditableCapabilityFileSize(sharedDir, crypto, network)
                .thenCompose(editFilesize -> {
                    if (editFilesize == byteOffsetWrite)
                        return CompletableFuture.completedFuture(CapabilitiesFromUser.empty());
                    return CapabilityStore.loadWriteAccessSharingLinksFromIndex(null, sharedDir,
                            null, network, crypto, byteOffsetWrite, false, true);
                });
    }

    private CompletableFuture<CapsDiff> addNewCapsToMirror(String friend,
                                                           ProcessedCaps current,
                                                           CapsDiff diff,
                                                           NetworkAccess network) {
        if (diff.isEmpty())
            return Futures.of(diff);

        List<CapabilityWithPath> all = diff.getNewCaps();
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
