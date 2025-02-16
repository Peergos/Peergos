package peergos.shared.user.fs;
import java.nio.file.*;
import java.util.logging.*;

import jsinterop.annotations.*;
import peergos.shared.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.crypto.random.*;
import peergos.shared.crypto.symmetric.*;
import peergos.shared.io.ipfs.Multihash;
import peergos.shared.storage.*;
import peergos.shared.storage.auth.*;
import peergos.shared.user.*;
import peergos.shared.user.fs.cryptree.*;
import peergos.shared.user.fs.transaction.*;
import peergos.shared.util.*;

import java.io.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import java.util.stream.*;

/** This class is used to read and modify files and directories and represents a single file or directory and the keys
 *  to access it.
 *
 */
public class FileWrapper {
    public static final long THUMBNAIL_PROGRESS_OFFSET = 20*1024;
	private static final Logger LOG = Logger.getGlobal();

    private final static int THUMBNAIL_SIZE = 400;
    private static final NativeJSThumbnail thumbnail = new NativeJSThumbnail();

    private final RetrievedCapability pointer;
    private final Optional<RetrievedCapability> linkPointer;
    private final Optional<SigningPrivateKeyAndPublicHash> entryWriter;
    private final FileProperties props;
    private final String ownername;
    private final Optional<TrieNode> capTrie;
    public final Snapshot version;
    private final boolean isWritable;
    private AtomicBoolean modified = new AtomicBoolean(); // This only used as a guard against concurrent modifications

    /**
     *
     * @param capTrie This is only present if this is the global root, or this is read only
     * @param pointer
     * @param ownername
     */
    public FileWrapper(Optional<TrieNode> capTrie,
                       RetrievedCapability pointer,
                       Optional<RetrievedCapability> linkPointer,
                       Optional<SigningPrivateKeyAndPublicHash> entryWriter,
                       String ownername,
                       Snapshot version) {
        this.capTrie = capTrie;
        this.pointer = pointer;
        this.linkPointer = linkPointer;
        this.entryWriter = entryWriter;
        this.ownername = ownername;
        this.version = version;
        this.isWritable = pointer != null &&
                pointer.capability instanceof WritableAbsoluteCapability ||
                entryWriter.map(s -> s.publicKeyHash.equals(pointer.capability.writer)).orElse(false);
        if (pointer != null) {
            SymmetricKey parentKey = this.getParentKey();
            FileProperties directProps = pointer.fileAccess.getProperties(parentKey);
            if (linkPointer.isPresent()) {
                RetrievedCapability link = linkPointer.get();
                FileProperties linkProps = link.getProperties();
                this.props = directProps.withLink(linkProps);
            } else {
                this.props = directProps;
            }
        } else
            props = null;
        if (pointer != null && ! version.contains(pointer.capability.writer))
            throw new IllegalStateException("File version doesn't include its own writer!");
        if (isWritable() && !signingPair().publicKeyHash.equals(pointer.capability.writer))
            throw new IllegalStateException("Invalid FileWrapper! public writing keys don't match!");
    }

    public FileWrapper(RetrievedCapability pointer,
                       Optional<RetrievedCapability> linkPointer,
                       Optional<SigningPrivateKeyAndPublicHash> entryWriter,
                       String ownername,
                       Snapshot version) {
        this(Optional.empty(), pointer, linkPointer, entryWriter, ownername, version);
    }

    public FileWrapper withTrieNode(TrieNode trie) {
        return new FileWrapper(Optional.of(trie), pointer, linkPointer, entryWriter, ownername, version);
    }

    public FileWrapper withTrieNodeOpt(Optional<TrieNode> trie) {
        return new FileWrapper(trie, pointer, linkPointer, entryWriter, ownername, version);
    }

    public FileWrapper withLinkPointer(Optional<RetrievedCapability> link) {
        return new FileWrapper(capTrie, pointer, link, entryWriter, ownername, version);
    }

    public FileWrapper withVersion(Snapshot version) {
        return new FileWrapper(capTrie, pointer, linkPointer, entryWriter, ownername, version);
    }

    public CommittedWriterData getVersionRoot() {
        return version.get(writer());
    }

    @JsMethod
    public CompletableFuture<FileWrapper> getLatest(NetworkAccess network) {
        if (isRoot())
            return Futures.of(this);
        return network.synchronizer.getValue(owner(), writer())
                .thenCompose(latest -> getUpdated(latest, network));

    }

    @JsMethod
    public boolean samePointer(FileWrapper other) {
        return pointer.equals(other.getPointer());
    }

    public CompletableFuture<FileWrapper> getUpdated(NetworkAccess network) {
        return network.synchronizer.getValue(owner(), writer())
                .thenCompose(v -> getUpdated(v, network));
    }

    public CompletableFuture<FileWrapper> getUpdated(Snapshot version, NetworkAccess network) {
        return version.withWriter(owner(), writer(), network).thenCompose(v -> {
            if (this.version.get(writer()).equals(v.get(writer())))
                return CompletableFuture.completedFuture(this);
            return network.getFile(v, pointer.capability, entryWriter, ownername)
                    .thenApply(Optional::get)
                    .thenApply(f -> f.withTrieNodeOpt(capTrie));
        });
    }

    public PublicKeyHash owner() {
        return pointer.capability.owner;
    }

    public PublicKeyHash writer() {
        return pointer.capability.writer;
    }

    public RetrievedCapability getPointer() {
        return pointer;
    }

    public RetrievedCapability getLinkPointer() {
        return linkPointer.get();
    }

    public boolean isRoot() {
        return pointer == null;
    }

    public CompletableFuture<String> getPath(NetworkAccess network) {
        return retrieveParent(network).thenCompose(parent -> {
            if (!parent.isPresent() || parent.get().isRoot())
                return CompletableFuture.completedFuture("/" + props.name);
            return parent.get().getPath(network).thenApply(parentPath -> parentPath + "/" + props.name);
        });
    }

    @JsMethod
    public CompletableFuture<Multihash> getContentHash(NetworkAccess network, Crypto crypto) {
        return getInputStream(network, crypto, x -> {})
                .thenCompose(reader -> crypto.hasher.hashFromStream(reader, getSize()));
    }

    public CompletableFuture<Optional<FileWrapper>> getDescendentByPath(String path,
                                                                        Hasher hasher,
                                                                        NetworkAccess network) {
        return getDescendentByPath(path, version, hasher, network);
    }

    public CompletableFuture<Optional<FileWrapper>> getDescendentByPath(String path,
                                                                        Snapshot version,
                                                                        Hasher hasher,
                                                                        NetworkAccess network) {
        ensureUnmodified();
        if (path.length() == 0)
            return CompletableFuture.completedFuture(Optional.of(this));

        if (path.equals("/"))
            if (isDirectory())
                return CompletableFuture.completedFuture(Optional.of(this));
            else
                return CompletableFuture.completedFuture(Optional.empty());

        Path canon = PathUtil.get(path);
        return getChild(version, canon.getName(0).toString(), network).thenCompose(child -> {
            if (child.isPresent()) {
                int names = canon.getNameCount();
                if (names == 1)
                    return Futures.of(child);
                return child.get().getDescendentByPath(canon.subpath(1, names).toString(), child.get().version, hasher, network);
            }
            return CompletableFuture.completedFuture(Optional.empty());
        });
    }

    private void ensureUnmodified() {
        if (modified.get())
            throw new IllegalStateException("This file has already been modified, use the returned instance");
    }

    private void setModified() {
        if (modified.get())
            throw new IllegalStateException("This file has already been modified, use the returned instance");
        modified.set(true);
    }

    public CompletableFuture<Snapshot> updateChildLinks(
            Snapshot version,
            Committer committer,
            Collection<Pair<AbsoluteCapability, NamedAbsoluteCapability>> childCases,
            NetworkAccess network,
            SafeRandom random,
            Hasher hasher) {
        return pointer.fileAccess
                .updateChildLinks(version, committer, (WritableAbsoluteCapability) pointer.capability, signingPair(),
                        childCases, network, random, hasher);
    }

    public CompletableFuture<Boolean> hasChildWithName(Snapshot version, String name, Hasher hasher, NetworkAccess network) {
        ensureUnmodified();
        return getChild(version, name, network)
                .thenApply(Optional::isPresent);
    }

    /**
     *
     * @param child
     * @param network
     * @return Updated version of this directory without the child
     */
    public CompletableFuture<FileWrapper> removeChild(FileWrapper child, NetworkAccess network, SafeRandom random, Hasher hasher) {
        setModified();
        return network.synchronizer.applyComplexUpdate(owner(), signingPair(),
                (cwd, committer) -> pointer.fileAccess
                .removeChildren(cwd, committer, Arrays.asList(child.isLink() ? child.linkPointer.get().capability : child.getPointer().capability), writableFilePointer(), entryWriter, network, random, hasher))
                .thenCompose(newRoot -> getUpdated(newRoot, network));
    }

    public CompletableFuture<Snapshot> removeChild(Snapshot version,
                                                   Committer committer,
                                                   FileWrapper child,
                                                   NetworkAccess network,
                                                   SafeRandom random,
                                                   Hasher hasher) {
        return pointer.fileAccess.removeChildren(version, committer,
                Arrays.asList(child.isLink() ? child.linkPointer.get().capability : child.getPointer().capability), writableFilePointer(), entryWriter, network, random, hasher);
    }

    @JsMethod
    public String toLink() {
        return pointer.capability.readOnly().toLink();
    }

    @JsMethod
    public String toWritableLink() {
        if (! isWritable())
            throw new IllegalStateException("You do not have write access to " + getName());
        return pointer.capability.toLink();
    }

    @JsMethod
    public boolean isWritable() {
        return isWritable;
    }

    @JsMethod
    public boolean isReadable() {
        return pointer.fileAccess.isReadable(pointer.capability.rBaseKey);
    }

    public SymmetricKey getKey() {
        return pointer.capability.rBaseKey;
    }

    public Location getLocation() {
        return new Location(pointer.capability.owner, pointer.capability.writer, pointer.capability.getMapKey());
    }

    public CompletableFuture<Set<NamedAbsoluteCapability>> getChildrenCapabilities(Hasher hasher, NetworkAccess network) {
        ensureUnmodified();
        if (!this.isDirectory())
            return CompletableFuture.completedFuture(Collections.emptySet());
        return pointer.fileAccess.getAllChildrenCapabilities(version, pointer.capability, hasher, network);
    }

    public CompletableFuture<Optional<FileWrapper>> retrieveParent(NetworkAccess network) {
        ensureUnmodified();
        return retrieveParent(linkPointer.orElse(pointer), ownername, version, network);
    }

    public CompletableFuture<Optional<RetrievedCapability>> getAnyLinkPointer(NetworkAccess network) {
        if (pointer == null)
            return CompletableFuture.completedFuture(Optional.empty());
        AbsoluteCapability cap = pointer.capability;
        CompletableFuture<RetrievedCapability> parent = pointer.fileAccess.getParent(cap.owner, cap.writer, cap.rBaseKey, network, version);
        return parent.thenApply(parentRFP -> {
            if (parentRFP == null)
                return Optional.empty();
            FileProperties parentProps = parentRFP.getProperties();
            if (! parentProps.isLink)
                return Optional.empty();
            return Optional.of(parentRFP);
        });
    }

    public static CompletableFuture<Optional<FileWrapper>> retrieveParent(RetrievedCapability pointer,
                                                                          String ownerName,
                                                                          Snapshot version,
                                                                          NetworkAccess network) {
        if (pointer == null)
            return CompletableFuture.completedFuture(Optional.empty());
        AbsoluteCapability cap = pointer.capability;
        CompletableFuture<RetrievedCapability> parent = pointer.fileAccess.getParent(cap.owner, cap.writer, cap.rBaseKey, network, version);
        return parent.thenCompose(parentRFP -> {
            if (parentRFP == null)
                return Futures.of(Optional.empty());
            FileProperties parentProps = parentRFP.getProperties();
            if (! parentProps.isLink)
                return version.withWriter(parentRFP.capability.owner, parentRFP.capability.writer, network)
                        .thenApply(fullVersion -> Optional.of(new FileWrapper(parentRFP, Optional.empty(),
                                Optional.empty(), ownerName, fullVersion)));
            return retrieveParent(parentRFP, ownerName, version, network);
        });
    }

    @JsMethod
    public boolean isUserRoot() {
        if (pointer == null)
            return false;
        return ! pointer.fileAccess.hasParentLink(pointer.capability.rBaseKey);
    }

    public SymmetricKey getParentKey() {
        return pointer.getParentKey();
    }

    private Optional<SigningPrivateKeyAndPublicHash> getChildsEntryWriter() {
        return pointer.capability.wBaseKey
                .map(wBase -> pointer.fileAccess.getSigner(pointer.capability.rBaseKey, wBase, entryWriter));
    }

    @JsMethod
    public CompletableFuture<Set<FileWrapper>> getChildren(Hasher hasher, NetworkAccess network) {
        if (capTrie.isPresent())
            return capTrie.get().getChildren("", hasher, network);
        return getChildren(version, hasher, network, true);
    }

    public CompletableFuture<Set<FileWrapper>> getChildren(Snapshot version, Hasher hasher, NetworkAccess network, boolean allowDanglingLinks) {
        if (capTrie.isPresent())
            return capTrie.get().getChildren("", hasher, version.merge(this.version), network);
        if (isReadable()) {
            Optional<SigningPrivateKeyAndPublicHash> childsEntryWriter = pointer.capability.wBaseKey
                    .map(wBase -> pointer.fileAccess.getSigner(pointer.capability.rBaseKey, wBase, entryWriter));
            return pointer.fileAccess.getAllChildrenCapabilities(version, pointer.capability, hasher, network)
                    .thenCompose(childCaps -> getFiles(owner(), childCaps, childsEntryWriter, ownername, network, version)
                            .thenCompose(p -> {
                                if (! p.right.isEmpty()) {
                                    List<NamedAbsoluteCapability> dangling = p.right.stream()
                                            .map(c -> childCaps.stream().filter(nc -> nc.cap.equals(c)).findFirst().get())
                                            .collect(Collectors.toList());
                                    // try once more
                                    return getFiles(owner(), new HashSet<>(dangling), childsEntryWriter, ownername, network, version)
                                            .thenApply(retry -> {
                                                if (! retry.right.isEmpty()) {
                                                    List<NamedAbsoluteCapability> retryDangling = retry.right.stream()
                                                            .map(c -> childCaps.stream().filter(nc -> nc.cap.equals(c)).findFirst().get())
                                                            .collect(Collectors.toList());
                                                    List<String> names = retryDangling.stream().map(nc -> nc.name.name).collect(Collectors.toList());
                                                    if (! allowDanglingLinks) {
                                                        throw new IllegalStateException("Couldn't retrieve children " + names + " in dir " + getName());
                                                    }
                                                    LOG.info("Couldn't retrieve children " + names + " in dir " + getName());
                                                }
                                                HashSet<FileWrapper> res = new HashSet<>(p.left);
                                                res.addAll(retry.left);
                                                return res;
                                            });
                                }
                                return Futures.of(p.left);
                            }));
        }
        throw new IllegalStateException("Unreadable FileWrapper!");
    }

    public CompletableFuture<Set<FileWrapper>> getChildren(Set<String> names, Hasher hasher, NetworkAccess network, boolean allowDanglingLinks) {
        if (capTrie.isPresent())
            return capTrie.get().getChildren("", hasher, network)
                    .thenApply(kids -> kids.stream()
                            .filter(f -> names.contains(f.getName()))
                            .collect(Collectors.toSet()));

        if (isReadable()) {
            Optional<SigningPrivateKeyAndPublicHash> childsEntryWriter = pointer.capability.wBaseKey
                    .map(wBase -> pointer.fileAccess.getSigner(pointer.capability.rBaseKey, wBase, entryWriter));
            return pointer.fileAccess.getAllChildrenCapabilities(version, pointer.capability, hasher, network)
                    .thenCompose(childCaps -> getFiles(owner(), childCaps.stream()
                            .filter(c -> names.contains(c.name.name))
                            .collect(Collectors.toSet()), childsEntryWriter, ownername, network, version)
                            .thenCompose(p -> {
                                if (! p.right.isEmpty()) {
                                    List<NamedAbsoluteCapability> dangling = p.right.stream()
                                            .map(c -> childCaps.stream().filter(nc -> nc.cap.equals(c)).findFirst().get())
                                            .collect(Collectors.toList());
                                    // try once more
                                    return getFiles(owner(), new HashSet<>(dangling), childsEntryWriter, ownername, network, version)
                                            .thenApply(retry -> {
                                                if (! retry.right.isEmpty()) {
                                                    List<NamedAbsoluteCapability> retryDangling = retry.right.stream()
                                                            .map(c -> childCaps.stream().filter(nc -> nc.cap.equals(c)).findFirst().get())
                                                            .collect(Collectors.toList());
                                                    List<String> failednames = retryDangling.stream().map(nc -> nc.name.name).collect(Collectors.toList());
                                                    if (! allowDanglingLinks) {
                                                        throw new IllegalStateException("Couldn't retrieve children " + failednames + " in dir " + getName());
                                                    }
                                                    LOG.info("Couldn't retrieve children " + failednames + " in dir " + getName());
                                                }
                                                p.left.addAll(retry.left);
                                                return p.left;
                                            });
                                }
                                return Futures.of(p.left);
                            }));
        }
        throw new IllegalStateException("Unreadable FileWrapper!");
    }

    /**
     *
     * @param owner
     * @param caps
     * @param entryWriter
     * @param ownername
     * @param network
     * @param version
     * @return the children, and the list of caps pointing to deleted files
     */
    private static CompletableFuture<Pair<Set<FileWrapper>, List<AbsoluteCapability>>> getFiles(PublicKeyHash owner,
                                                                                                Set<NamedAbsoluteCapability> caps,
                                                                                                Optional<SigningPrivateKeyAndPublicHash> entryWriter,
                                                                                                String ownername,
                                                                                                NetworkAccess network,
                                                                                                Snapshot version) {
        Set<PublicKeyHash> childWriters = caps.stream()
                .map(c -> c.cap.writer)
                .collect(Collectors.toSet());
        return version.withWriters(owner, childWriters, network)
                .thenCompose(fullVersion -> network.retrieveAllMetadata(caps.stream().map(n -> n.cap).collect(Collectors.toList()), fullVersion)
                        .thenCompose(p -> Futures.combineAll(p.left.stream()
                                .map(rc -> {
                                    FileProperties props = rc.getProperties();
                                    if (! props.isLink)
                                        return Futures.of(new FileWrapper(rc, Optional.empty(), entryWriter, ownername, fullVersion));
                                    return NetworkAccess.getFileFromLink(owner, rc, entryWriter, ownername, network, version);
                                })
                                .collect(Collectors.toSet())).thenApply(set -> new Pair<>(set, p.right))));
    }

    @JsMethod
    public CompletableFuture<Optional<FileWrapper>> getChild(String name, Hasher hasher, NetworkAccess network) {
        return getChild(version, name, network);
    }

    public CompletableFuture<Optional<FileWrapper>> getChild(Snapshot version,
                                                             String name,
                                                             NetworkAccess network) {
        if (capTrie.isPresent())
            return capTrie.get().getByPath("/" + name, version.merge(this.version), network.hasher, network);
        return pointer.fileAccess.getChild(name, pointer.capability, version, network.hasher, network)
                .thenCompose(rcOpt -> {
                    if (rcOpt.isEmpty())
                        return Futures.of(Optional.empty());
                    RetrievedCapability rc = rcOpt.get();
                    FileProperties props = rc.getProperties();
                    Optional<SigningPrivateKeyAndPublicHash> childsEntryWriter = pointer.capability.wBaseKey
                            .map(wBase -> pointer.fileAccess.getSigner(pointer.capability.rBaseKey, wBase, entryWriter));
                    if (! props.isLink)
                        return version.withWriter(owner(), rc.capability.writer, network)
                                .thenApply(fullVersion -> Optional.of(new FileWrapper(rc, Optional.empty(),
                                        childsEntryWriter, ownername, fullVersion)));
                    return version.withWriter(owner(), rc.capability.writer, network)
                            .thenCompose(fullVersion ->
                                    NetworkAccess.getFileFromLink(owner(), rc, childsEntryWriter, ownername, network, fullVersion)
                                            .thenApply(Optional::of));
                });
    }

    @JsMethod
    public String getOwnerName() {
        return ownername;
    }

    @JsMethod
    public boolean isDirectory() {
        boolean isNull = pointer == null;
        return isNull || pointer.fileAccess.isDirectory();
    }

    public boolean isLink() {
        return linkPointer.isPresent();
    }

    public boolean isDirty() {
        ensureUnmodified();
        return pointer.fileAccess.isDirty(pointer.capability.rBaseKey);
    }

    /**
     *
     * @param current
     * @param committer
     * @param network
     * @param crypto
     * @return updated cleaned version
     */
    public CompletableFuture<Pair<FileWrapper, Snapshot>> clean(Snapshot current,
                                                                Committer committer,
                                                                NetworkAccess network,
                                                                Crypto crypto) {
        if (!isDirty())
            return CompletableFuture.completedFuture(new Pair<>(this, current));
        if (isDirectory()) {
            throw new IllegalStateException("Directories are never dirty (they are cleaned immediately)!");
        } else {
            WritableAbsoluteCapability currentCap = writableFilePointer();
            boolean isLink = isLink();
            Optional<RelativeCapability> parentCap = pointer.fileAccess.getParentCapability(pointer.capability.rBaseKey);
            Location parentOrLinkLocation = isLink ?
                    linkPointer.get().capability.getLocation() :
                    parentCap.get().getLocation(owner(), writer());
            SymmetricKey parentOrLinkParentKey = isLink ?
                    linkPointer.get().getParentKey() :
                    parentCap.get().rBaseKey;
            Optional<Bat> parentOrLinkBat = isLink ?
                    linkPointer.get().capability.bat :
                    parentCap.get().bat;
            return pointer.fileAccess.cleanAndCommit(current, committer, currentCap, currentCap, props.streamSecret,
                    Optional.empty(), signingPair(), SymmetricKey.random(),
                    parentOrLinkLocation, parentOrLinkBat, parentOrLinkParentKey, network, crypto)
                    .thenCompose(cwd -> {
                        setModified();
                        return getUpdated(cwd, network).thenApply(updated -> new Pair<>(updated, cwd));
                    });
        }
    }

    public CompletableFuture<Pair<byte[], Optional<Bat>>> getMapKey(long offset, NetworkAccess network, Crypto crypto) {
        CryptreeNode fileAccess = pointer.fileAccess;
        return fileAccess.retriever(pointer.capability.rBaseKey, props.streamSecret, getLocation().getMapKey(), pointer.capability.bat, crypto.hasher)
                .thenCompose(retriever -> retriever
                        .getMapLabelAt(version.get(writer()).props.get(), writableFilePointer(),
                                getFileProperties().streamSecret, offset, crypto.hasher, network)
                        .thenApply(Optional::get));
    }

    @JsMethod
    public CompletableFuture<FileWrapper> truncate(long newSize, NetworkAccess network, Crypto crypto) {
        if (getSize() <= newSize)
            return Futures.of(this);
        return network.synchronizer.applyComplexUpdate(owner(), signingPair(), (current, committer) ->
                truncate(current, committer, newSize, network, crypto)
        ).thenCompose(finished -> getUpdated(finished, network));
    }

    public CompletableFuture<Snapshot> truncate(Snapshot initialVersion, Committer committer, long newSize, NetworkAccess network, Crypto crypto) {
        if (isDirectory())
            return Futures.errored(new IllegalStateException("You cannot truncate a directory!"));
        FileProperties props = getFileProperties();
        if (props.size <= newSize)
            return CompletableFuture.completedFuture(initialVersion);

        return initialVersion.withWriter(owner(), writer(), network)
                .thenCompose(snapshot -> getMapKey(newSize, network, crypto).thenCompose(endMapKey ->
                        getInputStream(snapshot.get(writer()).props.get(), network, crypto, props.size, 1, x -> {}).thenCompose(originalReader -> {
                            long startOfLastChunk = newSize - (newSize % Chunk.MAX_SIZE);
                            return originalReader.seek(startOfLastChunk).thenCompose(seekedOriginal -> {
                                byte[] lastChunk = new byte[(int)(newSize % Chunk.MAX_SIZE)];
                                return seekedOriginal.readIntoArray(lastChunk, 0, lastChunk.length).thenCompose(read -> {
                                    if (newSize <= Chunk.MAX_SIZE)
                                        return CompletableFuture.completedFuture(snapshot);
                                    int currentChunk = (int) (newSize / Chunk.MAX_SIZE);
                                    return IpfsTransaction.call(owner(), tid ->
                                                    deleteFileChunks(props.streamSecret.get(), props.chunkCount() - currentChunk, writableFilePointer().withMapKey(endMapKey.left, endMapKey.right),
                                                            signingPair(), tid, crypto.hasher, network, snapshot, committer),
                                            network.dhtClient);
                                }).thenCompose(deleted -> pointer.fileAccess.updateProperties(deleted, committer, writableFilePointer(),
                                        entryWriter, props.withSize(startOfLastChunk), network).thenCompose(resized ->
                                        getUpdated(resized, network).thenCompose(f -> f.clean(resized, committer, network, crypto)
                                                .thenCompose(p -> p.left.overwriteSection(p.right, committer,
                                                AsyncReader.build(lastChunk), startOfLastChunk, newSize, network, crypto, x -> {})))));
                            });
                        }))
                );
    }

    public static int getNumberOfChunks(long size) {
        if (size == 0)
            return 1;
        return (int)((size + Chunk.MAX_SIZE - 1)/Chunk.MAX_SIZE);
    }

    public List<Location> generateChildLocationsFromSize(long fileSize, SafeRandom random) {
        return generateChildLocations(getNumberOfChunks(fileSize), random);
    }

    public List<Location> generateChildLocations(int numberOfChunks,
                                                        SafeRandom random) {
        return IntStream.range(0, numberOfChunks + 1) //have to have one extra location
                .mapToObj(e -> new Location(owner(), writer(), random.randomBytes(32)))
                .collect(Collectors.toList());
    }

    @JsMethod
    public CompletableFuture<FileWrapper> appendFileJS(String filename,
                                                       AsyncReader fileData,
                                                       int lengthHi,
                                                       int lengthLow,
                                                       NetworkAccess network,
                                                       Crypto crypto,
                                                       ProgressConsumer<Long> monitor) {
        long fileSize = LongUtil.intsToLong(lengthHi, lengthLow);
        return network.synchronizer.applyComplexUpdate(owner(), signingPair(),
                (s, committer) -> getChild(s, filename, network).thenCompose(childOpt -> {
                if (childOpt.isEmpty()) {
                    throw new IllegalStateException("File does not exists with name " + filename);
                } else {
                    FileProperties props = childOpt.get().getFileProperties();
                    if (props.isHidden) {
                        throw new IllegalStateException("File is hidden " + filename);
                    }
                    long startIndex = props.size;
                    return uploadFileSection(s, committer, filename, fileData,
                            false, startIndex, startIndex + fileSize, Optional.empty(), false, true, false,
                            network, crypto, monitor, crypto.random.randomBytes(32), Optional.empty(),
                            Optional.of(Bat.random(crypto.random)), mirrorBatId());
                }
        })).thenCompose(finished -> getUpdated(finished, network));
    }

    @JsMethod
    public CompletableFuture<FileWrapper> uploadFileJS(String filename,
                                                       AsyncReader fileData,
                                                       int lengthHi,
                                                       int lengthLow,
                                                       boolean overwriteExisting,
                                                       Optional<BatId> mirrorBat,
                                                       NetworkAccess network,
                                                       Crypto crypto,
                                                       ProgressConsumer<Long> monitor,
                                                       TransactionService transactions,
                                                       Function<FileUploadTransaction, CompletableFuture<Boolean>> resumeFile) {
        FileUploadProperties fileProps = new FileUploadProperties(filename, () -> fileData, lengthHi, lengthLow, Optional.empty(), Optional.empty(), false, overwriteExisting, monitor);
        FolderUploadProperties currentFolder = new FolderUploadProperties(Collections.emptyList(), Collections.singletonList(fileProps));
        return uploadSubtree(Stream.of(currentFolder), mirrorBat, network, crypto, transactions, resumeFile, () -> true);
    }

    private CompletableFuture<Pair<Snapshot, Optional<NamedRelativeCapability>>> resumeUpload(FileUploadTransaction txn,
                                                                                              AsyncReader data,
                                                                                              ProgressConsumer<Long> monitor,
                                                                                              Snapshot s,
                                                                                              Committer c,
                                                                                              NetworkAccess network,
                                                                                              Crypto crypto) {
        RelativeCapability fromParent = writableFilePointer().relativise(txn.writeCap());
        FileProperties props = txn.props;
        // first find how many chunks were already uploaded, then seek reader to that offset and continue
        return findFirstAbsentChunkIndex(0, txn.streamSecret(), txn.getFirstLocation(), s, network, crypto)
                .thenCompose(startChunkIndex -> {
                    monitor.accept(startChunkIndex * Chunk.MAX_SIZE);
                    FileUploader uploader = new FileUploader(txn.targetFilename(), data, startChunkIndex*Chunk.MAX_SIZE,
                            txn.size(), txn.baseKey, txn.dataKey, getLocation(), getPointer().capability.bat, getParentKey(),
                            monitor, props, Optional.empty(), txn.getFirstLocation().getMapKey(), txn.firstBat);
                    return uploader.uploadFrom(s, c, network, startChunkIndex.intValue(), txn.getFirstLocation().owner,
                            signingPair(), mirrorBatId(), crypto.random, crypto.hasher);
                }).thenApply(v -> new Pair<>(v, Optional.of(new NamedRelativeCapability(txn.targetFilename(), fromParent))));
    }

    private CompletableFuture<Long> findFirstAbsentChunkIndex(long currentIndex, byte[] streamSecret, Location currentLoc, Snapshot s, NetworkAccess network, Crypto crypto) {
        return network.chunkIsPresent(s, currentLoc.owner, currentLoc.writer, currentLoc.getMapKey())
                .thenCompose(present -> present ?
                        FileProperties.calculateNextMapKey(streamSecret, currentLoc.getMapKey(), Optional.empty(), crypto.hasher)
                                .thenCompose(p -> findFirstAbsentChunkIndex(currentIndex + 1, streamSecret, currentLoc.withMapKey(p.left), s, network, crypto)) :
                        Futures.of(currentIndex));
    }

    @JsMethod
    public CompletableFuture<FileWrapper> uploadOrReplaceFile(String filename,
                                                              AsyncReader fileData,
                                                              int fileSizeHi,
                                                              int fileSizeLow,
                                                              NetworkAccess network,
                                                              Crypto crypto,
                                                              ProgressConsumer<Long> monitor) {
        long fileSize = (fileSizeLow & 0xFFFFFFFFL) + ((fileSizeHi & 0xFFFFFFFFL) << 32);
        return uploadOrReplaceFile(filename, fileData, fileSize, network, crypto, monitor,
                crypto.random.randomBytes(32), Optional.of(Bat.random(crypto.random)), mirrorBatId());
    }

    public CompletableFuture<FileWrapper> uploadOrReplaceFile(String filename,
                                                              AsyncReader fileData,
                                                              long length,
                                                              NetworkAccess network,
                                                              Crypto crypto,
                                                              ProgressConsumer<Long> monitor) {
        return uploadOrReplaceFile(filename, fileData, length, network, crypto, monitor,
                crypto.random.randomBytes(32), Optional.of(Bat.random(crypto.random)), mirrorBatId());
    }

    public CompletableFuture<FileWrapper> uploadFileWithHash(String filename,
                                                             AsyncReader fileData,
                                                             long length,
                                                             Optional<HashTree> hash,
                                                             Optional<LocalDateTime> modificationTime,
                                                             NetworkAccess network,
                                                             Crypto crypto,
                                                             ProgressConsumer<Long> monitor) {
        if (! isWritable())
            throw new IllegalStateException("Folder not writable!");
        return network.synchronizer.applyComplexUpdate(owner(), signingPair(), (current, committer) ->
                        uploadFileSection(current, committer, filename, fileData, Optional.empty(), false, 0, length, hash,
                                modificationTime, Optional.empty(), Optional.empty(), Optional.empty(), false, false, false,
                                network, crypto, monitor, crypto.random.randomBytes(32), Optional.empty(), Optional.of(Bat.random(crypto.random)), mirrorBatId())
                                .thenCompose(p -> getUpdated(p.left, network)
                                        .thenCompose(latest -> p.right.isEmpty() ?
                                                Futures.of(p.left) :
                                                latest.addChildPointer(p.left, committer, p.right.get(), network, crypto))))
                .thenCompose(finalBase -> getUpdated(finalBase, network));
    }

    public CompletableFuture<FileWrapper> uploadOrReplaceFile(String filename,
                                                              AsyncReader fileData,
                                                              long length,
                                                              NetworkAccess network,
                                                              Crypto crypto,
                                                              ProgressConsumer<Long> monitor,
                                                              byte[] firstChunkMapKey,
                                                              Optional<Bat> firstChunkBat,
                                                              Optional<BatId> mirrorBat) {
        return uploadFileSection(filename, fileData, false, 0, length, Optional.empty(),
                true, network, crypto, monitor, firstChunkMapKey, Optional.empty(), firstChunkBat, mirrorBat)
                .thenCompose(f -> f.getChild(filename, crypto.hasher, network)
                        .thenCompose(childOpt -> childOpt.get().truncate(length, network, crypto))
                        .thenCompose(c -> f.getUpdated(f.version.mergeAndOverwriteWith(c.version), network)));
    }

    public CompletableFuture<Snapshot> uploadOrReplaceFile(String filename,
                                                           AsyncReader fileData,
                                                           long length,
                                                           boolean isHidden,
                                                           Snapshot s,
                                                           Committer c,
                                                           NetworkAccess network,
                                                           Crypto crypto,
                                                           ProgressConsumer<Long> monitor,
                                                           byte[] firstChunkMapKey,
                                                           Optional<Bat> firstChunkBat,
                                                           Optional<BatId> mirrorBat) {
        return uploadFileSection(s, c, filename, fileData, isHidden, 0, length,
                Optional.empty(), false, true, true, network, crypto,
                monitor, firstChunkMapKey, Optional.empty(), firstChunkBat, mirrorBat);
    }

    public CompletableFuture<FileWrapper> uploadAndReturnFile(String filename,
                                                              AsyncReader fileData,
                                                              long length,
                                                              boolean isHidden,
                                                              Optional<BatId> mirrorBat,
                                                              NetworkAccess network,
                                                              Crypto crypto) {
        return uploadAndReturnFile(filename, fileData, length, isHidden, x -> {}, mirrorBat, network, crypto);
    }

    public CompletableFuture<FileWrapper> uploadAndReturnFile(String filename,
                                                              AsyncReader fileData,
                                                              long length,
                                                              boolean isHidden,
                                                              ProgressConsumer<Long> progressMonitor,
                                                              Optional<BatId> mirrorBat,
                                                              NetworkAccess network,
                                                              Crypto crypto) {
        return uploadFileSection(filename, fileData, isHidden, 0, length, Optional.empty(),
                true, network, crypto, progressMonitor, crypto.random.randomBytes(32), Optional.empty(),
                Optional.of(Bat.random(crypto.random)), mirrorBat)
                .thenCompose(f -> f.getChild(filename, crypto.hasher, network)
                        .thenCompose(childOpt -> childOpt.get().truncate(length, network, crypto)));
    }

    @JsMethod
    public CompletableFuture<FileWrapper> overwriteFileJS(AsyncReader fileData,
                                                          int endHigh,
                                                          int endLow,
                                                          NetworkAccess network,
                                                          Crypto crypto,
                                                          ProgressConsumer<Long> monitor) {
        long newSize = LongUtil.intsToLong(endHigh, endLow);
        return overwriteFile(fileData, newSize, network, crypto, monitor);
    }

    public CompletableFuture<Snapshot> overwriteFile(AsyncReader fileData,
                                                     long newSize,
                                                     NetworkAccess network,
                                                     Crypto crypto,
                                                     ProgressConsumer<Long> monitor,
                                                     Snapshot s,
                                                     Committer committer) {
        long size = getSize();
        return clean(s, committer, network, crypto)
                .thenCompose(u -> u.left.overwriteSection(u.right, committer, fileData,
                        0L, newSize, network, crypto, monitor))
                .thenCompose(v -> newSize >= size ?
                        Futures.of(v) :
                        getUpdated(v, network)
                                .thenCompose(f -> f.truncate(v, committer, newSize, network, crypto)));
    }

    public CompletableFuture<FileWrapper> overwriteFile(AsyncReader fileData,
                                                        long newSize,
                                                        NetworkAccess network,
                                                        Crypto crypto,
                                                        ProgressConsumer<Long> monitor) {
        return network.synchronizer.applyComplexUpdate(owner(), signingPair(),
                (s, committer) -> overwriteFile(fileData, newSize, network, crypto, monitor, version, committer))
                .thenCompose(v -> getUpdated(v, network));
    }

    @JsMethod
    public CompletableFuture<FileWrapper> overwriteSectionJS(AsyncReader fileData,
                                                             int startHigh,
                                                             int startLow,
                                                             int endHigh,
                                                             int endLow,
                                                             NetworkAccess network,
                                                             Crypto crypto,
                                                             ProgressConsumer<Long> monitor) {
        return network.synchronizer.applyComplexUpdate(owner(), signingPair(),
                (s, committer) -> overwriteSection(s, committer, fileData,
                        LongUtil.intsToLong(startHigh, startLow),
                        LongUtil.intsToLong(endHigh, endLow), network, crypto, monitor))
                .thenCompose(v -> getUpdated(v, network));
    }

    public static final class FileUploadProperties {
        public final String filename;
        public final Supplier<AsyncReader> fileData;
        public final long length;
        public final Optional<LocalDateTime> modifiedTime;
        public final Optional<HashTree> hash;
        public final boolean skipExisting;
        public final boolean overwriteExisting;
        public final ProgressConsumer<Long> monitor;

        @JsConstructor
        public FileUploadProperties(String filename,
                                    Supplier<AsyncReader> fileData,
                                    int lengthHi,
                                    int lengthLow,
                                    Optional<LocalDateTime> modifiedTime,
                                    Optional<HashTree> hash,
                                    boolean skipExisting,
                                    boolean overwriteExisting,
                                    ProgressConsumer<Long> monitor) {
            this.filename = filename;
            this.fileData = fileData;
            this.length = (((long)lengthHi) << 32) | (lengthLow & 0xFFFFFFFFL);
            this.modifiedTime = modifiedTime;
            this.hash = hash;
            this.skipExisting = skipExisting;
            this.overwriteExisting = overwriteExisting;
            this.monitor = monitor;
        }

        @Override
        public String toString() {
            return filename + " [" + length + "]";
        }
    }

    public static class FolderUploadProperties {
        public final List<String> relativePath;
        public final List<FileUploadProperties> files;

        @JsConstructor
        public FolderUploadProperties(List<String> relativePath, List<FileUploadProperties> files) {
            this.relativePath = relativePath;
            this.files = files;
        }

        public Path path() {
            return PathUtil.get(relativePath.stream().collect(Collectors.joining("/")));
        }
    }

    @JsMethod
    public CompletableFuture<FileWrapper> uploadSubtree(Stream<FolderUploadProperties> directories,
                                                        Optional<BatId> mirrorBat,
                                                        NetworkAccess network,
                                                        Crypto crypto,
                                                        TransactionService txns,
                                                        Function<FileUploadTransaction, CompletableFuture<Boolean>> resumeFile,
                                                        Supplier<Boolean> commitWatcher) {
        // only use the supplied mirror BAT if the parent doesn't have a mirror BAT
        Optional<BatId> mirror = mirrorBatId().or(() -> mirrorBat);
        return getPath(network).thenCompose(path ->
                network.synchronizer.applyComplexUpdate(owner(), signingPair(),
                        (s, c) -> {
                            return getUpdated(s, network).thenCompose(us -> Futures.reduceAll(directories, us,
                                    (dir, children) -> dir.getOrMkdirs(children.relativePath, false, mirror, network, crypto, dir.version, c)
                                            .thenCompose(p -> uploadFolder(PathUtil.get(path).resolve(children.path()), p.right,
                                                    children, mirrorBat, txns, resumeFile, commitWatcher, network, crypto, c)
                                                    .thenCompose(v -> dir.getUpdated(v, network))),
                                    (a, b) -> b))
                                    .thenApply(d -> d.version);
                        },
                        commitWatcher
                )).thenCompose(finished -> getUpdated(finished, network));
    }

    public static CompletableFuture<Snapshot> uploadFolder(Path toParent,
                                                           FileWrapper parent,
                                                           FolderUploadProperties children,
                                                           Optional<BatId> mirrorBat,
                                                           TransactionService transactions,
                                                           Function<FileUploadTransaction, CompletableFuture<Boolean>> resumeFile,
                                                           Supplier<Boolean> commitWatcher,
                                                           NetworkAccess network,
                                                           Crypto crypto,
                                                           Committer c) {
        Pair<Snapshot, List<NamedRelativeCapability>> identity = new Pair<>(parent.version, Collections.emptyList());

        // Upload in order of ascending file size
        List<FileUploadProperties> sortedChildren = children.files.stream()
                .sorted(Comparator.comparingLong(a -> a.length))
                .collect(Collectors.toList());
        return Futures.reduceAll(sortedChildren, identity,
                        (p, f) -> {
                            // don't bother with file upload transactions as single chunk uploads are atomic anyway
                            // (nothing to resume or cleanup later in case of failure)
                            AsyncReader fileData = f.fileData.get();
                            if (f.length <= Chunk.MAX_SIZE || transactions == null) // small files or writable public links
                                return parent.uploadFileSection(p.left, c, f.filename, fileData, Optional.empty(), false, 0, f.length, f.hash, f.modifiedTime,
                                                Optional.empty(), Optional.empty(), Optional.empty(), f.skipExisting,
                                                f.overwriteExisting, true, network.disableCommits(), crypto, f.monitor,
                                                crypto.random.randomBytes(32), Optional.empty(), Optional.of(Bat.random(crypto.random)), mirrorBat)
                                        .thenApply(pair -> new Pair<>(pair.left, Stream.concat(p.right.stream(), pair.right.stream()).collect(Collectors.toList())))
                                        .thenCompose(r -> {
                                            fileData.close();
                                            if (! network.isFull())
                                                return Futures.of(r);
                                            return atomicallyClearTransactionsAndAddToParent(Collections.emptyList(), r.right, parent, transactions, r.left, c, commitWatcher, network, crypto);
                                        });

                            network.enableCommits();
                            List<FileUploadTransaction> toClose = new ArrayList<>();
                            LocalDateTime now = LocalDateTime.now();
                            return calculateMimeType(fileData, f.length, f.filename).thenCompose(mimeType -> {
                                FileProperties props = new FileProperties(f.filename,
                                        false, false, mimeType, f.length,
                                        now, now, false, Optional.empty(), Optional.of(crypto.random.randomBytes(32)), Optional.empty());
                                return Transaction.buildFileUploadTransaction(toParent.resolve(f.filename).toString(), f.length,
                                        props, props.streamSecret.get(), SymmetricKey.random(), SymmetricKey.random(),
                                        SymmetricKey.random(), parent.signingPair(), new Location(parent.owner(), parent.writer(),
                                                crypto.random.randomBytes(32)), Optional.of(Bat.random(crypto.random)), crypto.hasher);
                            }).thenCompose(txn -> transactions.open(p.left, c, txn)
                                            .thenCompose(r -> {
                                                if (r.isB()) // we must clear legacy transactions which can't be resumed or ones whose parent has rotated writer
                                                    return (r.b().isLegacy() || ! parent.writer().equals(r.b().writer()) ? Futures.of(false) : resumeFile.apply(r.b()))
                                                            .thenCompose(resume -> {
                                                                if (resume) {
                                                                    toClose.add(r.b());
                                                                    return parent.resumeUpload(r.b(), fileData, f.monitor, p.left, c, network, crypto)
                                                                            .thenCompose(res -> fileData.reset().thenCompose(resetAgain ->
                                                                                            parent.generateThumbnailAndUpdate(res.left, c, r.b().writeCap(), f.filename, resetAgain,
                                                                                                    network, false, r.b().props.mimeType,
                                                                                                    f.length, r.b().startTime(), r.b().startTime(), Optional.of(r.b().streamSecret()), f.monitor))
                                                                                    .thenApply(s -> new Pair<>(s, res.right)));
                                                                }
                                                                return transactions.close(p.left, c, r.b())
                                                                        .thenCompose(s2 -> transactions.open(s2, c, txn))
                                                                        .thenCompose(r2 -> {
                                                                            if (r2.isB())
                                                                                throw new IllegalStateException("Error uploading file - concurrent upload of same file?");
                                                                            toClose.add(txn);
                                                                            return fileData.reset().thenCompose(reset -> parent.uploadFileSection(r2.a(), c, f.filename, reset, Optional.empty(),
                                                                                    false, 0, f.length, f.hash, f.modifiedTime, Optional.of(txn.baseKey), Optional.of(txn.dataKey), Optional.of(txn.writeKey),
                                                                                    f.skipExisting, f.overwriteExisting, true,
                                                                                    network, crypto, f.monitor, txn.firstMapKey(),
                                                                                    Optional.of(txn.streamSecret()), txn.firstBat, mirrorBat));
                                                                        });
                                                            }).thenApply(pair -> new Pair<>(pair.left, Stream.concat(p.right.stream(), pair.right.stream()).collect(Collectors.toList())));
                                                toClose.add(txn);
                                                return fileData.reset().thenCompose(reset -> parent.uploadFileSection(r.a(), c, f.filename, fileData, Optional.empty(), false,
                                                                0, f.length, f.hash, f.modifiedTime, Optional.of(txn.baseKey), Optional.of(txn.dataKey), Optional.of(txn.writeKey),f.skipExisting, f.overwriteExisting, true,
                                                                network, crypto, f.monitor, txn.firstMapKey(), Optional.of(txn.streamSecret()), txn.firstBat, mirrorBat))
                                                        .thenApply(pair -> new Pair<>(pair.left, Stream.concat(p.right.stream(), pair.right.stream()).collect(Collectors.toList())));
                                            })
                            ).thenCompose(r -> atomicallyClearTransactionsAndAddToParent(toClose, r.right, parent, transactions, r.left, c, commitWatcher, network, crypto))
                                    .thenApply(res -> {
                                        fileData.close();
                                        return res;
                                    });
                        },
                        (a, b) -> new Pair<>(b.left, Stream.concat(a.right.stream(), b.right.stream()).collect(Collectors.toList())))
                .thenCompose(r -> atomicallyClearTransactionsAndAddToParent(Collections.emptyList(), r.right, parent, transactions, r.left, c, commitWatcher, network, crypto))
                .thenApply(x -> x.left);
    }

    private static CompletableFuture<Pair<Snapshot, List<NamedRelativeCapability>>> atomicallyClearTransactionsAndAddToParent(
            List<FileUploadTransaction> toClose,
            List<NamedRelativeCapability> childLinks,
            FileWrapper parent,
            TransactionService transactions,
            Snapshot in,
            Committer c,
            Supplier<Boolean> commitWatcher,
            NetworkAccess network,
            Crypto crypto) {
        if (toClose.isEmpty() && childLinks.isEmpty())
            return Futures.of(new Pair<>(in, childLinks));
        return parent.getUpdated(in, network)
                .thenCompose(latest -> latest.addChildPointers(in, c, childLinks, network.disableCommits(), crypto))
                .thenCompose(res -> Futures.reduceAll(toClose, res, (v, f) -> transactions.close(v, c, f), (a, b) -> b))
                .thenApply(s -> {network.enableCommits(); return s;})
                .thenCompose(s -> network.isFull() ?
                        network.commit(parent.owner(), commitWatcher).thenApply(x -> s) :
                        Futures.of(s))
                .thenApply(s -> new Pair<>(s, Collections.<NamedRelativeCapability>emptyList()));
    }

    public Optional<BatId> mirrorBatId() {
        return pointer.fileAccess.mirrorBatId();
    }

    public CompletableFuture<Snapshot> overwriteSection(Snapshot current,
                                                        Committer committer,
                                                        AsyncReader fileData,
                                                        long inputStartIndex,
                                                        long endIndex,
                                                        NetworkAccess network,
                                                        Crypto crypto,
                                                        ProgressConsumer<Long> monitor) {
        if (! isWritable())
            return Futures.errored(new IllegalStateException("Unable to modify file without write access!"));
        if (isDirty())
            return Futures.errored(new IllegalStateException("File needs cleaning before modification."));

        FileProperties props = getFileProperties();
        String filename = props.name;
        LOG.info("Overwriting section [" + Long.toHexString(inputStartIndex) + ", " + Long.toHexString(endIndex) + "] of: " + filename);

        Supplier<Location> legacyLocs = () -> new Location(getLocation().owner, getLocation().writer, crypto.random.randomBytes(32));

        SymmetricKey parentParentKey = getPointer().getParentParentKey();
        Location parentLocation = getPointer().getParentCap().getLocation(owner(), writer());
        Optional<Bat> parentBat = getPointer().getParentCap().bat;
        WritableAbsoluteCapability ourCap = writableFilePointer();
        boolean updateTreeHash = inputStartIndex == 0 && endIndex >= props.size;
        HashTreeBuilder treeHasher = updateTreeHash ? new HashTreeBuilder(endIndex) : null;
        return current.withWriter(owner(), writer(), network)
                .thenCompose(base -> {
                    FileWrapper us = this;
                    final AtomicLong filesSize = new AtomicLong(props.size);
                    return us.getRetriever(crypto.hasher).thenCompose(retriever -> {
                        SymmetricKey baseKey = us.pointer.capability.rBaseKey;
                        CryptreeNode fileAccess = us.pointer.fileAccess;
                        SymmetricKey dataKey = fileAccess.getDataKey(baseKey);

                        List<Long> startIndexes = new ArrayList<>();

                        for (long startIndex = inputStartIndex; startIndex < endIndex; startIndex = startIndex + Chunk.MAX_SIZE - (startIndex % Chunk.MAX_SIZE))
                            startIndexes.add(startIndex);

                        BiFunction<Snapshot, Long, CompletableFuture<Snapshot>> composer = (version, startIndex) -> {
                            MaybeMultihash currentHash = us.pointer.fileAccess.committedHash();
                            return retriever.getChunk(version.get(us.writer()).props.get(), network, crypto, startIndex,
                                    filesSize.get(), ourCap, props.streamSecret, currentHash, monitor)
                                    .thenCompose(currentLocation -> {
                                                CompletableFuture<Optional<Pair<Location, Optional<Bat>>>> locationAt = retriever
                                                        .getMapLabelAt(version.get(us.writer()).props.get(), ourCap,
                                                                props.streamSecret, startIndex + Chunk.MAX_SIZE, crypto.hasher, network)
                                                        .thenApply(x -> x.map(mb -> new Pair<>(getLocation().withMapKey(mb.left), mb.right)));
                                                return locationAt.thenCompose(locationAndBat ->
                                                        CompletableFuture.completedFuture(new Pair<>(currentLocation, locationAndBat)));
                                            }
                                    ).thenCompose(pair -> {

                                        if (!pair.left.isPresent()) {
                                            CompletableFuture<Snapshot> result = new CompletableFuture<>();
                                            result.completeExceptionally(new IllegalStateException("Current chunk not present"));
                                            return result;
                                        }

                                        LocatedChunk currentOriginal = pair.left.get();
                                        Optional<Pair<Location, Optional<Bat>>> nextChunkLocationOpt = pair.right;
                                        CompletableFuture<Pair<Location, Optional<Bat>>> nextChunkLocationFut = nextChunkLocationOpt
                                                .map(Futures::of)
                                                .orElseGet(() -> props.streamSecret
                                                        .map(streamSecret -> FileProperties.calculateNextMapKey(streamSecret,
                                                                currentOriginal.location.getMapKey(), currentOriginal.bat, crypto.hasher)
                                                                .thenApply(nextMapKeyAndBat -> new Pair<>(us.getLocation().withMapKey(nextMapKeyAndBat.left), nextMapKeyAndBat.right)))
                                                        .orElseGet(() -> Futures.of(new Pair<>(legacyLocs.get(), Optional.empty()))));
                                        return nextChunkLocationFut.thenCompose(nextChunkLocationAndBat -> {
                                            Location nextChunkLocation = nextChunkLocationAndBat.left;
                                            Optional<Bat> nextChunkBat = nextChunkLocationAndBat.right;
                                            LOG.info("********** Writing to chunk at mapkey: " + ArrayOps.bytesToHex(currentOriginal.location.getMapKey()) + " next: " + nextChunkLocation);

                                            // modify chunk, re-encrypt and upload
                                            int internalStart = (int) (startIndex % Chunk.MAX_SIZE);
                                            int internalEnd = endIndex - (startIndex - internalStart) > Chunk.MAX_SIZE ?
                                                    Chunk.MAX_SIZE : (int) (endIndex - (startIndex - internalStart));
                                            byte[] rawData = currentOriginal.chunk.data();
                                            // extend data array if necessary
                                            if (rawData.length < internalEnd)
                                                rawData = Arrays.copyOfRange(rawData, 0, internalEnd);
                                            byte[] raw = rawData;
                                            Optional<SymmetricLinkToSigner> writerLink = startIndex < Chunk.MAX_SIZE ?
                                                    us.pointer.fileAccess.getWriterLink(us.pointer.capability.rBaseKey) :
                                                    Optional.empty();

                                            return fileData.readIntoArray(raw, internalStart, internalEnd - internalStart)
                                                    .thenCompose(read -> updateTreeHash ?
                                                            treeHasher.setChunk((int)(startIndex / Chunk.MAX_SIZE), raw, crypto.hasher).thenApply(x -> read) :
                                                            Futures.of(read))
                                                    .thenCompose(read -> {

                                                Chunk updated = new Chunk(raw, dataKey, currentOriginal.location.getMapKey(), dataKey.createNonce());
                                                LocatedChunk located = new LocatedChunk(currentOriginal.location, currentOriginal.bat, currentOriginal.existingHash, updated);
                                                long currentSize = filesSize.get();
                                                // remove hash from properties as we are changing the file
                                                FileProperties newProps = new FileProperties(props.name, false,
                                                        props.isLink, props.mimeType,
                                                        endIndex > currentSize ? endIndex : currentSize,
                                                        LocalDateTime.now(), props.created, props.isHidden,
                                                        props.thumbnail, props.streamSecret, Optional.empty());

                                                Optional<BatId> mirrorBat = mirrorBatId();
                                                CompletableFuture<Snapshot> chunkUploaded = FileUploader.uploadChunk(version, committer, us.signingPair(),
                                                        newProps, parentLocation, parentBat, parentParentKey, baseKey, located,
                                                        nextChunkLocation, nextChunkBat, writerLink, mirrorBat,
                                                        crypto.random, crypto.hasher, network, monitor);

                                                return chunkUploaded.thenCompose(updatedBase -> {
                                                    //update indices to be relative to next chunk
                                                    long updatedLength = startIndex + internalEnd - internalStart;
                                                    if (updatedLength > filesSize.get()) {
                                                        filesSize.set(updatedLength);

                                                        if (updatedLength > Chunk.MAX_SIZE) {
                                                            // update file size and remove treehash in FileProperties of first chunk
                                                            return network.getFile(updatedBase, ourCap, entryWriter, ownername)
                                                                    .thenCompose(updatedUs -> {
                                                                        FileProperties correctedSize = updatedUs.get()
                                                                                .getPointer().fileAccess.getProperties(ourCap.rBaseKey)
                                                                                .withSize(endIndex)
                                                                                .withHash(Optional.empty());
                                                                        return updatedUs.get()
                                                                                .getPointer().fileAccess.updateProperties(updatedBase,
                                                                                        committer, ourCap, entryWriter, correctedSize, network);
                                                                    });
                                                        }
                                                    }
                                                    return CompletableFuture.completedFuture(updatedBase);
                                                });
                                            });
                                        });
                                    });
                        };

                        return Futures.reduceAll(startIndexes, base, composer, (a, b) -> b)
                                .thenCompose(preHashVersion -> {
                                    if (! updateTreeHash)
                                        return Futures.of(preHashVersion);
                                    // update hash branches every 5 GiB
                                    return (endIndex == 0 ? treeHasher.setChunk(0, new byte[0], crypto.hasher) : Futures.of(true))
                                            .thenCompose(x -> treeHasher.complete(crypto.hasher))
                                            .thenCompose(treeHash -> network.getFile(preHashVersion, ourCap, entryWriter, ownername)
                                                    .thenCompose(updatedUs -> updatedUs.get()
                                                            .getHashUpdates(treeHash, network, crypto.hasher))
                                                    .thenCompose(hashUpdates -> FileWrapper.bulkSetSameNameProperties(preHashVersion, committer, owner(), hashUpdates, network))
                                            );
                                });
                    });
                });
    }

    public CompletableFuture<FileWrapper> uploadFileSection(String filename,
                                                            AsyncReader fileData,
                                                            boolean isHidden,
                                                            long startIndex,
                                                            long endIndex,
                                                            Optional<SymmetricKey> baseKey,
                                                            boolean overwriteExisting,
                                                            NetworkAccess network,
                                                            Crypto crypto,
                                                            ProgressConsumer<Long> monitor) {
        return uploadFileSection(filename, fileData, isHidden, startIndex, endIndex, baseKey, overwriteExisting,
                network, crypto, monitor, crypto.random.randomBytes(32), Optional.empty(),
                Optional.of(Bat.random(crypto.random)), mirrorBatId());
    }

    /**
     *
     * @param filename
     * @param fileData
     * @param isHidden
     * @param startIndex
     * @param endIndex
     * @param baseKey The desired base key for the uploaded file. If absent a random key is generated.
     * @param overwriteExisting
     * @param network
     * @param crypto
     * @param monitor A way to report back progress in number of bytes of file written
     * @param firstChunkMapKey The planned location for the first chunk
     * @return The updated version of this directory after the upload
     */
    public CompletableFuture<FileWrapper> uploadFileSection(String filename,
                                                            AsyncReader fileData,
                                                            boolean isHidden,
                                                            long startIndex,
                                                            long endIndex,
                                                            Optional<SymmetricKey> baseKey,
                                                            boolean overwriteExisting,
                                                            NetworkAccess network,
                                                            Crypto crypto,
                                                            ProgressConsumer<Long> monitor,
                                                            byte[] firstChunkMapKey,
                                                            Optional<byte[]> streamSecret,
                                                            Optional<Bat> firstBat,
                                                            Optional<BatId> mirrorBat) {
        if (isWritable())
            return network.synchronizer.applyComplexUpdate(owner(), signingPair(), (current, committer) ->
                    uploadFileSection(current, committer, filename, fileData, isHidden, startIndex, endIndex,
                            baseKey, false, overwriteExisting, false, network, crypto, monitor, firstChunkMapKey, streamSecret, firstBat, mirrorBat))
                    .thenCompose(finalBase -> getUpdated(finalBase, network));

        if (! overwriteExisting)
            return Futures.errored(new IllegalStateException("Cannot upload a file to a directory without write access!"));
        return getChild(filename, crypto.hasher, network)
                .thenCompose(c -> {
                    if (! c.isPresent())
                        return Futures.errored(new IllegalStateException("No child with name " + filename));
                    FileWrapper child = c.get();
                    return network.synchronizer.applyComplexUpdate(owner(), child.signingPair(),
                            (current, committer) -> updateExistingChild(current, committer, child,
                                    fileData, startIndex, endIndex, network, crypto, monitor))
                            .thenApply(childVersion -> withVersion(version.mergeAndOverwriteWith(childVersion)));
                });
    }

    private CompletableFuture<Snapshot> updateSize(Committer committer,
                                                   long newSize,
                                                   NetworkAccess network) {
        FileProperties newProps = getFileProperties().withSize(newSize);
        return updateProperties(version, committer, newProps, network);
    }

    @JsMethod
    public CompletableFuture<FileWrapper> updateThumbnail(String base64Str, NetworkAccess network) {
        Optional<Thumbnail> thumbData = Optional.empty();
        if (base64Str != null && base64Str.length() > 0) {
            thumbData = convertFromBase64(base64Str);
        }
        FileProperties updatedProperties = this.props.withThumbnail(thumbData);
        return network.synchronizer.applyComplexUpdate(owner(), signingPair(),
                (s, committer) -> updateProperties(s, committer, updatedProperties, network)
        ).thenCompose(finished -> getUpdated(finished, network));
    }

    public CompletableFuture<Snapshot> uploadFileSection(Snapshot initialVersion,
                                                         Committer committer,
                                                         String filename,
                                                         AsyncReader fileData,
                                                         boolean isHidden,
                                                         long startIndex,
                                                         long endIndex,
                                                         Optional<SymmetricKey> baseKey,
                                                         boolean skipExisting,
                                                         boolean overwriteExisting,
                                                         boolean truncateExisting,
                                                         NetworkAccess network,
                                                         Crypto crypto,
                                                         ProgressConsumer<Long> monitor,
                                                         byte[] firstChunkMapKey,
                                                         Optional<byte[]> streamSecret,
                                                         Optional<Bat> firstBat,
                                                         Optional<BatId> requestedMirrorBat) {
        return uploadFileSection(initialVersion, committer, filename, fileData, Optional.empty(), isHidden, startIndex, endIndex, Optional.empty(), Optional.empty(),
                baseKey, Optional.empty(), Optional.empty(), skipExisting, overwriteExisting, truncateExisting, network, crypto, monitor, firstChunkMapKey, streamSecret, firstBat, requestedMirrorBat)
                .thenCompose(p -> getUpdated(p.left, network)
                        .thenCompose(latest -> p.right.isEmpty() ?
                                Futures.of(p.left) :
                                latest.addChildPointer(p.left, committer, p.right.get(), network, crypto)));
    }

    private CompletableFuture<Pair<Snapshot, Optional<NamedRelativeCapability>>> uploadFileSection(
            Snapshot initialVersion,
            Committer committer,
            String filename,
            AsyncReader fileData,
            Optional<Thumbnail> existingThumbnail,
            boolean isHidden,
            long startIndex,
            long endIndex,
            Optional<HashTree> hash,
            Optional<LocalDateTime> modificationTime,
            Optional<SymmetricKey> requestedBaseKey,
            Optional<SymmetricKey> requestedDataKey,
            Optional<SymmetricKey> requestedWriteKey,
            boolean skipExisting,
            boolean overwriteExisting,
            boolean truncateExisting,
            NetworkAccess network,
            Crypto crypto,
            ProgressConsumer<Long> monitor,
            byte[] firstChunkMapKey,
            Optional<byte[]> streamSecret,
            Optional<Bat> firstBat,
            Optional<BatId> requestedMirrorBat) {
        if (!isLegalName(filename)) {
            return Futures.errored(new IllegalStateException("Illegal filename: " + filename));
        }
        if (! isDirectory()) {
            return Futures.errored(new IllegalStateException("Cannot upload a sub file to a file!"));
        }
        Optional<BatId> mirrorBat = mirrorBatId().or(() -> requestedMirrorBat);
        return initialVersion.withWriter(owner(), writer(), network)
                .thenCompose(current -> getUpdated(current, network)
                        .thenCompose(latest -> latest.getChild(current, filename, network)
                                        .thenCompose(childOpt -> {
                                            if (childOpt.isPresent()) {
                                                if (skipExisting)
                                                    return Futures.of(new Pair<>(current, Optional.empty()));
                                                if (! overwriteExisting)
                                                    throw new FileExistsException(filename);
                                                FileWrapper child = childOpt.get();
                                                FileProperties childProps = child.getFileProperties();

                                                TriFunction<FileWrapper, Snapshot, Long, CompletableFuture<Snapshot>> updatePropsIfNecessary =
                                                        (updatedChild, latestSnapshot, writeEnd) -> {
                                                    if (childProps.thumbnail.isEmpty()) {
                                                        if (writeEnd <= childProps.size)
                                                            return Futures.of(latestSnapshot);
                                                        // update size only
                                                        return updatedChild.updateSize(committer, writeEnd, network);
                                                    }
                                                    return updatedChild.getInputStream(latestSnapshot.get(updatedChild.writer()).props.get(), network, crypto, l -> {})
                                                            .thenCompose(is -> updatedChild.recalculateThumbnail(
                                                                latestSnapshot, committer, filename, is, isHidden,
                                                                updatedChild.getSize(), updatedChild.props.created, network, (WritableAbsoluteCapability)updatedChild.pointer.capability,
                                                                updatedChild.getFileProperties().streamSecret));
                                                };
                                                boolean redoMimetypeAndThumbnail = startIndex < 24;

                                                if (truncateExisting && endIndex < childProps.size) {
                                                    return child.truncate(current, committer, endIndex, network, crypto).thenCompose( updatedSnapshot ->
                                                        getUpdated(updatedSnapshot, network).thenCompose( updatedParent ->
                                                                child.getUpdated(updatedSnapshot, network).thenCompose( updatedChild ->
                                                                    updatedParent.updateExistingChild(updatedSnapshot, committer, updatedChild, fileData,
                                                                        startIndex, endIndex, network, crypto, monitor)
                                                                            .thenCompose(latestSnapshot ->  updatePropsIfNecessary.apply(updatedChild, latestSnapshot, endIndex)))))
                                                            .thenApply(s -> new Pair<>(s, Optional.<NamedRelativeCapability>empty()));
                                                } else {
                                                    return updateExistingChild(current, committer, child, fileData,
                                                            startIndex, endIndex, network, crypto, monitor)
                                                            .thenCompose(s -> redoMimetypeAndThumbnail ?
                                                                    child.getUpdated(s, network)
                                                                            .thenCompose(updatedChild -> updatedChild.getInputStream(s.get(updatedChild.writer()).props.get(), network, crypto, l -> {})
                                                                                    .thenCompose(is -> updatedChild.recalculateThumbnail(s, committer, filename, is, isHidden,
                                                                                            updatedChild.getSize(), updatedChild.props.created, network, (WritableAbsoluteCapability)updatedChild.pointer.capability,
                                                                                            updatedChild.getFileProperties().streamSecret))) :
                                                                    Futures.of(s))
                                                            .thenApply(s -> {
                                                                monitor.accept(THUMBNAIL_PROGRESS_OFFSET);
                                                                return new Pair<>(s, Optional.<NamedRelativeCapability>empty());
                                                            });
                                                }
                                            }
                                            if (startIndex > 0) {
                                                // TODO if startIndex > 0 prepend with a zero section
                                                throw new IllegalStateException("Unimplemented!");
                                            }
                                            SymmetricKey fileWriteKey = requestedWriteKey.orElseGet(SymmetricKey::random);
                                            SymmetricKey fileKey = requestedBaseKey.orElseGet(SymmetricKey::random);
                                            SymmetricKey dataKey = requestedDataKey.orElseGet(SymmetricKey::random);
                                            SymmetricKey rootRKey = latest.pointer.capability.rBaseKey;
                                            CryptreeNode dirAccess = latest.pointer.fileAccess;
                                            SymmetricKey dirParentKey = dirAccess.getParentKey(rootRKey);
                                            Location parentLocation = getLocation();
                                            Optional<Bat> parentBat = writableFilePointer().bat;
                                            LocalDateTime timestamp = modificationTime.orElseGet(LocalDateTime::now);
                                            return fileData.reset()
                                                    .thenCompose(reset -> calculateMimeType(reset, endIndex, filename)).thenCompose(mimeType -> fileData.reset()
                                                    .thenCompose(resetReader -> {
                                                        Optional<byte[]> actualStreamSecret = streamSecret.isPresent() ?
                                                                streamSecret :
                                                                Optional.of(crypto.random.randomBytes(32));
                                                        FileProperties fileProps = new FileProperties(filename,
                                                                false, false, mimeType, endIndex,
                                                                timestamp, timestamp, isHidden, existingThumbnail, actualStreamSecret, hash.map(t -> t.branch(0)));

                                                        FileUploader chunks = new FileUploader(filename, resetReader,
                                                                startIndex, endIndex, fileKey, dataKey, parentLocation, parentBat,
                                                                dirParentKey, monitor, fileProps, hash, firstChunkMapKey, firstBat);

                                                        SigningPrivateKeyAndPublicHash signer = signingPair();
                                                        WritableAbsoluteCapability fileWriteCap = new
                                                                WritableAbsoluteCapability(owner(),
                                                                signer.publicKeyHash,
                                                                firstChunkMapKey, firstBat, fileKey,
                                                                fileWriteKey);

                                                        return chunks.upload(current, committer, network, parentLocation.owner,
                                                                signer, mirrorBat, crypto.random, crypto.hasher)
                                                                .thenCompose(cwd -> fileData.reset().thenCompose(resetAgain ->
                                                                        generateThumbnailAndUpdate(cwd, committer, fileWriteCap, filename, resetAgain,
                                                                                network, isHidden, mimeType,
                                                                                endIndex, timestamp, timestamp, actualStreamSecret, monitor)))
                                                                .thenApply(s -> new Pair<>(s, Optional.of(new NamedRelativeCapability(filename, writableFilePointer().relativise(fileWriteCap)))));
                                                    }));
                                        })
                        )
                );
    }

    @JsMethod
    public CompletableFuture<Boolean> calculateAndUpdateThumbnail(NetworkAccess network, Crypto crypto) {
        return network.synchronizer.applyComplexComputation(owner(), signingPair(),
                (latestSnapshot, committer) -> getInputStream(latestSnapshot.get(writer()).props.get(), network, crypto, l -> {})
                        .thenCompose(is -> recalculateThumbnail(
                                latestSnapshot, committer, getName(), is, props.isHidden,
                                getSize(), props.created, network, (WritableAbsoluteCapability)pointer.capability,
                                getFileProperties().streamSecret))
                        .thenApply(res -> new Pair<>(res, true))
                        .exceptionally(ex -> new Pair<>(latestSnapshot, false))
        ).thenApply(p -> p.right);
    }

    private CompletableFuture<Snapshot> recalculateThumbnail(Snapshot snapshot, Committer committer, String filename,
                                                             AsyncReader fileData, boolean isHidden, long fileSize,
                                                             LocalDateTime createdDateTime, NetworkAccess network,
                                                             WritableAbsoluteCapability fileWriteCap, Optional<byte[]> streamSecret
    ) {
        return fileData.reset()
                .thenCompose(fileData2 -> calculateMimeType(fileData2, fileSize, filename)
                        .thenCompose(mimeType -> fileData.reset()
                                .thenCompose(resetAgain ->
                                    generateThumbnailAndUpdate(snapshot, committer, fileWriteCap, filename, resetAgain,
                                            network, isHidden, mimeType, fileSize, LocalDateTime.now(), createdDateTime, streamSecret, true, x -> {}))));
    }

    private CompletableFuture<Snapshot> generateThumbnailAndUpdate(Snapshot base,
                                                                   Committer committer,
                                                                   WritableAbsoluteCapability cap,
                                                                   String fileName,
                                                                   AsyncReader fileData,
                                                                   NetworkAccess network,
                                                                   Boolean isHidden,
                                                                   String mimeType,
                                                                   long fileSize,
                                                                   LocalDateTime updatedDateTime,
                                                                   LocalDateTime createdDateTime,
                                                                   Optional<byte[]> streamSecret,
                                                                   ProgressConsumer<Long> monitor) {
        return generateThumbnailAndUpdate(base, committer, cap, fileName, fileData, network, isHidden,
                mimeType, fileSize, updatedDateTime, createdDateTime, streamSecret, false, monitor);
    }

    private CompletableFuture<Snapshot> generateThumbnailAndUpdate(Snapshot base,
                                                                   Committer committer,
                                                                   WritableAbsoluteCapability cap,
                                                                   String fileName,
                                                                   AsyncReader fileData,
                                                                   NetworkAccess network,
                                                                   Boolean isHidden,
                                                                   String mimeType,
                                                                   long fileSize,
                                                                   LocalDateTime updatedDateTime,
                                                                   LocalDateTime createdDateTime,
                                                                   Optional<byte[]> streamSecret,
                                                                   boolean replaceExistingThumbnail,
                                                                   ProgressConsumer<Long> monitor) {
        return network.getFile(base, cap, getChildsEntryWriter(), ownername).thenCompose(fileOpt -> {
            if (replaceExistingThumbnail || fileOpt.get().props.thumbnail.isEmpty()) {
                return generateThumbnail(network, fileData, (int) Math.min(fileSize, Integer.MAX_VALUE), fileName, mimeType)
                        .thenCompose(thumbData -> {
                            if (thumbData.isEmpty() && mimeType.equals(fileOpt.get().getFileProperties().mimeType))
                                return Futures.of(base);
                            FileProperties fileProps = new FileProperties(fileName, false, props.isLink, mimeType, fileSize,
                                    updatedDateTime, createdDateTime, isHidden, thumbData, streamSecret, fileOpt.get().props.treeHash);

                            return fileOpt.get().updateProperties(base, committer, fileProps, network);
                        });
            } else {
                return Futures.of(base);
            }
        }).thenApply(s -> {
            monitor.accept(THUMBNAIL_PROGRESS_OFFSET);
            return s;
        });
    }

    private CompletableFuture<Snapshot> updateProperties(Snapshot base,
                                                         Committer committer,
                                                         FileProperties newProps,
                                                         NetworkAccess network) {
        return getPointer().fileAccess.updateProperties(base, committer, writableFilePointer(),
                getChildsEntryWriter(), newProps, network);
    }

    private CompletableFuture<Snapshot> addChildPointer(Snapshot current,
                                                        Committer committer,
                                                        NamedRelativeCapability childCap,
                                                        NetworkAccess network,
                                                        Crypto crypto) {
        return addChildPointers(current, committer, Collections.singletonList(childCap), network, crypto);
    }

    private CompletableFuture<Snapshot> addChildPointers(Snapshot current,
                                                        Committer committer,
                                                        List<NamedRelativeCapability> childCaps,
                                                        NetworkAccess network,
                                                        Crypto crypto) {
        return pointer.fileAccess.addChildrenAndCommit(current, committer,
                childCaps, writableFilePointer(), signingPair(), mirrorBatId(), network, crypto)
                .thenApply(newBase -> {
                    setModified();
                    return newBase;
                });
    }

    public CompletableFuture<FileWrapper> appendToChild(String filename,
                                                        long expectedSize,
                                                        byte[] fileData,
                                                        boolean isHidden,
                                                        Optional<BatId> mirrorBat,
                                                        NetworkAccess network,
                                                        Crypto crypto,
                                                        ProgressConsumer<Long> monitor) {
        return getChild(filename, crypto.hasher, network)
                .thenCompose(child -> child
                        .flatMap(c -> c.getFileProperties().streamSecret)
                        .map(secret -> FileProperties.calculateMapKey(secret,
                                child.get().getLocation().getMapKey(),
                                child.get().pointer.capability.bat,
                                child.get().getFileProperties().size, crypto.hasher)
                                .thenApply(p -> new Triple<>(p.left, p.right, Optional.of(secret))))
                        .orElseGet(() -> Futures.of(new Triple<>(crypto.random.randomBytes(32),
                                Optional.of(Bat.random(crypto.random)), Optional.empty())))
                        .thenCompose(x -> {
                            long size = child.map(f -> f.getSize()).orElse(0L);
                            if (size != expectedSize)
                                throw new IllegalStateException("File has been concurrently modified!");
                            return uploadFileSection(filename, AsyncReader.build(fileData), isHidden,
                                    size,
                                    fileData.length + size,
                                    child.map(f -> f.getPointer().capability.rBaseKey), true, network, crypto,
                                    monitor, x.left, x.right, x.middle, mirrorBat);
                        }));
    }

    public CompletableFuture<Snapshot> append(byte[] fileData,
                                              NetworkAccess network,
                                              Crypto crypto,
                                              Committer committer,
                                              ProgressConsumer<Long> progress) {
        long size = getSize();
        return overwriteSection(version, committer, AsyncReader.build(fileData), size, size + fileData.length, network, crypto, progress);
    }

    /**
     *
     * @param current
     * @param committer
     * @param existingChild
     * @param fileData
     * @param inputStartIndex
     * @param endIndex
     * @param network
     * @param crypto
     * @param monitor
     * @return The committed root for the parent (this) directory
     */
    private CompletableFuture<Snapshot> updateExistingChild(Snapshot current,
                                                            Committer committer,
                                                            FileWrapper existingChild,
                                                            AsyncReader fileData,
                                                            long inputStartIndex,
                                                            long endIndex,
                                                            NetworkAccess network,
                                                            Crypto crypto,
                                                            ProgressConsumer<Long> monitor) {

        FileProperties existingProps = existingChild.getFileProperties();
        String filename = existingProps.name;
        LOG.info("Overwriting section [" + Long.toHexString(inputStartIndex) + ", " + Long.toHexString(endIndex) + "] of child with name: " + filename);

        return current.withWriter(existingChild.owner(), existingChild.writer(), network)
                .thenCompose(state ->
                        existingChild.clean(state, committer, network, crypto)
                                .thenCompose(pair -> pair.left.overwriteSection(pair.right, committer, fileData,
                        inputStartIndex, endIndex, network, crypto, monitor)));
    }

    static boolean isLegalName(String name) {
        return !name.contains("/") && ! name.equals(".") && ! name.equals("..") && ! name.isEmpty();
    }

    /**
     *
     * @param newFolderName
     * @param network
     * @param isSystemFolder
     * @param crypto
     * @return An updated version of this directory
     */
    @JsMethod
    public CompletableFuture<FileWrapper> mkdir(String newFolderName,
                                                NetworkAccess network,
                                                boolean isSystemFolder,
                                                Optional<BatId> mirrorBat,
                                                Crypto crypto) {
        return mkdir(newFolderName, network, null, Optional.empty(), isSystemFolder, mirrorBatId().or(() -> mirrorBat), crypto);
    }

    public CompletableFuture<FileWrapper> mkdir(String newFolderName,
                                                NetworkAccess network,
                                                SymmetricKey requestedBaseSymmetricKey,
                                                Optional<Bat> desiredBat,
                                                boolean isSystemFolder,
                                                Optional<BatId> mirrorBat,
                                                Crypto crypto) {

        return network.synchronizer.applyComplexUpdate(owner(), signingPair(),
                (state, committer) -> mkdir(newFolderName, Optional.ofNullable(requestedBaseSymmetricKey),
                        Optional.empty(), Optional.empty(), desiredBat, isSystemFolder, mirrorBatId().or(() -> mirrorBat), network, crypto, state, committer))
                .thenCompose(version -> getUpdated(version, network));
    }

    public CompletableFuture<Snapshot> mkdir(String newFolderName,
                                             Optional<SymmetricKey> requestedBaseReadKey,
                                             Optional<SymmetricKey> requestedBaseWriteKey,
                                             Optional<byte[]> desiredMapKey,
                                             Optional<Bat> desiredBat,
                                             boolean isSystemFolder,
                                             Optional<BatId> mirrorBat,
                                             NetworkAccess network,
                                             Crypto crypto,
                                             Snapshot version,
                                             Committer committer) {

        if (!this.isDirectory()) {
            return Futures.errored(new IllegalStateException("Cannot mkdir in a file!"));
        }
        if (!isLegalName(newFolderName)) {
            return Futures.errored(new IllegalStateException("Illegal directory name: " + newFolderName));
        }
        Snapshot fullVersion = this.version.mergeAndOverwriteWith(version);
        return hasChildWithName(fullVersion, newFolderName, crypto.hasher, network).thenCompose(hasChild -> {
            if (hasChild) {
                return Futures.errored(new IllegalStateException("Child already exists with name: " + newFolderName));
            }
            return pointer.fileAccess.mkdir(fullVersion, committer, newFolderName, network, writableFilePointer(), getChildsEntryWriter(),
                    requestedBaseReadKey, requestedBaseWriteKey, desiredMapKey, desiredBat, isSystemFolder, mirrorBatId().or(() -> mirrorBat), crypto).thenApply(x -> {
                setModified();
                return x;
            });
        });
    }

    /** Get or create a descendant directory
     *
     * @param subPath
     * @param network
     * @param isSystemFolder
     * @param crypto
     * @return
     */
    @JsMethod
    public CompletableFuture<FileWrapper> getOrMkdirs(Path subPath,
                                                      NetworkAccess network,
                                                      boolean isSystemFolder,
                                                      Optional<BatId> mirrorBat,
                                                      Crypto crypto) {
        String finalPath = TrieNode.canonicalise(subPath.toString());
        List<String> elements = Arrays.asList(finalPath.split("/"));
        return network.synchronizer.applyComplexComputation(owner(), signingPair(),
                (state, committer) -> getOrMkdirs(elements, isSystemFolder, mirrorBat, network, crypto, state, committer))
                .thenApply(p -> p.right);
    }

    public CompletableFuture<Pair<Snapshot, FileWrapper>> getOrMkdirs(List<String> subPath,
                                                                      boolean isSystemFolder,
                                                                      Optional<BatId> mirrorBat,
                                                                      NetworkAccess network,
                                                                      Crypto crypto,
                                                                      Snapshot version,
                                                                      Committer committer) {
        return Futures.reduceAll(subPath, new Pair<>(version, this),
                (p, name) -> p.right.getOrMkdir(name, Optional.empty(), Optional.empty(), Optional.empty(),
                                Optional.empty(), isSystemFolder, p.right.mirrorBatId().or(() -> mirrorBat), network, crypto, p.left, committer),
                (a, b) -> b);
    }

    private CompletableFuture<Pair<Snapshot, FileWrapper>> getOrMkdir(String newFolderName,
                                                                      Optional<SymmetricKey> requestedBaseReadKey,
                                                                      Optional<SymmetricKey> requestedBaseWriteKey,
                                                                      Optional<byte[]> desiredMapKey,
                                                                      Optional<Bat> desiredBat,
                                                                      boolean isSystemFolder,
                                                                      Optional<BatId> mirrorBat,
                                                                      NetworkAccess network,
                                                                      Crypto crypto,
                                                                      Snapshot version,
                                                                      Committer committer) {

        if (! this.isDirectory()) {
            return Futures.errored(new IllegalStateException("Cannot mkdir in a file!"));
        }
        if (! isLegalName(newFolderName)) {
            return Futures.errored(new IllegalStateException("Illegal directory name: " + newFolderName));
        }
        Snapshot fullVersion = this.version.mergeAndOverwriteWith(version);
        return getChild(fullVersion, newFolderName, network).thenCompose(childOpt -> {
            if (childOpt.isPresent()) {
                return Futures.of(new Pair<>(fullVersion, childOpt.get()));
            }
            return pointer.fileAccess.mkdir(fullVersion, committer, newFolderName, network, writableFilePointer(), getChildsEntryWriter(),
                    requestedBaseReadKey, requestedBaseWriteKey, desiredMapKey, desiredBat, isSystemFolder, mirrorBat, crypto).thenCompose(x -> {
                setModified();
                return getUpdated(x, network).thenCompose(us -> us.getChild(newFolderName, crypto.hasher, network))
                        .thenApply(child -> new Pair<>(x, child.get()));
            });
        });
    }

    /**
     * @param newFilename
     * @param parent
     * @param userContext
     * @return the updated parent
     */
    @JsMethod
    public CompletableFuture<FileWrapper> rename(String newFilename,
                                                 FileWrapper parent,
                                                 Path ourPath,
                                                 UserContext userContext) {
        if (! isLegalName(newFilename))
            return CompletableFuture.completedFuture(parent);
        if (! parent.isWritable())
            return Futures.errored(new IllegalStateException("Unable to rename something without write access to the parent!"));
        CompletableFuture<Optional<FileWrapper>> childExists = parent == null ?
                CompletableFuture.completedFuture(Optional.empty()) :
                parent.getDescendentByPath(newFilename, userContext.crypto.hasher, userContext.network);
        ensureUnmodified();
        FileProperties currentProps = getFileProperties();
        setModified();
        return childExists
                .thenCompose(existing -> {
                    if (existing.isPresent())
                        throw new IllegalStateException("Cannot rename, child already exists with name: " + newFilename);

                    //get current props
                    RetrievedCapability ourPointer = linkPointer.orElse(pointer);
                    WritableAbsoluteCapability us = (WritableAbsoluteCapability) ourPointer.capability;
                    CryptreeNode nodeToUpdate = ourPointer.fileAccess;

                    boolean isDir = this.isDirectory();
                    boolean isLink = ourPointer.getProperties().isLink;
                    FileProperties newProps = new FileProperties(newFilename, isDir, isLink,
                            currentProps.mimeType, currentProps.size,
                            currentProps.modified, currentProps.created, currentProps.isHidden,
                            currentProps.thumbnail, currentProps.streamSecret, currentProps.treeHash);
                    SigningPrivateKeyAndPublicHash signer = isLink ? parent.signingPair() : signingPair();
                    return userContext.network.synchronizer.applyComplexUpdate(owner(), signer,
                            (s, committer) -> nodeToUpdate.updateProperties(s, committer, us,
                                    entryWriter, newProps, userContext.network)
                                    .thenCompose(updated -> parent.updateChildLinks(updated, committer,
                                            Arrays.asList(new Pair<>(us, new NamedAbsoluteCapability(newFilename, us))),
                                            userContext.network, userContext.crypto.random, userContext.crypto.hasher))
                                    .thenCompose(v -> userContext.isSecretLink() ? Futures.of(v) :
                                            userContext.sharedWithCache.rename(ourPath,
                                                    ourPath.getParent().resolve(newFilename), v, committer, userContext.network))
                    ).thenCompose(newVersion -> parent.getUpdated(newVersion, userContext.network));
                });
    }

    public CompletableFuture<Snapshot> addMirrorBat(BatId mirrorBat, boolean addToFragmentsOnly, NetworkAccess network) {
        return network.synchronizer.applyComplexUpdate(owner(), signingPair(),
                (s, committer) -> getPointer().fileAccess.addMirrorBat(s, committer, writableFilePointer(),
                        entryWriter, getFileProperties().streamSecret, mirrorBat, ! addToFragmentsOnly, network));
    }

    public CompletableFuture<Boolean> setProperties(FileProperties updatedProperties,
                                                    Hasher hasher,
                                                    NetworkAccess network,
                                                    Optional<FileWrapper> parent) {
        setModified();
        String newName = updatedProperties.name;
        if (!isLegalName(newName)) {
            return Futures.errored(new IllegalArgumentException("Illegal file name: " + newName));
        }
        return network.synchronizer.applyComplexUpdate(owner(), signingPair(),
                (s, comitter) -> (! parent.isPresent() ?
                        CompletableFuture.completedFuture(s) :
                        s.withWriter(owner(), parent.get().writer(), network)
                ).thenCompose(withParent -> parent.get().hasChildWithName(withParent, newName, hasher, network))
                        .thenCompose(hasChild -> ! hasChild ?
                                CompletableFuture.completedFuture(true) :
                                parent.get().getChildrenCapabilities(hasher, network)
                                        .thenApply(childCaps -> {
                                            if (! childCaps.stream()
                                                    .map(l -> new ByteArrayWrapper(l.cap.getMapKey()))
                                                    .collect(Collectors.toSet())
                                                    .contains(new ByteArrayWrapper(pointer.capability.getMapKey())))
                                                throw new IllegalStateException("Cannot rename to same name as an existing file");
                                            return true;
                                        })).thenCompose(x -> {
                            CryptreeNode fileAccess = pointer.fileAccess;
                            return fileAccess.updateProperties(s, comitter, writableFilePointer(),
                                    entryWriter, updatedProperties, network);
                        }))
                .thenApply(fa -> true);
    }

    public CompletableFuture<Boolean> setSameNameProperties(FileProperties updatedProperties,
                                                            NetworkAccess network) {
        String name = getName();
        setModified();
        String newName = updatedProperties.name;
        if (! newName.equals(name)) {
            return Futures.errored(new IllegalArgumentException("Can't rename file here: " + newName));
        }
        return network.synchronizer.applyComplexUpdate(owner(), signingPair(),
                        (s, c) -> pointer.fileAccess.updateProperties(s, c, writableFilePointer(), entryWriter, updatedProperties, network))
                .thenApply(fa -> true);
    }

    public static class PropsUpdate {
        public final WritableAbsoluteCapability cap;
        public final CryptreeNode meta;
        public final Optional<SigningPrivateKeyAndPublicHash> entryWriter;
        public final FileProperties newProps;

        public PropsUpdate(WritableAbsoluteCapability cap, CryptreeNode meta, Optional<SigningPrivateKeyAndPublicHash> entryWriter, FileProperties newProps) {
            this.cap = cap;
            this.meta = meta;
            this.entryWriter = entryWriter;
            this.newProps = newProps;
        }
    }

    public CompletableFuture<List<PropsUpdate>> getHashUpdates(HashTree hash, NetworkAccess network, Hasher hasher) {
        WritableAbsoluteCapability cap = writableFilePointer();
        long fileSize = getSize();
        long nBranches = fileSize == 0 ? 1 : (fileSize + 1024L * Chunk.MAX_SIZE - 1) / (1024L * Chunk.MAX_SIZE);
        byte[] streamSecret = getFileProperties().streamSecret.get();
        return Futures.combineAllInOrder(LongStream.range(0, nBranches)
                .mapToObj(b -> FileProperties.calculateMapKey(streamSecret, cap.getMapKey(), cap.bat, b * 1024 * Chunk.MAX_SIZE, hasher)
                        .thenCompose(loc -> {
                            WritableAbsoluteCapability chunkCap = cap.withMapKey(loc.left, loc.right);
                            long chunkIndex = b * 1024;
                            return network.getMetadata(version.get(writer()).props.get(), chunkCap)
                                    .thenApply(meta -> new PropsUpdate(chunkCap, meta.get(), entryWriter, meta.get().getProperties(chunkCap.rBaseKey)
                                            .withHash(Optional.of(hash.branch(chunkIndex)))));
                        }))
                .collect(Collectors.toList()));
    }

    public static CompletableFuture<Snapshot> bulkSetSameNameProperties(Snapshot s,
                                                                        Committer c,
                                                                        PublicKeyHash owner,
                                                                        List<PropsUpdate> updates,
                                                                        NetworkAccess network) {
        return Futures.reduceAll(updates, s,
                (v, p) -> v.withWriter(owner, p.cap.writer, network)
                        .thenCompose(v2 -> p.meta.updateProperties(v2, c, p.cap, p.entryWriter, p.newProps, network)),
                (a, b) -> a.mergeAndOverwriteWith(b));
    }

    public static CompletableFuture<Boolean> bulkSetSameNameProperties(List<PropsUpdate> updates,
                                                                       NetworkAccess network) {
        PropsUpdate first = updates.get(0);
        PublicKeyHash owner = first.cap.owner;
        SigningPrivateKeyAndPublicHash signer = first.meta.getSigner(first.cap.rBaseKey, first.cap.wBaseKey.get(), first.entryWriter);
        return network.synchronizer.applyComplexUpdate(owner, signer,
                        (s, c) -> bulkSetSameNameProperties(s, c, owner, updates, network))
                .thenApply(fa -> true);
    }

    @JsMethod
    public AbsoluteCapability readOnlyPointer() {
        return pointer.capability.readOnly();
    }

    public WritableAbsoluteCapability writableFilePointer() {
        if (! isWritable())
            throw new IllegalStateException("File is not writable!");
        return (WritableAbsoluteCapability) pointer.capability;
    }

    public SigningPrivateKeyAndPublicHash signingPair() {
        if (! isWritable())
            throw new IllegalStateException("File is not writable!");
        return pointer.capability.wBaseKey
                .map(w -> pointer.fileAccess.getSigner(pointer.capability.rBaseKey, w, entryWriter))
                .orElseGet(entryWriter::get);
    }

    @JsMethod
    public CompletableFuture<Boolean> moveTo(FileWrapper target, FileWrapper parent, Path ourPath, UserContext context, Supplier<CompletableFuture<Boolean>> preserveAccess) {
        ensureUnmodified();

        if (! parent.isWritable())
            return Futures.errored(new IllegalStateException("Cannot move file without write access to parent"));
        if (! target.isDirectory()) {
            return Futures.errored(new IllegalStateException("CopyTo target " + target + " must be a directory"));
        }
        // ensure we aren't trying to move a folder to a descendant folder, which will result in data loss!
        return target.getPath(context.network).thenCompose(targetPath -> {
            Path targetP = PathUtil.get(targetPath);
            if (targetP.startsWith(ourPath))
                throw new IllegalStateException("You cannot move a folder to a descendant folder");

            Optional<BatId> targetMirrorBatId = target.mirrorBatId()
                    .or(() -> target.owner().equals(context.signer.publicKeyHash) ?
                            context.mirrorBatId() :
                            Optional.empty());

            NetworkAccess net = context.network;
            Hasher hasher = context.crypto.hasher;

            return net.synchronizer.applyComplexUpdate(owner(), signingPair(),
                    (v, c) -> context.getPublicFile(ourPath).thenApply(opt ->  opt.isPresent())
                            .thenCompose(isPublic -> context.sharedWithCache.getAllDescendantShares(ourPath, v)
                                    .thenCompose(shared -> {
                                        return (isPublic || ! owner().equals(target.owner()) ?
                                                Futures.of(false) :
                                                shared.isEmpty() // fast path
                                                        ? Futures.of(true) : preserveAccess.get())
                                                .thenCompose(keepAccess -> {
                                                    boolean differentParentWriter = !target.writer().equals(parent.writer());
                                                    // TODO optimise different parent writer case by correcting owned keys
                                                    if (keepAccess && ! differentParentWriter) {
                                                        // just update parent and child pointers, no need to re-upload, rotate keys etc.
                                                        boolean differentWriter = ! target.writer().equals(writer());
                                                        boolean ourFile = context.signer != null && target.owner().equals(context.signer.publicKeyHash);
                                                        RelativeCapability newParentLink = new RelativeCapability(differentWriter ?
                                                                Optional.of(target.writer()) :
                                                                Optional.empty(),
                                                                target.getLocation().getMapKey(), target.writableFilePointer().bat, target.getParentKey(), Optional.empty());
                                                        CryptreeNode newMetadata = pointer.fileAccess.withParentLink(getParentKey(), newParentLink);
                                                        RelativeCapability ourNewcap = target.writableFilePointer().relativise(pointer.capability);
                                                        return IpfsTransaction.call(owner(),
                                                                tid -> target.getPath(net).thenCompose(newPath -> v.withWriter(owner(), target.writer(), net)
                                                                        .thenCompose(w -> net.uploadChunk(w, c, newMetadata, owner(), pointer.capability.getMapKey(), signingPair(), tid))
                                                                        .thenCompose(v2 -> target.pointer.fileAccess.addChildrenAndCommit(v2, c,
                                                                                Arrays.asList(new NamedRelativeCapability(getName(), ourNewcap)),
                                                                                target.writableFilePointer(), target.signingPair(), targetMirrorBatId, net, context.crypto))
                                                                        .thenCompose(v3 -> parent.pointer.fileAccess
                                                                                .removeChildren(v3, c, Arrays.asList(isLink() ? linkPointer.get().capability : getPointer().capability), parent.writableFilePointer(),
                                                                                        parent.entryWriter, net, context.crypto.random, hasher))
                                                                        .thenCompose(v4 -> ! ourFile || shared.isEmpty() ? Futures.of(v4) : context.sharedWithCache.clearSharedWith(ourPath, v4, c, net))
                                                                        .thenCompose(v5 -> ! ourFile || shared.isEmpty() ? Futures.of(v5) : context.sharedWithCache.addAllSharedWith(shared.entrySet().stream()
                                                                                .collect(Collectors.toMap(e ->  PathUtil.get(newPath).resolve(e.getKey().relativize(ourPath)), e -> e.getValue())), v5, c, net))),
                                                                net.dhtClient);

                                                    }
                                                    return version.withWriter(owner(), target.writer(), net)
                                                            .thenCompose(both -> copyTo(target, this.props.thumbnail, targetMirrorBatId, net, context.crypto, both, c))
                                                            .thenCompose(v2 -> version.withWriter(owner(), parent.writer(), net)
                                                                    .thenCompose(v3 -> parent.pointer.fileAccess
                                                                            .removeChildren(v2, c, isLink() ? Arrays.asList(linkPointer.get().capability) : Arrays.asList(getPointer().capability), parent.writableFilePointer(),
                                                                                    parent.entryWriter, net, context.crypto.random, hasher))
                                                                    .thenCompose(v4 -> IpfsTransaction.call(owner(),
                                                                                    tid -> FileWrapper.deleteAllChunks(
                                                                                            isLink() ?
                                                                                                    (WritableAbsoluteCapability) getLinkPointer().capability :
                                                                                                    writableFilePointer(),
                                                                                            parent.signingPair(), tid, hasher, net, v4, c), net.dhtClient)
                                                                            .thenCompose(v5 -> context.isSecretLink() ? Futures.of(v5) :
                                                                                    context.sharedWithCache.clearSharedWith(ourPath, v5, c, net)))
                                                            );
                                                });
                                    }))).thenApply(s -> true);
        });
    }

    @JsMethod
    public CompletableFuture<Boolean> copyTo(FileWrapper target, UserContext context) {
        ensureUnmodified();
        NetworkAccess network = context.network;
        Crypto crypto = context.crypto;
        if (! target.isDirectory()) {
            return Futures.errored(new IllegalStateException("CopyTo target " + target + " must be a directory"));
        }

        Optional<BatId> targetMirrorBatId = target.mirrorBatId()
                .or(() -> target.owner().equals(context.signer.publicKeyHash) ?
                        context.mirrorBatId() :
                        Optional.empty());
        return context.network.synchronizer.applyComplexUpdate(target.owner(), target.signingPair(),
                (version, committer) -> version.withWriter(owner(), writer(), network)
                        .thenCompose(both -> copyTo(target, this.props.thumbnail, targetMirrorBatId, network, crypto, both, committer)))
                .thenApply(newAccess -> true);
    }

    public CompletableFuture<Optional<HashTree>> getHash(NetworkAccess network, Hasher hasher) {
        long size = getSize();
        Optional<HashBranch> hash = getFileProperties().treeHash;
        if (hash.isEmpty())
            return Futures.of(Optional.empty());
        if (size < 1024 * Chunk.MAX_SIZE)
            return Futures.of(hash.map(t -> new HashTree(t.rootHash, t
                    .level1.stream().collect(Collectors.toList()),
                    t.level2.stream().collect(Collectors.toList()),
                    t.level3.stream().collect(Collectors.toList()))));
        long nBranches = (size + 1024 * Chunk.MAX_SIZE - 1) / (1024 * Chunk.MAX_SIZE);
        return Futures.combineAllInOrder(LongStream.range(0, nBranches).mapToObj(b -> {
            WritableAbsoluteCapability cap = writableFilePointer();
            return FileProperties.calculateMapKey(getFileProperties().streamSecret.get(),
                    cap.getMapKey(), cap.bat, b * 1024 * Chunk.MAX_SIZE, hasher).thenCompose(loc -> {
                WritableAbsoluteCapability chunkCap = cap.withMapKey(loc.left, loc.right);
                return network.getMetadata(version.get(writer()).props.get(), chunkCap)
                        .thenApply(meta -> meta.get().getProperties(chunkCap.rBaseKey).treeHash.get());
            });
        }).collect(Collectors.toList()))
                .thenApply(HashTree::fromBranches)
                .thenApply(Optional::of);
    }

    private CompletableFuture<Snapshot> copyTo(FileWrapper target,
                                              Optional<Thumbnail> existingThumbnail,
                                              Optional<BatId> targetMirrorBat,
                                              NetworkAccess network,
                                              Crypto crypto,
                                              Snapshot version,
                                              Committer committer) {
        if (! target.isDirectory()) {
            return Futures.errored(new IllegalStateException("CopyTo target " + target + " must be a directory"));
        }

        return target.hasChildWithName(version, getFileProperties().name, crypto.hasher, network).thenCompose(childExists -> {
            if (childExists) {
                return Futures.errored(new IllegalStateException("CopyTo target " + target + " already has child with name " + getFileProperties().name));
            }
            if (isDirectory()) {
                byte[] newMapKey = crypto.random.randomBytes(32);
                Optional<Bat> newBat = Optional.of(Bat.random(crypto.random));
                SymmetricKey newBaseR = SymmetricKey.random();
                SymmetricKey newBaseW = SymmetricKey.random();
                WritableAbsoluteCapability newCap = ((WritableAbsoluteCapability)target.getPointer().capability)
                        .withMapKey(newMapKey, newBat)
                        .withBaseKey(newBaseR)
                        .withBaseWriteKey(newBaseW);
                return withVersion(this.version.mergeAndOverwriteWith(version))
                        .getChildren(version, crypto.hasher, network, false).thenCompose(children ->
                        target.mkdir(getName(), Optional.of(newBaseR), Optional.of(newBaseW), Optional.of(newMapKey),
                                newBat, getFileProperties().isHidden, targetMirrorBat, network, crypto, version, committer)
                                .thenCompose(versionWithDir ->
                                        network.getFile(versionWithDir, newCap, target.getChildsEntryWriter(), target.ownername)
                                                .thenCompose(subTargetOpt -> {
                                                    FileWrapper newTarget = subTargetOpt.get();
                                                    return Futures.reduceAll(children, versionWithDir,
                                                            (s, child) -> newTarget.getUpdated(s, network)
                                                                    .thenCompose(updated ->
                                                                            child.copyTo(updated, child.getFileProperties().thumbnail, targetMirrorBat, network, crypto, s, committer)),
                                                            (a, b) -> a.merge(b));
                                                })));
            } else {
                return version.withWriter(owner(), writer(), network).thenCompose(snapshot ->
                        getInputStream(snapshot.get(writer()).props.get(), network, crypto, x -> {})
                                .thenCompose(stream -> getHash(network, crypto.hasher).thenCompose(hashTree -> target.uploadFileSection(snapshot, committer,
                                                getName(), stream, existingThumbnail, false, 0, getSize(), hashTree,
                                                Optional.of(getFileProperties().modified),
                                                Optional.empty(), Optional.empty(), Optional.empty(), false, false, false, network, crypto, x -> {},
                                                crypto.random.randomBytes(32), Optional.empty(),
                                                Optional.of(Bat.random(crypto.random)), targetMirrorBat))
                                        .thenCompose(p -> p.right.isEmpty() ?
                                                Futures.of(p.left) :
                                                target.addChildPointer(p.left, committer, p.right.get(), network, crypto))));
            }
        });
    }

    @JsMethod
    public CompletableFuture<Boolean> hasChild(String fileName, Hasher hasher, NetworkAccess network) {
        if (!isLegalName(fileName)) {
            return Futures.errored(new IllegalArgumentException("Illegal file/directory name: " + fileName));
        }
        return this.hasChildWithName(version, fileName, hasher, network);
    }

    public CompletableFuture<Boolean> hasChildren(NetworkAccess network) {
        return hasChildren(version, network);
    }

    public CompletableFuture<Boolean> hasChildren(Snapshot version,
                                                  NetworkAccess network) {
        if (capTrie.isPresent())
            return Futures.of(capTrie.get().isEmpty());
        return pointer.fileAccess.getAllChildrenCapabilities(version, pointer.capability, network.hasher, network)
                .thenApply(caps -> !caps.isEmpty());
    }

    /**
     * Move this file/dir and subtree to a new signing key pair.
     * @param signer
     * @param parent
     * @param network
     * @return The updated version of this file/dir and its parent
     */
    public CompletableFuture<Pair<FileWrapper, FileWrapper>> changeSigningKey(SigningPrivateKeyAndPublicHash signer,
                                                                              FileWrapper parent,
                                                                              NetworkAccess network,
                                                                              SafeRandom random,
                                                                              Hasher hasher) {
        ensureUnmodified();
        WritableAbsoluteCapability cap = (WritableAbsoluteCapability)getPointer().capability;
        SymmetricLinkToSigner signerLink = SymmetricLinkToSigner.fromPair(cap.wBaseKey.get(), signer);
        CryptreeNode fileAccess = getPointer().fileAccess;

        RelativeCapability newParentLink = new RelativeCapability(Optional.of(parent.writer()),
                parent.getLocation().getMapKey(), parent.writableFilePointer().bat, parent.getParentKey(), Optional.empty());
        CryptreeNode newFileAccess = fileAccess
                .withWriterLink(cap.rBaseKey, signerLink)
                .withParentLink(getParentKey(), newParentLink);
        WritableAbsoluteCapability ourNewCap = cap.withSigner(signer.publicKeyHash);
        RetrievedCapability newRetrievedCapability = new RetrievedCapability(ourNewCap, newFileAccess);

        // create the new signing subspace move subtree to it
        PublicKeyHash owner = owner();

        network.synchronizer.putEmpty(owner, signer.publicKeyHash);
        return network.synchronizer.applyComplexUpdate(owner, signer, (version, committer) -> IpfsTransaction.call(owner,
                tid -> network.uploadChunk(version, committer, newFileAccess, owner, getPointer().capability.getMapKey(), signer, tid)
                        .thenCompose(newVersion -> copyAllChunks(false, cap, signer, hasher, network, newVersion, committer))
                        .thenCompose(copiedVersion -> copiedVersion.withWriter(owner, parent.writer(), network))
                        .thenCompose(withParent -> parent.getPointer().fileAccess
                                .updateChildLink(withParent, committer, parent.writableFilePointer(),
                                        parent.signingPair(),
                                        getPointer(),
                                        newRetrievedCapability, network, random, hasher))
                        .thenCompose(updatedParentVersion -> deleteAllChunks(cap, signingPair(), tid, hasher, network,
                                updatedParentVersion, committer)),
                network.dhtClient)
        ).thenCompose(finalVersion -> parent.getUpdated(finalVersion, network)
                .thenCompose(updatedParent -> network.getFile(finalVersion, ourNewCap, Optional.of(signer), ownername)
                .thenApply(updatedUs -> new Pair<>(updatedUs.get(), updatedParent))));
    }

    /** This copies all the cryptree nodes from one signing key to another for a file or subtree
     *
     * @param includeFirst
     * @param currentCap
     * @param targetSigner
     * @param network
     * @return
     */
    private static CompletableFuture<Snapshot> copyAllChunks(boolean includeFirst,
                                                             AbsoluteCapability currentCap,
                                                             SigningPrivateKeyAndPublicHash targetSigner,
                                                             Hasher hasher,
                                                             NetworkAccess network,
                                                             Snapshot initialVersion,
                                                             Committer committer) {

        return initialVersion.withWriter(currentCap.owner, currentCap.writer, network)
                .thenCompose(version -> network.getMetadata(version.get(currentCap.writer).props.get(), currentCap)
                        .thenCompose(mOpt -> {
                            if (! mOpt.isPresent()) {
                                return CompletableFuture.completedFuture(version);
                            }
                            return (includeFirst ?
                                    IpfsTransaction.call(currentCap.owner,
                                            tid -> network.addPreexistingChunk(mOpt.get(), currentCap.owner, currentCap.getMapKey(),
                                                    targetSigner, tid, version, committer), network.dhtClient) :
                                    CompletableFuture.completedFuture(version))
                                    .thenCompose(updated -> {
                                        CryptreeNode chunk = mOpt.get();
                                        Optional<byte[]> streamSecret = chunk.getProperties(chunk
                                                .getParentKey(currentCap.rBaseKey)).streamSecret;
                                        return chunk.getNextChunkLocation(currentCap.rBaseKey,
                                                streamSecret, currentCap.getMapKey(), currentCap.bat, hasher)
                                                .thenCompose(nextChunkMapKeyAndBat ->
                                                        copyAllChunks(true, currentCap.withMapKey(nextChunkMapKeyAndBat.left, nextChunkMapKeyAndBat.right),
                                                                targetSigner, hasher, network, updated, committer));
                                    })
                                    .thenCompose(updatedVersion -> {
                                        if (! mOpt.get().isDirectory())
                                            return CompletableFuture.completedFuture(updatedVersion);
                                        return mOpt.get().getDirectChildrenCapabilities(currentCap, updatedVersion, network)
                                                .thenCompose(childCaps ->
                                                        Futures.reduceAll(childCaps,
                                                                updatedVersion,
                                                                (v, cap) -> copyAllChunks(true, cap.cap,
                                                                        targetSigner, hasher, network, v, committer),
                                                                (x, y) -> y));
                                    });
                        }));
    }

    public static CompletableFuture<Snapshot> deleteAllChunks(WritableAbsoluteCapability currentCap,
                                                              SigningPrivateKeyAndPublicHash signer,
                                                              TransactionId tid,
                                                              Hasher hasher,
                                                              NetworkAccess network,
                                                              Snapshot version,
                                                              Committer committer) {
        return version.withWriter(currentCap.owner, currentCap.writer, network)
                .thenCompose(current -> network.getMetadata(current.get(currentCap.writer).props.get(), currentCap)
                        .thenCompose(mOpt -> {
                            if (! mOpt.isPresent()) {
                                return CompletableFuture.completedFuture(current);
                            }
                            CryptreeNode chunk = mOpt.get();
                            SigningPrivateKeyAndPublicHash ourSigner = chunk
                                    .getSigner(currentCap.rBaseKey, currentCap.wBaseKey.get(), Optional.of(signer));
                            FileProperties props = chunk.getProperties(chunk
                                    .getParentKey(currentCap.rBaseKey));
                            Optional<byte[]> streamSecret = props.streamSecret;

                            boolean normalFile = ! chunk.isDirectory() && streamSecret.isPresent();
                            return (normalFile ?
                                    deleteFileChunks(props.streamSecret.get(), props.chunkCount(), currentCap, ourSigner, tid, hasher, network, current, committer) :
                                    network.deleteChunk(current, committer, chunk, currentCap.owner,
                                                    currentCap.getMapKey(), ourSigner, tid)
                                            .thenCompose(deletedVersion -> {
                                                return chunk.getNextChunkLocation(currentCap.rBaseKey, streamSecret,
                                                        currentCap.getMapKey(), currentCap.bat, hasher).thenCompose(nextChunkMapKeyAndBat ->
                                                        deleteAllChunks(currentCap.withMapKey(nextChunkMapKeyAndBat.left, nextChunkMapKeyAndBat.right), ourSigner, tid, hasher,
                                                                network, deletedVersion, committer));
                                            }))
                                    .thenCompose(updatedVersion -> {
                                        if (! chunk.isDirectory())
                                            return CompletableFuture.completedFuture(updatedVersion);
                                        return chunk.getDirectChildrenCapabilities(currentCap, updatedVersion, network).thenCompose(childCaps ->
                                                Futures.reduceAll(childCaps,
                                                        updatedVersion,
                                                        (v, cap) -> deleteAllChunks((WritableAbsoluteCapability) cap.cap, ourSigner,
                                                                tid, hasher, network, v, committer),
                                                        (x, y) -> y));
                                    })
                                    .thenCompose(s -> removeSigningKey(ourSigner, signer, currentCap.owner, network, s, committer));
                        }));
    }

    private static CompletableFuture<Snapshot> deleteFileChunks(byte[] streamSecret,
                                                                int nChunks,
                                                                WritableAbsoluteCapability startCap,
                                                                SigningPrivateKeyAndPublicHash ourSigner,
                                                                TransactionId tid,
                                                                Hasher hasher,
                                                                NetworkAccess network,
                                                                Snapshot current,
                                                                Committer c) {
        return getAllChunkLocations(startCap.getMapKey(), streamSecret, nChunks, hasher)
                .thenCompose(labels -> network.deleteAllChunksIfPresent(current, c, startCap.owner, ourSigner, labels, tid));
    }

    private static CompletableFuture<List<byte[]>> getAllChunkLocations(byte[] first, byte[] streamSecret, int nChunks, Hasher h) {
        List<byte[]> res = new ArrayList<>(nChunks);
        res.add(first);
        return Futures.reduceAll(IntStream.range(1, nChunks).mapToObj(i -> i), res,
                (labels, i) -> FileProperties.calculateNextMapKey(streamSecret, labels.get(labels.size() - 1), Optional.empty(), h)
                        .thenApply(next -> {
                            labels.add(next.left);
                            return labels;
                        }),
                (a, b) -> b);
    }

    @JsMethod
    public static CompletableFuture<FileWrapper> deleteChildren(FileWrapper parent,
                                                                Collection<FileWrapper> childrenToDelete,
                                                                Path parentPath,
                                                                UserContext context) {
        NetworkAccess network = context.network;
        Hasher hasher = context.crypto.hasher;
        parent.ensureUnmodified();
        if (! parent.pointer.capability.isWritable())
            return Futures.errored(new IllegalStateException("Cannot delete file without write access to it"));

        parent.setModified();
        network.disableCommits();
        PublicKeyHash owner = parent.owner();
        return network.synchronizer.applyComplexUpdate(owner, parent.signingPair(),
                (version, c) -> version.withWriter(owner, parent.writer(), network)
                .thenCompose(v2 -> parent.pointer.fileAccess
                        .removeChildren(v2, c, childrenToDelete.stream()
                                        .map(f -> f.isLink() ? f.linkPointer.get().capability : f.getPointer().capability)
                                        .collect(Collectors.toList()), parent.writableFilePointer(),
                                parent.entryWriter, network, context.crypto.random, hasher))
                        .thenCompose(v3 -> Futures.reduceAll(childrenToDelete, v3,
                        (s, f) ->  deleteChild(owner, parent, parentPath, f, s, c, context),
                        (a, b) -> a.mergeAndOverwriteWith(b))))
                .thenCompose(s -> parent.getUpdated(s, network));
    }

    private static CompletableFuture<Snapshot> deleteChild(PublicKeyHash owner,
                                                           FileWrapper parent,
                                                           Path parentPath,
                                                           FileWrapper child,
                                                           Snapshot version,
                                                           Committer c,
                                                           UserContext context) {
        Hasher hasher = context.crypto.hasher;
        NetworkAccess network = context.network;
        return IpfsTransaction.call(owner,
                        tid -> FileWrapper.deleteAllChunks(
                                child.isLink() ?
                                        (WritableAbsoluteCapability) child.getLinkPointer().capability :
                                        child.writableFilePointer(),
                                parent.isWritable() ?
                                        parent.signingPair() :
                                        child.signingPair(), tid, hasher, network, version, c), network.dhtClient)
                .thenCompose(s -> context.isSecretLink() ? Futures.of(s) :
                        context.sharedWithCache.clearSharedWith(parentPath.resolve(child.getName()), s, c, network));
    }

    /**
     * @param parent
     * @param context
     * @return updated parent
     */
    @JsMethod
    public CompletableFuture<FileWrapper> remove(FileWrapper parent, Path ourPath, UserContext context) {
        NetworkAccess network = context.network;
        Hasher hasher = context.crypto.hasher;
        ensureUnmodified();
        if (! pointer.capability.isWritable())
            return Futures.errored(new IllegalStateException("Cannot delete file without write access to it"));

        boolean writableParent = parent.isWritable();
        parent.setModified();
        network.disableCommits();
        return network.synchronizer.applyComplexUpdate(owner(), signingPair(),
                (v0, c) -> {
                    return (context.isSecretLink() ?
                            Futures.of(v0) :
                            context.sharedWithCache.getAllDescendantShares(ourPath, v0)
                                    .thenCompose(shares -> Futures.reduceAll(shares.entrySet().stream()
                                                    .flatMap(swe -> swe.getValue().links().entrySet().stream()
                                                            .flatMap(e -> e.getValue().stream().map(p -> new Pair<>(swe.getKey().resolve(e.getKey()), p)))), v0,
                                            (v, linkp) -> context.deleteSecretLink(linkp.right.label, linkp.left, v, c),
                                            (a, b) -> a.mergeAndOverwriteWith(b))))
                            .thenCompose(v1 -> (writableParent ? v1.withWriter(owner(), parent.writer(), network)
                                    .thenCompose(v2 -> parent.pointer.fileAccess
                                            .removeChildren(v2, c, Arrays.asList(isLink() ? linkPointer.get().capability : getPointer().capability), parent.writableFilePointer(),
                                                    parent.entryWriter, network, context.crypto.random, hasher)) :
                                    Futures.of(v1)))
                            .thenCompose(v -> IpfsTransaction.call(owner(),
                                            tid -> FileWrapper.deleteAllChunks(
                                                    isLink() ?
                                                            (WritableAbsoluteCapability) getLinkPointer().capability :
                                                            writableFilePointer(),
                                                    writableParent ?
                                                            parent.signingPair() :
                                                            signingPair(), tid, hasher, network, v, c), network.dhtClient)
                                    .thenCompose(s -> context.isSecretLink() ? Futures.of(s) :
                                            context.sharedWithCache.clearSharedWith(ourPath, s, c, network)));
                })
                .thenCompose(s -> parent.getUpdated(s, network));
    }

    public static CompletableFuture<Snapshot> removeSigningKey(SigningPrivateKeyAndPublicHash signerToRemove,
                                                               SigningPrivateKeyAndPublicHash parentSigner,
                                                               PublicKeyHash owner,
                                                               NetworkAccess network,
                                                               Snapshot current,
                                                               Committer committer) {
        PublicKeyHash parentWriter = parentSigner.publicKeyHash;
        if (parentWriter.equals(signerToRemove.publicKeyHash))
            return CompletableFuture.completedFuture(current);
        CommittedWriterData toRemove = current.get(signerToRemove.publicKeyHash);

        return current.withWriter(owner, parentWriter, network)
                .thenCompose(s -> s.get(parentSigner).props.get()
                        .removeOwnedKey(owner, parentSigner, signerToRemove.publicKeyHash, network.dhtClient, network.hasher)
                        .thenCompose(removed -> IpfsTransaction.call(
                                owner,
                                tid -> committer.commit(owner, parentSigner, removed, s.get(parentSigner), tid),
                                network.dhtClient))
                        .thenApply(committed -> s.withVersion(parentWriter, committed.get(parentWriter))))
                .thenCompose(s -> IpfsTransaction.call(owner,
                        tid -> committer.commit(owner, signerToRemove, Optional.empty(), toRemove, tid), network.dhtClient)
                        .thenApply(s::mergeAndOverwriteWith));
    }

    public CompletableFuture<? extends AsyncReader> getInputStream(NetworkAccess network,
                                                                   Crypto crypto,
                                                                   ProgressConsumer<Long> monitor) {
        return network.synchronizer.getValue(owner(), writer())
                .thenCompose(state -> getInputStream(state.get(writer()).props.get(), network, crypto, getFileProperties().size, 1, monitor));
    }

    public CompletableFuture<? extends AsyncReader> getInputStream(WriterData version,
                                                                   NetworkAccess network,
                                                                   Crypto crypto,
                                                                   ProgressConsumer<Long> monitor) {
        return getInputStream(version, network, crypto, getFileProperties().size, 1, monitor);
    }

    @JsMethod
    public CompletableFuture<? extends AsyncReader> getBufferedInputStream(NetworkAccess network,
                                                                           Crypto crypto,
                                                                           int fileSizeHi,
                                                                           int fileSizeLow,
                                                                           int nBufferedChunks,
                                                                           ProgressConsumer<Long> monitor) {
        return getInputStream(network, crypto, fileSize(fileSizeHi, fileSizeLow), nBufferedChunks, monitor);
    }

    private static long fileSize(int fileSizeHi, int fileSizeLow) {
        return (fileSizeLow & 0xFFFFFFFFL) + ((fileSizeHi & 0xFFFFFFFFL) << 32);
    }

    @JsMethod
    public CompletableFuture<? extends AsyncReader> getInputStream(NetworkAccess network,
                                                                   Crypto crypto,
                                                                   int fileSizeHi,
                                                                   int fileSizeLow,
                                                                   ProgressConsumer<Long> monitor) {
        return network.synchronizer.getValue(owner(), writer())
                .thenCompose(state -> getInputStream(state.get(writer()).props.get(), network, crypto, fileSize(fileSizeHi, fileSizeLow), 1, monitor));
    }

    public CompletableFuture<? extends AsyncReader> getInputStream(NetworkAccess network,
                                                                   Crypto crypto,
                                                                   long fileSize,
                                                                   ProgressConsumer<Long> monitor) {
        return getInputStream(network, crypto, fileSize, 1, monitor);
    }

    public CompletableFuture<? extends AsyncReader> getInputStream(NetworkAccess network,
                                                                   Crypto crypto,
                                                                   long fileSize,
                                                                   int nBufferedChunks,
                                                                   ProgressConsumer<Long> monitor) {
        return network.synchronizer.getValue(owner(), writer())
                .thenCompose(state -> getInputStream(state.get(writer()).props.get(), network, crypto, fileSize, nBufferedChunks, monitor));
    }

    public CompletableFuture<? extends AsyncReader> getInputStream(WriterData version,
                                                                   NetworkAccess network,
                                                                   Crypto crypto,
                                                                   long fileSize,
                                                                   int nBufferedChunks,
                                                                   ProgressConsumer<Long> monitor) {
        ensureUnmodified();
        if (pointer.fileAccess.isDirectory())
            throw new IllegalStateException("Cannot get input stream for a directory!");
        CryptreeNode fileAccess = pointer.fileAccess;
        return fileAccess.retriever(pointer.capability.rBaseKey, props.streamSecret, getLocation().getMapKey(), pointer.capability.bat, crypto.hasher)
                .thenCompose(retriever ->
                        retriever.getFile(version, network, crypto, pointer.capability, props.streamSecret,
                                fileSize, fileAccess.committedHash(), nBufferedChunks, monitor));
    }

    private CompletableFuture<FileRetriever> getRetriever(Hasher hasher) {
        if (pointer.fileAccess.isDirectory())
            throw new IllegalStateException("Cannot get input stream for a directory!");
        return pointer.fileAccess.retriever(pointer.capability.rBaseKey, props.streamSecret, getLocation().getMapKey(), pointer.capability.bat, hasher);
    }

    @JsMethod
    public String getBase64Thumbnail() {
        Optional<Thumbnail> thumbnail = props.thumbnail;
        if (thumbnail.isPresent()) {
            Thumbnail thumb = thumbnail.get();
            String base64Data = Base64.getEncoder().encodeToString(thumb.data);
            if (thumb.mimeType.equals("image/webp"))
                return "data:image/webp;base64," + base64Data;
            if (thumb.mimeType.equals("image/jpeg"))
                return "data:image/jpeg;base64," + base64Data;
            if (thumb.mimeType.equals("image/png"))
                return "data:image/png;base64," + base64Data;
            throw new IllegalStateException("Unknown thumbnail mimetype: " + thumb.mimeType);
        } else {
            return "";
        }
    }

    @JsMethod
    public FileProperties getFileProperties() {
        ensureUnmodified();
        return props;
    }

    @JsMethod
    public String getName() {
        return Optional.ofNullable(getFileProperties()).map(p -> p.name).orElse("/");
    }

    public long getSize() {
        return Optional.ofNullable(getFileProperties()).map(p -> p.size).orElse(0L);
    }

    public String toString() {
        return getName();
    }

    public static FileWrapper createRoot(TrieNode root) {
        return new FileWrapper(Optional.of(root), null, Optional.empty(), Optional.empty(), null, new Snapshot(new HashMap<>()));
    }

    public static Optional<Thumbnail> generateVideoThumbnail(byte[] videoBlob) {
        File tempFile = null;
        try {
            tempFile = File.createTempFile(UUID.randomUUID().toString(), ".mp4");
            Files.write(tempFile.toPath(), videoBlob, StandardOpenOption.WRITE);
            return VideoThumbnail.create(tempFile.getAbsolutePath(), THUMBNAIL_SIZE, THUMBNAIL_SIZE);
        } catch (IOException ioe) {
            LOG.log(Level.WARNING, ioe.getMessage(), ioe);
        } finally {
            if(tempFile != null) {
                try {
                    Files.delete(tempFile.toPath());
                }catch(IOException ioe){

                }
            }
        }
        return Optional.empty();
    }

    private static Optional<Thumbnail> convertFromBase64(String base64Url) {
        String base64data = base64Url.substring(base64Url.indexOf(",") + 1);
        byte[] data = Base64.getDecoder().decode(base64data);
        if (data.length == 0)
            return Optional.empty();
        if (base64Url.startsWith("data:image/jpeg;base64,"))
            return Optional.of(new Thumbnail("image/jpeg", data));
        if (base64Url.startsWith("data:image/webp;base64,"))
            return Optional.of(new Thumbnail("image/webp", data));
        throw new IllegalStateException("Unknown image type for generated thumbnail!");
    }

    private CompletableFuture<Optional<Thumbnail>> generateThumbnail(NetworkAccess network, AsyncReader fileData, int fileSize, String filename, String mimeType) {
        CompletableFuture<Optional<Thumbnail>> fut = new CompletableFuture<>();
        if (fileSize > MimeTypes.HEADER_BYTES_TO_IDENTIFY_MIME_TYPE) {
            if (mimeType.startsWith("image")) {
                if (network.isJavascript()) {
                    thumbnail.generateThumbnail(fileData, fileSize, filename).thenAccept(base64Str -> {
                        fut.complete(convertFromBase64(base64Str));
                    });
                } else {
                    byte[] bytes = new byte[fileSize];
                    fileData.readIntoArray(bytes, 0, fileSize).thenAccept(data -> {
                        fut.complete(ThumbnailGenerator.get().generateThumbnail(bytes));
                    }).exceptionally(t -> {
                        fut.complete(Optional.empty());
                        return null;
                    });
                }
            } else if (mimeType.startsWith("video")) {
                if (network.isJavascript()) {
                    thumbnail.generateVideoThumbnail(fileData, fileSize, filename, mimeType).thenAccept(base64Str -> {
                        if(base64Str == null) {
                            fut.complete(Optional.empty());
                        }
                        fut.complete(convertFromBase64(base64Str));
                    });
                } else {
                    // TODO find a cross platform way to generate (streaming) video thumbnails in Java
                    fut.complete(Optional.empty());
//                    byte[] bytes = new byte[fileSize];
//                    fileData.readIntoArray(bytes, 0, fileSize).thenAccept(data -> {
//                        fut.complete(generateVideoThumbnail(bytes));
//                    }).exceptionally(t -> {
//                        fut.complete(Optional.empty());
//                        return null;
//                    });
                }
            } else if (mimeType.startsWith("audio/mpeg")) {
                byte[] mp3Data = new byte[fileSize];
                fileData.readIntoArray(mp3Data, 0, fileSize).thenAccept(read -> {
                    try {
                        Mp3CoverImage mp3CoverImage = Mp3CoverImage.extractCoverArt(mp3Data);
                        if (mp3CoverImage.imageData == null) {
                            fut.complete(Optional.empty());
                        } else {
                            if (network.isJavascript()) {
                                AsyncReader.ArrayBacked imageBlob = new AsyncReader.ArrayBacked(mp3CoverImage.imageData);
                                thumbnail.generateThumbnail(imageBlob, mp3CoverImage.imageData.length, filename)
                                        .thenAccept(base64Str -> {
                                            fut.complete(convertFromBase64(base64Str));
                                        });
                            } else {
                                fut.complete(ThumbnailGenerator.get().generateThumbnail(mp3CoverImage.imageData));
                            }
                        }
                    } catch(Exception ex) {
                        fut.complete(Optional.empty());
                    }
                });
            } else {
                fut.complete(Optional.empty());
            }
        } else {
            fut.complete(Optional.empty());
        }
        return fut;
    }

    private static CompletableFuture<String> getFileType(AsyncReader imageBlob, String filename) {
        CompletableFuture<String> result = new CompletableFuture<>();
        byte[] data = new byte[MimeTypes.HEADER_BYTES_TO_IDENTIFY_MIME_TYPE];
        imageBlob.readIntoArray(data, 0, data.length).thenAccept(numBytesRead -> {
            imageBlob.reset().thenAccept(resetResult -> {
                if (numBytesRead < data.length) {
                    result.complete("");
                } else {
                    String mimeType = MimeTypes.calculateMimeType(data, filename);
                    result.complete(mimeType);
                }
            });
        });
        return result;
    }

    public static CompletableFuture<String> calculateMimeType(AsyncReader data, long fileSize, String filename) {
        byte[] header = new byte[(int) Math.min(fileSize, MimeTypes.HEADER_BYTES_TO_IDENTIFY_MIME_TYPE)];
        return data.readIntoArray(header, 0, header.length)
                .thenApply(read -> MimeTypes.calculateMimeType(header, filename));
    }
}
