package peergos.shared.user;

import peergos.shared.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.inode.*;
import peergos.shared.social.*;
import peergos.shared.storage.auth.*;
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
    private final Map<PublicKeyHash, Pair<MaybeMultihash, CapsDiff>> pointerCache;
    private final Crypto crypto;
    private final Hasher hasher;

    public IncomingCapCache(FileWrapper cacheRoot, FileWrapper worldRoot, Crypto crypto) {
        this.cacheRoot = cacheRoot;
        this.worldRoot = worldRoot;
        this.crypto = crypto;
        this.hasher = crypto.hasher;
        this.pointerCache = new HashMap<>();
    }

    public static CompletableFuture<IncomingCapCache> build(FileWrapper cacheRoot, Optional<BatId> mirrorBatId, Crypto crypto, NetworkAccess network) {
        return cacheRoot.getOrMkdirs(PathUtil.get(WORLD_ROOT_NAME), network, true, mirrorBatId, crypto)
                .thenApply(worldRoot -> new IncomingCapCache(cacheRoot, worldRoot, crypto));
    }

    public PublicKeyHash owner() {
        return worldRoot.owner();
    }

    public SigningPrivateKeyAndPublicHash signingPair() {
        return worldRoot.signingPair();
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
                () -> mirrorDir.getChild(version, path.get(childIndex), network)
                        .thenCompose(childOpt -> childOpt.map(c ->
                                getByPath(c, path, childIndex + 1, version, network))
                                .orElseGet(() -> Futures.of(Optional.empty())));
        if (childIndex >= path.size())
            return getAnyValidParentOfAChild(mirrorDir, hasher, network);
        return mirrorDir.getChild(version, DIR_STATE, network)
                .thenCompose(capsOpt -> {
                    if (capsOpt.isEmpty())
                        return recurse.get();
                    FileWrapper capsFile = capsOpt.get();
                    return capsFile.getInputStream(capsFile.version.get(capsFile.writer()).props.get(), network, crypto, x-> {})
                            .thenCompose(r -> Serialize.readFully(r, capsFile.getSize()))
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
        return dir.getChild(version, DIR_STATE, network)
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
                () -> mirrorDir.getChild(version, path.get(childIndex), network)
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

        return mirrorDir.getChild(version, DIR_STATE, network)
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

    public CompletableFuture<Snapshot> getLatestVersion(EntryPoint sharedDir, NetworkAccess network) {
        return NetworkAccess.getLatestEntryPoint(sharedDir, network)
                .thenApply(r ->  r.file.version);
    }

    public synchronized CompletableFuture<Pair<Snapshot, CapsDiff>> ensureFriendUptodate(String friend,
                                                                                         EntryPoint sharedDir,
                                                                                         List<EntryPoint> groups,
                                                                                         Snapshot s,
                                                                                         Committer c,
                                                                                         NetworkAccess network) {
        // if the friend's mutable pointer hasn't changed since our last update we can short circuit early
        PublicKeyHash owner = sharedDir.pointer.owner;
        PublicKeyHash writer = sharedDir.pointer.writer;
        if (! s.contains(writer))
            return Futures.of(new Pair<>(s, CapsDiff.empty()));
        CommittedWriterData latestCwd = s.get(writer);
        MaybeMultihash latestRoot = latestCwd.hash;
        Pair<MaybeMultihash, CapsDiff> cached = pointerCache.get(writer);
        boolean equal = cached != null && latestRoot.equals(cached.left);
        if (equal) {
            if (cached.right.groupDiffs.size() == groups.size())
                return Futures.of(new Pair<>(s, cached.right));
        }

        return getAndUpdateRoot(s, network)
                .thenCompose(root -> root.getDescendentByPath(friend + FRIEND_STATE_SUFFIX, s, hasher, network)
                        .thenCompose(stateOpt -> {
                            if (stateOpt.isEmpty())
                                return Futures.of(ProcessedCaps.empty());
                            return Serialize.readFully(stateOpt.get(), crypto, network)
                                    .thenApply(arr -> ProcessedCaps.fromCbor(CborObject.fromByteArray(arr)));
                        }))
                .thenCompose(currentState -> ensureUptodate(friend, sharedDir, groups, currentState, s, c, crypto, network))
                .thenApply(res -> {
                    pointerCache.put(writer, new Pair<>(latestRoot, res.right.flatten()));
                    return res;
                });
    }

    public CompletableFuture<CapsDiff> getCapsFrom(String friend,
                                                   EntryPoint originalSharedDir,
                                                   List<EntryPoint> groups,
                                                   ProcessedCaps current,
                                                   Snapshot s,
                                                   NetworkAccess network) {
        return network.getFile(originalSharedDir, s)
                .thenCompose(shared -> shared.isEmpty() ?
                        Futures.of(CapsDiff.empty()) :
                        retrieveNewCaps(shared.get(), current, network, crypto)
                                .thenCompose(direct -> Futures.combineAll(groups.stream()
                                                .parallel()
                                                .map(e -> network.getFile(e, s)
                                                        .thenApply(Optional::get)
                                                        .thenCompose(sharedDir -> retrieveNewCaps(sharedDir,
                                                                current.groups.getOrDefault(sharedDir.getName(), ProcessedCaps.empty()), network, crypto)
                                                                .thenApply(diff -> Optional.of(new Pair<>(sharedDir.getName(), diff))))
                                                        .exceptionally(t -> Optional.empty()))
                                                .collect(Collectors.toList()))
                                        .thenApply(groupDiffs -> groupDiffs.stream()
                                                .flatMap(Optional::stream)
                                                .reduce(direct,
                                                        (a, p) -> a.mergeGroups(current.createGroupDiff(p.left, p.right)),
                                                        CapsDiff::mergeGroups))));
    }

    private static CompletableFuture<CapsDiff> retrieveNewCaps(FileWrapper sharedDir,
                                                               ProcessedCaps current,
                                                               NetworkAccess network,
                                                               Crypto crypto) {
        return retrieveNewCaps(sharedDir, current.readCapBytes, current.writeCapBytes, network, crypto)
                .exceptionally(t -> {
                    // we might have been removed from a group or similar
                    t.printStackTrace();
                    return CapsDiff.empty();
                });
    }

    private static CompletableFuture<CapsDiff> retrieveNewCaps(FileWrapper sharedDir,
                                                               long readCapBytes,
                                                               long writeCapBytes,
                                                               NetworkAccess network,
                                                               Crypto crypto) {
        return CapabilityStore.loadReadAccessSharingLinksFromIndex(null, sharedDir,
                null, network, crypto, readCapBytes, false, true)
                .thenCompose(newReadCaps ->
                        getWritableCaps(sharedDir, writeCapBytes, crypto, network)
                                .thenApply(writeable ->
                                        new CapsDiff.ReadAndWriteCaps(newReadCaps, writeable)))
                .thenApply(newCaps -> new CapsDiff(readCapBytes, writeCapBytes, newCaps, Collections.emptyMap()));
    }

    private synchronized CompletableFuture<Pair<Snapshot, CapsDiff>> ensureUptodate(String friend,
                                                                                    EntryPoint originalSharedDir,
                                                                                    List<EntryPoint> groups,
                                                                                    ProcessedCaps current,
                                                                                    Snapshot s,
                                                                                    Committer c,
                                                                                    Crypto crypto,
                                                                                    NetworkAccess network) {
        // check there are no new capabilities in the friend's shared directory, or any of their groups
        return getCapsFrom(friend, originalSharedDir, groups, current, s, network)
                .thenCompose(diff -> addNewCapsToMirror(friend, current, diff, s, c, network))
                .thenCompose(p -> getAndUpdateWorldRoot(p.left, network)
                        .thenApply(y -> p));
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

    private CompletableFuture<Pair<Snapshot, CapsDiff>> addNewCapsToMirror(String friend,
                                                                           ProcessedCaps current,
                                                                           CapsDiff diff,
                                                                           Snapshot s,
                                                                           Committer c,
                                                                           NetworkAccess network) {
        if (diff.isEmpty())
            return Futures.of(new Pair<>(s, diff));

        List<CapabilityWithPath> all = diff.getNewCaps();
        // Add all new caps to mirror tree
        return worldRoot.getUpdated(s, network)
                .thenCompose(updatedWorldRoot -> Futures.reduceAll(all, updatedWorldRoot,
                        (r, cap) -> addCapToMirror(friend, r, cap, r.version, c, crypto, network),
                        (a, b) -> b))
                .thenCompose(updatedRoot -> {
                    this.worldRoot = updatedRoot;
                    // Commit our position in the friend's cap stream
                    ProcessedCaps updated = current.add(diff);
                    byte[] raw = updated.serialize();
                    AsyncReader reader = AsyncReader.build(raw);
                    return getAndUpdateRoot(updatedRoot.version, network)
                            .thenCompose(root -> root.uploadOrReplaceFile(friend + FRIEND_STATE_SUFFIX, reader, raw.length,
                                    false, updatedRoot.version, c, network, crypto, x -> {}, crypto.random.randomBytes(RelativeCapability.MAP_KEY_LENGTH),
                                    Optional.of(Bat.random(crypto.random)),
                                    root.mirrorBatId()))
                            .thenApply(v -> new Pair<>(s.mergeAndOverwriteWith(v), diff));
                });
    }

    private static CompletableFuture<FileWrapper> addCapToMirror(String friend,
                                                                 FileWrapper root,
                                                                 CapabilityWithPath cap,
                                                                 Snapshot s,
                                                                 Committer c,
                                                                 Crypto crypto,
                                                                 NetworkAccess network) {
        Path fullPath = PathUtil.get(cap.path);
        Path parentPath = fullPath.getParent();
        String owner = fullPath.getName(0).toString();
        String filename = fullPath.getFileName().toString();
        return root.getUpdated(s, network)
                .thenCompose(freshRoot -> freshRoot.getOrMkdirs(PathUtil.components(parentPath), false, root.mirrorBatId(), network, crypto, s, c))
                .thenCompose(p -> p.right.getChild(p.left, DIR_STATE, network)
                        .thenCompose(capsOpt -> {
                            if (capsOpt.isEmpty()) {
                                CapsInDirectory single = CapsInDirectory.of(filename, cap.cap, friend);
                                byte[] raw = single.serialize();
                                AsyncReader reader = AsyncReader.build(raw);
                                return p.right.uploadOrReplaceFile(DIR_STATE, reader, raw.length, false, p.left, c, network, crypto,
                                        x -> {}, crypto.random.randomBytes(RelativeCapability.MAP_KEY_LENGTH),
                                        Optional.of(Bat.random(crypto.random)), root.mirrorBatId());
                            }
                            return Serialize.readFully(capsOpt.get(), crypto, network)
                                    .thenApply(CborObject::fromByteArray)
                                    .thenApply(CapsInDirectory::fromCbor)
                                    .thenCompose(existing -> existing.addChild(filename, cap.cap, friend, owner, network))
                                    .thenCompose(updated -> {
                                        byte[] raw = updated.serialize();
                                        AsyncReader reader = AsyncReader.build(raw);
                                        return capsOpt.get().overwriteFile(reader, raw.length, network, crypto, x -> {}, p.left, c);
                                    });
                        })
                ).thenCompose(v -> root.getUpdated(v, network));
    }

    private synchronized CompletableFuture<FileWrapper> getAndUpdateWorldRoot(Snapshot s, NetworkAccess network) {
        return worldRoot.getUpdated(s, network).thenApply(updated -> {
            this.worldRoot = updated;
            return updated;
        });
    }

    private synchronized CompletableFuture<FileWrapper> getAndUpdateRoot(Snapshot s, NetworkAccess network) {
        return cacheRoot.getUpdated(s, network).thenApply(updated -> {
            this.cacheRoot = updated;
            return updated;
        });
    }
}
