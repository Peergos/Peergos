package peergos.shared.user.fs;
import java.nio.file.*;
import java.util.logging.*;

import jsinterop.annotations.*;
import peergos.shared.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.crypto.random.*;
import peergos.shared.crypto.symmetric.*;
import peergos.shared.inode.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.storage.*;
import peergos.shared.user.*;
import peergos.shared.user.fs.cryptree.*;
import peergos.shared.user.fs.transaction.*;
import peergos.shared.util.*;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.Graphics2D;
import java.awt.AlphaComposite;
import java.awt.RenderingHints;
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
	private static final Logger LOG = Logger.getGlobal();

    private final static int THUMBNAIL_SIZE = 100;
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
        if (pointer == null)
            props = new FileProperties("/", true, false, "", 0, LocalDateTime.MIN, false, Optional.empty(), Optional.empty());
        else {
            SymmetricKey parentKey = this.getParentKey();
            FileProperties directProps = pointer.fileAccess.getProperties(parentKey);
            if (linkPointer.isPresent()) {
                RetrievedCapability link = linkPointer.get();
                FileProperties linkProps = link.getProperties();
                this.props = directProps.withLink(linkProps);
            } else {
                this.props = directProps;
            }
        }
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
        return network.synchronizer.getValue(owner(), writer())
                .thenCompose(latest -> getUpdated(latest, network));

    }

    @JsMethod
    public boolean equals(Object other) {
        if (other == null)
            return false;
        if (!(other instanceof FileWrapper))
            return false;
        return pointer.equals(((FileWrapper) other).getPointer());
    }

    public CompletableFuture<FileWrapper> getUpdated(NetworkAccess network) {
        return network.synchronizer.getValue(owner(), writer())
                .thenCompose(v -> getUpdated(v, network));
    }

    public CompletableFuture<FileWrapper> getUpdated(Snapshot version, NetworkAccess network) {
        if (this.version.get(writer()).equals(version.get(writer())))
            return CompletableFuture.completedFuture(this);
        return network.getFile(version, pointer.capability, entryWriter, ownername)
                .thenApply(Optional::get)
                .thenApply(f -> f.withTrieNodeOpt(capTrie));
    }

    public PublicKeyHash owner() {
        try {
            return pointer.capability.owner;
        } catch (Exception e)  {
            System.out.println();
            throw e;
        }
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
        return props.name.equals("/");
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
                .thenCompose(reader -> crypto.hasher.hash(reader, getSize()));
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

        if (path.startsWith("/"))
            path = path.substring(1);
        int slash = path.indexOf("/");
        String prefix = slash > 0 ? path.substring(0, slash) : path;
        String suffix = slash > 0 ? path.substring(slash + 1) : "";
        return getChild(version, prefix, hasher, network).thenCompose(child -> {
            if (child.isPresent())
                return child.get().getDescendentByPath(suffix, child.get().version, hasher, network);
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
                        childCases, network, hasher);
    }

    public CompletableFuture<Boolean> hasChildWithName(Snapshot version, String name, Hasher hasher, NetworkAccess network) {
        ensureUnmodified();
        return getChild(version, name, hasher, network)
                .thenApply(Optional::isPresent);
    }

    /**
     *
     * @param child
     * @param network
     * @return Updated version of this directory without the child
     */
    public CompletableFuture<FileWrapper> removeChild(FileWrapper child, NetworkAccess network, Hasher hasher) {
        setModified();
        return network.synchronizer.applyComplexUpdate(owner(), signingPair(),
                (cwd, committer) -> pointer.fileAccess
                .removeChildren(cwd, committer, Arrays.asList(child.getPointer().capability), writableFilePointer(), entryWriter, network, hasher))
                .thenCompose(newRoot -> getUpdated(newRoot, network));
    }

    public CompletableFuture<Snapshot> removeChild(Snapshot version,
                                                   Committer committer,
                                                   FileWrapper child,
                                                   NetworkAccess network,
                                                   Hasher hasher) {
        return pointer.fileAccess.removeChildren(version, committer,
                Arrays.asList(child.getPointer().capability), writableFilePointer(), entryWriter, network, hasher);
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
            return capTrie.get().getChildren("/", hasher, network);
        return getChildren(version, hasher, network);
    }

    public CompletableFuture<Set<FileWrapper>> getChildren(Snapshot version, Hasher hasher, NetworkAccess network) {
        if (capTrie.isPresent())
            return capTrie.get().getChildren("/", hasher, version.merge(this.version), network);
        if (isReadable()) {
            Optional<SigningPrivateKeyAndPublicHash> childsEntryWriter = pointer.capability.wBaseKey
                    .map(wBase -> pointer.fileAccess.getSigner(pointer.capability.rBaseKey, wBase, entryWriter));
            return pointer.fileAccess.getAllChildrenCapabilities(version, pointer.capability, hasher, network)
                    .thenCompose(childCaps -> getFiles(owner(), childCaps, childsEntryWriter, ownername, network, version));
        }
        throw new IllegalStateException("Unreadable FileWrapper!");
    }

    private static CompletableFuture<Set<FileWrapper>> getFiles(PublicKeyHash owner,
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
                        .thenCompose(rcs -> Futures.combineAll(rcs.stream()
                                .map(rc -> {
                                    FileProperties props = rc.getProperties();
                                    if (! props.isLink)
                                        return Futures.of(new FileWrapper(rc, Optional.empty(), entryWriter, ownername, fullVersion));
                                    return NetworkAccess.getFileFromLink(owner, rc, entryWriter, ownername, network, version);
                                })
                                .collect(Collectors.toSet()))));
    }

    @JsMethod
    public CompletableFuture<Optional<FileWrapper>> getChild(String name, Hasher hasher, NetworkAccess network) {
        return getChild(version, name, hasher, network);
    }

    public CompletableFuture<Optional<FileWrapper>> getChild(Snapshot version,
                                                             String name,
                                                             Hasher hasher,
                                                             NetworkAccess network) {
        if (capTrie.isPresent())
            return capTrie.get().getByPath("/" + name, version.merge(this.version), hasher, network);
        return pointer.fileAccess.getChild(name, pointer.capability, version, hasher, network)
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
            return pointer.fileAccess.cleanAndCommit(current, committer, currentCap, currentCap, props.streamSecret,
                    Optional.empty(), signingPair(), SymmetricKey.random(),
                    parentOrLinkLocation, parentOrLinkParentKey, network, crypto)
                    .thenCompose(cwd -> {
                        setModified();
                        return getUpdated(cwd, network).thenApply(updated -> new Pair<>(updated, cwd));
                    });
        }
    }

    public CompletableFuture<byte[]> getMapKey(long offset, NetworkAccess network, Crypto crypto) {
        CryptreeNode fileAccess = pointer.fileAccess;
        return fileAccess.retriever(pointer.capability.rBaseKey, props.streamSecret, getLocation().getMapKey(), crypto.hasher)
                .thenCompose(retriever -> retriever
                        .getMapLabelAt(version.get(writer()).props, writableFilePointer(),
                                getFileProperties().streamSecret, offset, crypto.hasher, network)
                        .thenApply(Optional::get));
    }

    @JsMethod
    public CompletableFuture<FileWrapper> truncate(long newSize, NetworkAccess network, Crypto crypto) {
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
                        getInputStream(snapshot.get(writer()).props, network, crypto, props.size, x -> {}).thenCompose(originalReader -> {
                            long startOfLastChunk = newSize - (newSize % Chunk.MAX_SIZE);
                            return originalReader.seek(startOfLastChunk).thenCompose(seekedOriginal -> {
                                byte[] lastChunk = new byte[(int)(newSize % Chunk.MAX_SIZE)];
                                return seekedOriginal.readIntoArray(lastChunk, 0, lastChunk.length).thenCompose(read -> {
                                    if (newSize <= Chunk.MAX_SIZE)
                                        return CompletableFuture.completedFuture(snapshot);
                                    return IpfsTransaction.call(owner(), tid ->
                                                    deleteAllChunks(writableFilePointer().withMapKey(endMapKey),
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
    public CompletableFuture<FileWrapper> uploadFileJS(String filename,
                                                       AsyncReader fileData,
                                                       int lengthHi,
                                                       int lengthLow,
                                                       boolean overwriteExisting,
                                                       boolean truncateExisting,
                                                       NetworkAccess network,
                                                       Crypto crypto,
                                                       ProgressConsumer<Long> monitor,
                                                       TransactionService transactions) {
        long fileSize = LongUtil.intsToLong(lengthHi, lengthLow);
        if (transactions == null) // we are in a public writable link
            return network.synchronizer.applyComplexUpdate(owner(), signingPair(),
                    (s, committer) -> uploadFileSection(s, committer, filename, fileData,
                            false, 0, fileSize, Optional.empty(), overwriteExisting, truncateExisting,
                            network, crypto, monitor, crypto.random.randomBytes(32))
            ).thenCompose(finished -> getUpdated(finished, network));
        return getPath(network).thenCompose(path ->
                Transaction.buildFileUploadTransaction(Paths.get(path).resolve(filename).toString(), fileSize, fileData, signingPair(),
                        generateChildLocationsFromSize(fileSize, crypto.random)))
                .thenCompose(txn -> network.synchronizer.applyComplexUpdate(owner(), transactions.getSigner(),
                        (s, committer) -> transactions.open(s, committer, txn).thenCompose(v -> fileData.reset()
                                .thenCompose(reset -> uploadFileSection(v, committer, filename, reset,
                                        false, 0, fileSize, Optional.empty(), overwriteExisting, truncateExisting,
                                        network, crypto, monitor, txn.getLocations().get(0).getMapKey()))
                                .thenCompose(uploaded -> transactions.close(uploaded, committer, txn))
                        ))
                        .exceptionally(t -> {
                            // clean up after failed upload
                            network.synchronizer.applyComplexUpdate(owner(), transactions.getSigner(),
                                    (s, committer) -> transactions.close(s, committer, txn));
                            throw new RuntimeException(t);
                        })
                ).thenCompose(finished -> getUpdated(finished, network));
    }

    public CompletableFuture<FileWrapper> uploadOrReplaceFile(String filename,
                                                              AsyncReader fileData,
                                                              long length,
                                                              NetworkAccess network,
                                                              Crypto crypto,
                                                              ProgressConsumer<Long> monitor,
                                                              byte[] firstChunkMapKey) {
        return uploadFileSection(filename, fileData, false, 0, length, Optional.empty(),
                true, network, crypto, monitor, firstChunkMapKey)
                .thenCompose(f -> f.getChild(filename, crypto.hasher, network)
                        .thenCompose(childOpt -> childOpt.get().truncate(length, network, crypto))
                        .thenCompose(c -> f.getUpdated(f.version.mergeAndOverwriteWith(c.version), network)));
    }

    public CompletableFuture<FileWrapper> uploadAndReturnFile(String filename,
                                                              AsyncReader fileData,
                                                              long length,
                                                              boolean isHidden,
                                                              NetworkAccess network,
                                                              Crypto crypto) {
        return uploadFileSection(filename, fileData, isHidden, 0, length, Optional.empty(),
                true, network, crypto, x -> {}, crypto.random.randomBytes(32))
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

    public CompletableFuture<FileWrapper> overwriteFile(AsyncReader fileData,
                                                        long newSize,
                                                        NetworkAccess network,
                                                        Crypto crypto,
                                                        ProgressConsumer<Long> monitor) {
        long size = getSize();
        return network.synchronizer.applyComplexUpdate(owner(), signingPair(),
                (s, committer) -> clean(s, committer, network, crypto)
                        .thenCompose(u -> u.left.overwriteSection(u.right, committer, fileData,
                                0L, newSize, network, crypto, monitor))
                        .thenCompose(v -> newSize >= size ?
                                Futures.of(v) :
                                getUpdated(v, network)
                                        .thenCompose(f -> f.truncate(v, committer, newSize, network, crypto)))
        ).thenCompose(v -> getUpdated(v, network));
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
        WritableAbsoluteCapability ourCap = writableFilePointer();
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
                            return retriever.getChunk(version.get(us.writer()).props, network, crypto, startIndex,
                                    filesSize.get(), ourCap, props.streamSecret, currentHash, monitor)
                                    .thenCompose(currentLocation -> {
                                                CompletableFuture<Optional<Location>> locationAt = retriever
                                                        .getMapLabelAt(version.get(us.writer()).props, ourCap,
                                                                props.streamSecret, startIndex + Chunk.MAX_SIZE, crypto.hasher, network)
                                                        .thenApply(x -> x.map(m -> getLocation().withMapKey(m)));
                                                return locationAt.thenCompose(location ->
                                                        CompletableFuture.completedFuture(new Pair<>(currentLocation, location)));
                                            }
                                    ).thenCompose(pair -> {

                                        if (!pair.left.isPresent()) {
                                            CompletableFuture<Snapshot> result = new CompletableFuture<>();
                                            result.completeExceptionally(new IllegalStateException("Current chunk not present"));
                                            return result;
                                        }

                                        LocatedChunk currentOriginal = pair.left.get();
                                        Optional<Location> nextChunkLocationOpt = pair.right;
                                        CompletableFuture<Location> nextChunkLocationFut = nextChunkLocationOpt
                                                .map(Futures::of)
                                                .orElseGet(() -> props.streamSecret
                                                        .map(streamSecret -> FileProperties.calculateNextMapKey(streamSecret,
                                                                currentOriginal.location.getMapKey(), crypto.hasher)
                                                                .thenApply(nextMapKey -> us.getLocation().withMapKey(nextMapKey)))
                                                        .orElseGet(() -> Futures.of(legacyLocs.get())));
                                        return nextChunkLocationFut.thenCompose(nextChunkLocation -> {
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

                                            return fileData.readIntoArray(raw, internalStart, internalEnd - internalStart).thenCompose(read -> {

                                                Chunk updated = new Chunk(raw, dataKey, currentOriginal.location.getMapKey(), dataKey.createNonce());
                                                LocatedChunk located = new LocatedChunk(currentOriginal.location, currentOriginal.existingHash, updated);
                                                long currentSize = filesSize.get();
                                                FileProperties newProps = new FileProperties(props.name, false,
                                                        props.isLink, props.mimeType,
                                                        endIndex > currentSize ? endIndex : currentSize,
                                                        LocalDateTime.now(), props.isHidden,
                                                        props.thumbnail, props.streamSecret);

                                                CompletableFuture<Snapshot> chunkUploaded = FileUploader.uploadChunk(version, committer, us.signingPair(),
                                                        newProps, parentLocation, parentParentKey, baseKey, located,
                                                        nextChunkLocation, writerLink, crypto.hasher, network, monitor);

                                                return chunkUploaded.thenCompose(updatedBase -> {
                                                    //update indices to be relative to next chunk
                                                    long updatedLength = startIndex + internalEnd - internalStart;
                                                    if (updatedLength > filesSize.get()) {
                                                        filesSize.set(updatedLength);

                                                        if (updatedLength > Chunk.MAX_SIZE) {
                                                            // update file size in FileProperties of first chunk
                                                            return network.getFile(updatedBase, ourCap, entryWriter, ownername)
                                                                    .thenCompose(updatedUs -> {
                                                                        FileProperties correctedSize = updatedUs.get()
                                                                                .getPointer().fileAccess.getProperties(ourCap.rBaseKey)
                                                                                .withSize(endIndex);
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
                                .thenCompose(updatedBase -> {
                                    // update file size
                                    if (props.size >= endIndex)
                                        return CompletableFuture.completedFuture(updatedBase);
                                    WritableAbsoluteCapability cap = us.writableFilePointer();
                                    return network.getFile(updatedBase, cap, entryWriter, ownername)
                                            .thenCompose(updatedChild -> updatedChild.get()
                                                    .getPointer().fileAccess.updateProperties(updatedBase, committer, cap,
                                                            entryWriter, updatedChild.get().props.withSize(endIndex), network));
                                });
                    });
                });
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
                                                            byte[] firstChunkMapKey) {
        if (isWritable())
            return network.synchronizer.applyComplexUpdate(owner(), signingPair(), (current, committer) ->
                    uploadFileSection(current, committer, filename, fileData, isHidden, startIndex, endIndex,
                            baseKey, overwriteExisting, false, network, crypto, monitor, firstChunkMapKey))
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

    private CompletableFuture<Snapshot> uploadFileSection(Snapshot intialVersion,
                                                         Committer committer,
                                                         String filename,
                                                         AsyncReader fileData,
                                                         boolean isHidden,
                                                         long startIndex,
                                                         long endIndex,
                                                         Optional<SymmetricKey> baseKey,
                                                         boolean overwriteExisting,
                                                         NetworkAccess network,
                                                         Crypto crypto,
                                                         ProgressConsumer<Long> monitor,
                                                         byte[] firstChunkMapKey) {
        boolean truncateExisting = false;
        return uploadFileSection(intialVersion, committer, filename, fileData, isHidden, startIndex, endIndex, baseKey,
                overwriteExisting, truncateExisting, network, crypto, monitor, firstChunkMapKey);
    }

    private CompletableFuture<Snapshot> updateSize(Committer committer,
                                                   long newSize,
                                                   NetworkAccess network) {
        FileProperties newProps = getFileProperties().withSize(newSize);
        return updateProperties(version, committer, newProps, network);
    }

    @JsMethod
    public CompletableFuture<FileWrapper> updateThumbnail(String base64Str, NetworkAccess network) {
        byte[] thumbData = null;
        if (base64Str != null && base64Str.length() > 0) {
            String b64Thumb = base64Str.substring(base64Str.indexOf(',') + 1);
            thumbData = Base64.getDecoder().decode(b64Thumb);
        }
        FileProperties updatedProperties = thumbData == null ? this.props.withNoThumbnail()
                : this.props.withThumbnail(thumbData);
        return network.synchronizer.applyComplexUpdate(owner(), signingPair(),
                (s, committer) -> updateProperties(s, committer, updatedProperties, network)
        ).thenCompose(finished -> getUpdated(finished, network));
    }

    public CompletableFuture<Snapshot> uploadFileSection(Snapshot intialVersion,
                                                         Committer committer,
                                                         String filename,
                                                         AsyncReader fileData,
                                                         boolean isHidden,
                                                         long startIndex,
                                                         long endIndex,
                                                         Optional<SymmetricKey> baseKey,
                                                         boolean overwriteExisting,
                                                         boolean truncateExisting,
                                                         NetworkAccess network,
                                                         Crypto crypto,
                                                         ProgressConsumer<Long> monitor,
                                                         byte[] firstChunkMapKey) {
        if (!isLegalName(filename)) {
            CompletableFuture<Snapshot> res = new CompletableFuture<>();
            res.completeExceptionally(new IllegalStateException("Illegal filename: " + filename));
            return res;
        }
        if (! isDirectory()) {
            CompletableFuture<Snapshot> res = new CompletableFuture<>();
            res.completeExceptionally(new IllegalStateException("Cannot upload a sub file to a file!"));
            return res;
        }
        return intialVersion.withWriter(owner(), writer(), network)
                .thenCompose(current -> getUpdated(current, network)
                        .thenCompose(latest -> latest.getChild(current, filename, crypto.hasher, network)
                                        .thenCompose(childOpt -> {
                                            if (childOpt.isPresent()) {
                                                if (! overwriteExisting)
                                                    throw new IllegalStateException("File already exists with name " + filename);
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
                                                    return updatedChild.getInputStream(latestSnapshot.get(updatedChild.writer()).props, network, crypto, l -> {})
                                                            .thenCompose(is -> updatedChild.recalculateThumbnail(
                                                                latestSnapshot, committer, filename, is, isHidden,
                                                                updatedChild.getSize(), network, (WritableAbsoluteCapability)updatedChild.pointer.capability,
                                                                updatedChild.getFileProperties().streamSecret));
                                                };

                                                if (truncateExisting && endIndex < childProps.size) {
                                                    return child.truncate(current, committer, endIndex, network, crypto).thenCompose( updatedSnapshot ->
                                                        getUpdated(updatedSnapshot, network).thenCompose( updatedParent ->
                                                                child.getUpdated(updatedSnapshot, network).thenCompose( updatedChild ->
                                                                    updateExistingChild(updatedSnapshot, committer, updatedChild, fileData,
                                                                        startIndex, endIndex, network, crypto, monitor)
                                                                            .thenCompose(latestSnapshot ->  updatePropsIfNecessary.apply(updatedChild, latestSnapshot, endIndex)))));
                                                } else {
                                                    return updateExistingChild(current, committer, child, fileData,
                                                            startIndex, endIndex, network, crypto, monitor)
                                                            .thenCompose( updatedSnapshot -> child.getUpdated(updatedSnapshot, network)
                                                                    .thenCompose(updatedChild ->
                                                                            updatePropsIfNecessary.apply(updatedChild, updatedSnapshot, endIndex)));
                                                }
                                            }
                                            if (startIndex > 0) {
                                                // TODO if startIndex > 0 prepend with a zero section
                                                throw new IllegalStateException("Unimplemented!");
                                            }
                                            SymmetricKey fileWriteKey = SymmetricKey.random();
                                            SymmetricKey fileKey = baseKey.orElseGet(SymmetricKey::random);
                                            SymmetricKey dataKey = SymmetricKey.random();
                                            SymmetricKey rootRKey = latest.pointer.capability.rBaseKey;
                                            CryptreeNode dirAccess = latest.pointer.fileAccess;
                                            SymmetricKey dirParentKey = dirAccess.getParentKey(rootRKey);
                                            Location parentLocation = getLocation();

                                            return calculateMimeType(fileData, endIndex, filename).thenCompose(mimeType -> fileData.reset()
                                                    .thenCompose(resetReader -> {
                                                        Optional<byte[]> streamSecret = Optional.of(crypto.random.randomBytes(32));
                                                        FileProperties fileProps = new FileProperties(filename,
                                                                false, false, mimeType, endIndex,
                                                                LocalDateTime.now(), isHidden, Optional.empty(), streamSecret);

                                                        FileUploader chunks = new FileUploader(filename, mimeType, resetReader,
                                                                startIndex, endIndex, fileKey, dataKey, parentLocation,
                                                                dirParentKey, monitor, fileProps, firstChunkMapKey);

                                                        SigningPrivateKeyAndPublicHash signer = signingPair();
                                                        WritableAbsoluteCapability fileWriteCap = new
                                                                WritableAbsoluteCapability(owner(),
                                                                signer.publicKeyHash,
                                                                firstChunkMapKey, fileKey,
                                                                fileWriteKey);

                                                        return chunks.upload(current, committer, network, parentLocation.owner, signer, crypto.hasher)
                                                                .thenCompose(updatedWD -> latest.addChildPointer(updatedWD,
                                                                        committer, fileWriteCap, new PathElement(filename), network, crypto))
                                                                .thenCompose(cwd -> fileData.reset().thenCompose(resetAgain ->
                                                                        generateThumbnailAndUpdate(cwd, committer, fileWriteCap, filename, resetAgain,
                                                                                network, isHidden, mimeType,
                                                                                endIndex, LocalDateTime.now(), streamSecret)));
                                                    }));
                                        })
                        )
                );
    }

    private CompletableFuture<Snapshot> recalculateThumbnail(Snapshot snapshot, Committer committer, String filename, AsyncReader fileData
             , boolean isHidden, long fileSize, NetworkAccess network, WritableAbsoluteCapability fileWriteCap, Optional<byte[]> streamSecret
    ) {
        return fileData.reset()
                .thenCompose(fileData2 -> calculateMimeType(fileData2, fileSize, filename)
                        .thenCompose(mimeType -> fileData.reset()
                                .thenCompose(resetAgain ->
                                    generateThumbnailAndUpdate(snapshot, committer, fileWriteCap, filename, resetAgain,
                                            network, isHidden, mimeType, fileSize, LocalDateTime.now(), streamSecret))));
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
                                                                   Optional<byte[]> streamSecret) {
        return generateThumbnail(network, fileData, (int) Math.min(fileSize, Integer.MAX_VALUE), fileName, mimeType)
                .thenCompose(thumbData -> {
                    FileProperties fileProps = new FileProperties(fileName, false, props.isLink, mimeType, fileSize,
                            updatedDateTime, isHidden, thumbData, streamSecret);

                    return network.getFile(base, cap, getChildsEntryWriter(), ownername)
                            .thenCompose(child -> child.get().updateProperties(base, committer, fileProps, network));
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
                                                        WritableAbsoluteCapability childPointer,
                                                        PathElement filename,
                                                        NetworkAccess network,
                                                        Crypto crypto) {
        NamedRelativeCapability namedCap = new NamedRelativeCapability(filename, writableFilePointer().relativise(childPointer));
        List<NamedRelativeCapability> childCaps = Collections.singletonList(namedCap);
        return pointer.fileAccess.addChildrenAndCommit(current, committer,
                childCaps, writableFilePointer(), signingPair(), network, crypto)
                .thenApply(newBase -> {
                    setModified();
                    return newBase;
                });
    }

    @JsMethod
    public CompletableFuture<FileWrapper> appendToChild(String filename,
                                                        byte[] fileData,
                                                        boolean isHidden,
                                                        NetworkAccess network,
                                                        Crypto crypto,
                                                        ProgressConsumer<Long> monitor) {
        return getChild(filename, crypto.hasher, network)
                .thenCompose(child -> child
                        .flatMap(c -> c.getFileProperties().streamSecret)
                        .map(secret -> FileProperties.calculateMapKey(secret,
                                child.get().getLocation().getMapKey(),
                                child.get().getFileProperties().size, crypto.hasher))
                        .orElseGet(() -> Futures.of(crypto.random.randomBytes(32)))
                        .thenCompose(x -> uploadFileSection(filename, AsyncReader.build(fileData), isHidden,
                                child.map(f -> f.getSize()).orElse(0L),
                                fileData.length + child.map(f -> f.getSize()).orElse(0L),
                                child.map(f -> f.getPointer().capability.rBaseKey), true, network, crypto,
                                monitor, x)));
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
                                                Crypto crypto) {
        return mkdir(newFolderName, network, null, isSystemFolder, crypto);
    }

    public CompletableFuture<FileWrapper> mkdir(String newFolderName,
                                                NetworkAccess network,
                                                SymmetricKey requestedBaseSymmetricKey,
                                                boolean isSystemFolder,
                                                Crypto crypto) {

        return network.synchronizer.applyComplexUpdate(owner(), signingPair(),
                (state, committer) -> mkdir(newFolderName, Optional.ofNullable(requestedBaseSymmetricKey),
                        Optional.empty(), Optional.empty(), isSystemFolder, network, crypto, state, committer))
                .thenCompose(version -> getUpdated(version, network));
    }

    private CompletableFuture<Snapshot> mkdir(String newFolderName,
                                              Optional<SymmetricKey> requestedBaseReadKey,
                                              Optional<SymmetricKey> requestedBaseWriteKey,
                                              Optional<byte[]> desiredMapKey,
                                              boolean isSystemFolder,
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
        return hasChildWithName(version, newFolderName, crypto.hasher, network).thenCompose(hasChild -> {
            if (hasChild) {
                return Futures.errored(new IllegalStateException("Child already exists with name: " + newFolderName));
            }
            return pointer.fileAccess.mkdir(version, committer, newFolderName, network, writableFilePointer(), getChildsEntryWriter(),
                    requestedBaseReadKey, requestedBaseWriteKey, desiredMapKey, isSystemFolder, crypto).thenApply(x -> {
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
    public CompletableFuture<FileWrapper> getOrMkdirs(Path subPath,
                                                      NetworkAccess network,
                                                      boolean isSystemFolder,
                                                      Crypto crypto) {
        String finalPath = TrieNode.canonicalise(subPath.toString());
        List<String> elements = Arrays.asList(finalPath.split("/"));
        return network.synchronizer.applyComplexComputation(owner(), signingPair(),
                (state, committer) -> getOrMkdirs(elements, isSystemFolder, network, crypto, state, committer))
                .thenApply(p -> p.right);
    }

    private CompletableFuture<Pair<Snapshot, FileWrapper>> getOrMkdirs(List<String> subPath,
                                                                      boolean isSystemFolder,
                                                                      NetworkAccess network,
                                                                      Crypto crypto,
                                                                      Snapshot version,
                                                                      Committer committer) {
        return Futures.reduceAll(subPath, new Pair<>(version, this), (p, name) -> p.right.getOrMkdir(name,
                Optional.empty(), Optional.empty(), Optional.empty(), isSystemFolder, network, crypto, p.left, committer),
                (a, b) -> b);
    }

    private CompletableFuture<Pair<Snapshot, FileWrapper>> getOrMkdir(String newFolderName,
                                                                      Optional<SymmetricKey> requestedBaseReadKey,
                                                                      Optional<SymmetricKey> requestedBaseWriteKey,
                                                                      Optional<byte[]> desiredMapKey,
                                                                      boolean isSystemFolder,
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
        return getChild(version, newFolderName, crypto.hasher, network).thenCompose(childOpt -> {
            if (childOpt.isPresent()) {
                return Futures.of(new Pair<>(version, childOpt.get()));
            }
            return pointer.fileAccess.mkdir(version, committer, newFolderName, network, writableFilePointer(), getChildsEntryWriter(),
                    requestedBaseReadKey, requestedBaseWriteKey, desiredMapKey, isSystemFolder, crypto).thenCompose(x -> {
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
                            currentProps.modified, currentProps.isHidden,
                            currentProps.thumbnail, currentProps.streamSecret);
                    SigningPrivateKeyAndPublicHash signer = isLink ? parent.signingPair() : signingPair();
                    return userContext.network.synchronizer.applyComplexUpdate(owner(), signer,
                            (s, committer) -> nodeToUpdate.updateProperties(s, committer, us,
                                    entryWriter, newProps, userContext.network)
                                    .thenCompose(updated -> parent.updateChildLinks(updated, committer,
                                            Arrays.asList(new Pair<>(us, new NamedAbsoluteCapability(newFilename, us))),
                                            userContext.network, userContext.crypto.random, userContext.crypto.hasher)))
                            .thenCompose(newVersion -> parent.getUpdated(newVersion, userContext.network));
                }).thenCompose(f -> userContext.sharedWithCache
                        .rename(ourPath, ourPath.getParent().resolve(newFilename))
                        .thenApply(b -> f));
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
    public CompletableFuture<Boolean> moveTo(FileWrapper target, FileWrapper parent, Path ourPath, UserContext context) {
        return copyTo(target, context)
                .thenCompose(fw -> remove(parent, ourPath, context))
                .thenApply(newAccess -> true);
    }

    @JsMethod
    public CompletableFuture<Boolean> copyTo(FileWrapper target, UserContext context) {
        ensureUnmodified();
        NetworkAccess network = context.network;
        Crypto crypto = context.crypto;
        if (! target.isDirectory()) {
            return Futures.errored(new IllegalStateException("CopyTo target " + target + " must be a directory"));
        }

        return context.network.synchronizer.applyComplexUpdate(target.owner(), target.signingPair(),
                (version, committer) -> version.withWriter(owner(), writer(), network)
                        .thenCompose(both -> copyTo(target, network, crypto, both, committer)))
                .thenApply(newAccess -> true);
    }

    public CompletableFuture<Snapshot> copyTo(FileWrapper target,
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
                SymmetricKey newBaseR = SymmetricKey.random();
                SymmetricKey newBaseW = SymmetricKey.random();
                WritableAbsoluteCapability newCap = ((WritableAbsoluteCapability)target.getPointer().capability)
                        .withMapKey(newMapKey)
                        .withBaseKey(newBaseR)
                        .withBaseWriteKey(newBaseW);
                return getChildren(version, crypto.hasher, network).thenCompose(children ->
                        target.mkdir(getName(), Optional.of(newBaseR), Optional.of(newBaseW), Optional.of(newMapKey),
                                getFileProperties().isHidden, network, crypto, version, committer)
                                .thenCompose(versionWithDir ->
                                        network.getFile(versionWithDir, newCap, target.getChildsEntryWriter(), target.ownername)
                                                .thenCompose(subTargetOpt -> {
                                                    FileWrapper newTarget = subTargetOpt.get();
                                                    return Futures.reduceAll(children, versionWithDir,
                                                            (s, child) -> newTarget.getUpdated(s, network)
                                                                    .thenCompose(updated ->
                                                                            child.copyTo(updated, network, crypto, s, committer)),
                                                            (a, b) -> a.merge(b));
                                                })));
            } else {
                return version.withWriter(owner(), writer(), network).thenCompose(snapshot ->
                        getInputStream(snapshot.get(writer()).props, network, crypto, x -> {})
                                .thenCompose(stream -> target.uploadFileSection(snapshot, committer,
                                        getName(), stream, false, 0, getSize(),
                                        Optional.empty(), false, network, crypto, x -> {},
                                        crypto.random.randomBytes(32))));
            }
        });
    }

    @JsMethod
    public CompletableFuture<Boolean> hasChild(String fileName, Hasher hasher, NetworkAccess network) {
        if (!isLegalName(fileName)) {
            return Futures.errored(new IllegalArgumentException("Illegal file/directory name: " + fileName));
        }
        return this.hasChildWithName(version, fileName, hasher, network).thenApply(childExists -> childExists);
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
                                                                              Hasher hasher) {
        ensureUnmodified();
        WritableAbsoluteCapability cap = (WritableAbsoluteCapability)getPointer().capability;
        SymmetricLinkToSigner signerLink = SymmetricLinkToSigner.fromPair(cap.wBaseKey.get(), signer);
        CryptreeNode fileAccess = getPointer().fileAccess;

        RelativeCapability newParentLink = new RelativeCapability(Optional.of(parent.writer()),
                parent.getLocation().getMapKey(), parent.getParentKey(), Optional.empty());
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
                                        newRetrievedCapability, network, hasher))
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
                .thenCompose(version -> network.getMetadata(version.get(currentCap.writer).props, currentCap)
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
                                                streamSecret, currentCap.getMapKey(), hasher)
                                                .thenCompose(nextChunkMapKey ->
                                                        copyAllChunks(true, currentCap.withMapKey(nextChunkMapKey),
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
                .thenCompose(current -> network.getMetadata(current.get(currentCap.writer).props, currentCap)
                        .thenCompose(mOpt -> {
                            if (! mOpt.isPresent()) {
                                return CompletableFuture.completedFuture(current);
                            }
                            SigningPrivateKeyAndPublicHash ourSigner = mOpt.get()
                                    .getSigner(currentCap.rBaseKey, currentCap.wBaseKey.get(), Optional.of(signer));
                            return network.deleteChunk(current, committer, mOpt.get(), currentCap.owner,
                                    currentCap.getMapKey(), ourSigner, tid)
                                    .thenCompose(deletedVersion -> {
                                        CryptreeNode chunk = mOpt.get();
                                        Optional<byte[]> streamSecret = chunk.getProperties(chunk
                                                .getParentKey(currentCap.rBaseKey)).streamSecret;
                                        return chunk.getNextChunkLocation(currentCap.rBaseKey, streamSecret,
                                                currentCap.getMapKey(), hasher).thenCompose(nextChunkMapKey ->
                                                deleteAllChunks(currentCap.withMapKey(nextChunkMapKey), signer, tid, hasher,
                                                        network, deletedVersion, committer));
                                    })
                                    .thenCompose(updatedVersion -> {
                                        if (! mOpt.get().isDirectory())
                                            return CompletableFuture.completedFuture(updatedVersion);
                                        return mOpt.get().getDirectChildrenCapabilities(currentCap, updatedVersion, network).thenCompose(childCaps ->
                                                Futures.reduceAll(childCaps,
                                                        updatedVersion,
                                                        (v, cap) -> deleteAllChunks((WritableAbsoluteCapability) cap.cap, signer,
                                                                tid, hasher, network, v, committer),
                                                        (x, y) -> y));
                                    })
                                    .thenCompose(s -> removeSigningKey(currentCap.writer, signer, currentCap.owner, network, s, committer));
                        }));
    }

    /**
     * @param parent
     * @param userContext
     * @return updated parent
     */
    @JsMethod
    public CompletableFuture<FileWrapper> remove(FileWrapper parent, Path ourPath, UserContext userContext) {
        NetworkAccess network = userContext.network;
        Hasher hasher = userContext.crypto.hasher;
        ensureUnmodified();
        if (! pointer.capability.isWritable())
            return Futures.errored(new IllegalStateException("Cannot delete file without write access to it"));

        boolean writableParent = parent.isWritable();
        return (writableParent ? parent.removeChild(this, network, hasher) : CompletableFuture.completedFuture(parent))
                .thenCompose(updatedParent -> network.synchronizer.applyComplexUpdate(owner(), signingPair(),
                        (version, committer) -> IpfsTransaction.call(owner(),
                                tid -> FileWrapper.deleteAllChunks(
                                        isLink() ?
                                                (WritableAbsoluteCapability) getLinkPointer().capability :
                                                writableFilePointer(),
                                        writableParent ?
                                                parent.signingPair() :
                                                signingPair(), tid, hasher, network, version, committer), network.dhtClient))
                                .thenCompose(b -> userContext.sharedWithCache.clearSharedWith(ourPath))
                                .thenApply(b -> updatedParent)
                );
    }

    public static CompletableFuture<Snapshot> removeSigningKey(PublicKeyHash signerToRemove,
                                                               SigningPrivateKeyAndPublicHash parentSigner,
                                                               PublicKeyHash owner,
                                                               NetworkAccess network,
                                                               Snapshot current,
                                                               Committer committer) {
        PublicKeyHash parentWriter = parentSigner.publicKeyHash;
        if (parentWriter.equals(signerToRemove))
            return CompletableFuture.completedFuture(current);

        return current.withWriter(owner, parentWriter, network)
                .thenCompose(s -> s.get(parentSigner).props
                        .removeOwnedKey(owner, parentSigner, signerToRemove, network.dhtClient, network.hasher)
                        .thenCompose(removed -> IpfsTransaction.call(
                                owner,
                                tid -> committer.commit(owner, parentSigner, removed, s.get(parentSigner), tid),
                                network.dhtClient))
                        .thenApply(committed -> s.withVersion(parentWriter, committed.get(parentWriter))));
    }

    public CompletableFuture<? extends AsyncReader> getInputStream(NetworkAccess network,
                                                                   Crypto crypto,
                                                                   ProgressConsumer<Long> monitor) {
        return network.synchronizer.getValue(owner(), writer())
                .thenCompose(state -> getInputStream(state.get(writer()).props, network, crypto, getFileProperties().size, monitor));
    }

    public CompletableFuture<? extends AsyncReader> getInputStream(WriterData version,
                                                                   NetworkAccess network,
                                                                   Crypto crypto,
                                                                   ProgressConsumer<Long> monitor) {
        return getInputStream(version, network, crypto, getFileProperties().size, monitor);
    }

    @JsMethod
    public CompletableFuture<? extends AsyncReader> getBufferedInputStream(NetworkAccess network,
                                                                           Crypto crypto,
                                                                           int fileSizeHi,
                                                                           int fileSizeLow,
                                                                           int bufferChunks,
                                                                           ProgressConsumer<Long> monitor) {
        long fileSize = (fileSizeLow & 0xFFFFFFFFL) + ((fileSizeHi & 0xFFFFFFFFL) << 32);
        return getInputStream(network, crypto, fileSizeHi, fileSizeLow, monitor)
                .thenApply(r -> new BufferedAsyncReader(r, bufferChunks, fileSize));
    }

    @JsMethod
    public CompletableFuture<? extends AsyncReader> getInputStream(NetworkAccess network,
                                                                   Crypto crypto,
                                                                   int fileSizeHi,
                                                                   int fileSizeLow,
                                                                   ProgressConsumer<Long> monitor) {
        long fileSize = (fileSizeLow & 0xFFFFFFFFL) + ((fileSizeHi & 0xFFFFFFFFL) << 32);
        return network.synchronizer.getValue(owner(), writer())
                .thenCompose(state -> getInputStream(state.get(writer()).props, network, crypto, fileSize, monitor));
    }

    public CompletableFuture<? extends AsyncReader> getInputStream(NetworkAccess network,
                                                                   Crypto crypto,
                                                                   long fileSize,
                                                                   ProgressConsumer<Long> monitor) {
        return network.synchronizer.getValue(owner(), writer())
                .thenCompose(state -> getInputStream(state.get(writer()).props, network, crypto, fileSize, monitor));
    }

    public CompletableFuture<? extends AsyncReader> getInputStream(WriterData version,
                                                                   NetworkAccess network,
                                                                   Crypto crypto,
                                                                   long fileSize,
                                                                   ProgressConsumer<Long> monitor) {
        ensureUnmodified();
        if (pointer.fileAccess.isDirectory())
            throw new IllegalStateException("Cannot get input stream for a directory!");
        CryptreeNode fileAccess = pointer.fileAccess;
        return fileAccess.retriever(pointer.capability.rBaseKey, props.streamSecret, getLocation().getMapKey(), crypto.hasher)
                .thenCompose(retriever ->
                        retriever.getFile(version, network, crypto, pointer.capability, props.streamSecret,
                                fileSize, fileAccess.committedHash(), monitor));
    }

    private CompletableFuture<FileRetriever> getRetriever(Hasher hasher) {
        if (pointer.fileAccess.isDirectory())
            throw new IllegalStateException("Cannot get input stream for a directory!");
        return pointer.fileAccess.retriever(pointer.capability.rBaseKey, props.streamSecret, getLocation().getMapKey(), hasher);
    }

    @JsMethod
    public String getBase64Thumbnail() {
        Optional<byte[]> thumbnail = props.thumbnail;
        if (thumbnail.isPresent()) {
            String base64Data = Base64.getEncoder().encodeToString(thumbnail.get());
            return "data:image/png;base64," + base64Data;
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
        return getFileProperties().name;
    }

    public long getSize() {
        return getFileProperties().size;
    }

    public String toString() {
        return getFileProperties().name;
    }

    public static FileWrapper createRoot(TrieNode root) {
        return new FileWrapper(Optional.of(root), null, Optional.empty(), Optional.empty(), null, new Snapshot(new HashMap<>()));
    }

    public static Optional<byte[]> generateThumbnail(byte[] imageBlob) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(imageBlob));
            BufferedImage thumbnailImage = new BufferedImage(THUMBNAIL_SIZE, THUMBNAIL_SIZE, image.getType());
            Graphics2D g = thumbnailImage.createGraphics();
            g.setComposite(AlphaComposite.Src);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.drawImage(image, 0, 0, THUMBNAIL_SIZE, THUMBNAIL_SIZE, null);
            g.dispose();

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(thumbnailImage, "JPG", baos);
            baos.close();
            return Optional.of(baos.toByteArray());
        } catch (IOException ioe) {
            LOG.log(Level.WARNING, ioe.getMessage(), ioe);
        }
        return Optional.empty();
    }

    public static byte[] generateVideoThumbnail(byte[] videoBlob) {
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
        return new byte[0];
    }

    private CompletableFuture<Optional<byte[]>> generateThumbnail(NetworkAccess network, AsyncReader fileData, int fileSize, String filename, String mimeType) {
        CompletableFuture<Optional<byte[]>> fut = new CompletableFuture<>();
        if (fileSize > MimeTypes.HEADER_BYTES_TO_IDENTIFY_MIME_TYPE) {
            if (mimeType.startsWith("image")) {
                if (network.isJavascript()) {
                    thumbnail.generateThumbnail(fileData, fileSize, filename).thenAccept(base64Str -> {
                        byte[] bytesOfData = Base64.getDecoder().decode(base64Str);
                        if (bytesOfData.length == 0)
                            fut.complete(Optional.empty());
                        else
                            fut.complete(Optional.of(bytesOfData));
                    });
                } else {
                    byte[] bytes = new byte[fileSize];
                    fileData.readIntoArray(bytes, 0, fileSize).thenAccept(data -> {
                        fut.complete(generateThumbnail(bytes));
                    });
                }
            } else if (mimeType.startsWith("video")) {
                if (network.isJavascript()) {
                    thumbnail.generateVideoThumbnail(fileData, fileSize, filename, mimeType).thenAccept(base64Str -> {
                        if(base64Str == null) {
                            fut.complete(Optional.empty());
                        }
                        byte[] bytesOfData = Base64.getDecoder().decode(base64Str);
                        if (bytesOfData.length == 0)
                            fut.complete(Optional.empty());
                        else
                            fut.complete(Optional.of(bytesOfData));
                    });
                } else {
                    byte[] bytes = new byte[fileSize];
                    fileData.readIntoArray(bytes, 0, fileSize).thenAccept(data -> {
                        fut.complete(Optional.of(generateVideoThumbnail(bytes)));
                    });
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
                                            byte[] bytesOfData = Base64.getDecoder().decode(base64Str);
                                            fut.complete(Optional.of(bytesOfData));
                                        });
                            } else {
                                fut.complete(generateThumbnail(mp3CoverImage.imageData));
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
