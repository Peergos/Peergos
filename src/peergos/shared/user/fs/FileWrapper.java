package peergos.shared.user.fs;
import java.nio.file.*;
import java.util.logging.*;

import jsinterop.annotations.*;
import peergos.shared.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.crypto.random.*;
import peergos.shared.crypto.symmetric.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.storage.IpfsTransaction;
import peergos.shared.storage.TransactionId;
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
    private final Optional<SigningPrivateKeyAndPublicHash> entryWriter;
    private final FileProperties props;
    private final String ownername;
    private final Optional<TrieNode> globalRoot;
    private AtomicBoolean modified = new AtomicBoolean(); // This only used as a guard against concurrent modifications

    /**
     *
     * @param globalRoot This is only present if this is the global root
     * @param pointer
     * @param ownername
     */
    public FileWrapper(Optional<TrieNode> globalRoot,
                       RetrievedCapability pointer,
                       Optional<SigningPrivateKeyAndPublicHash> entryWriter,
                       String ownername) {
        this.globalRoot = globalRoot;
        this.pointer = pointer;
        this.ownername = ownername;
        this.entryWriter = entryWriter;
        if (pointer == null)
            props = new FileProperties("/", "", 0, LocalDateTime.MIN, false, Optional.empty());
        else {
            SymmetricKey parentKey = this.getParentKey();
            props = pointer.fileAccess.getProperties(parentKey);
        }
        if (isWritable() && !signingPair().publicKeyHash.equals(pointer.capability.writer))
            throw new IllegalStateException("Invalid FileWrapper! public writing keys don't match!");
    }

    public FileWrapper(RetrievedCapability pointer, Optional<SigningPrivateKeyAndPublicHash> entryWriter, String ownername) {
        this(Optional.empty(), pointer, entryWriter, ownername);
    }

    public FileWrapper withTrieNode(TrieNode trie) {
        return new FileWrapper(Optional.of(trie), pointer, entryWriter, ownername);
    }

    private FileWrapper withCryptreeNode(CryptreeNode access) {
        return new FileWrapper(globalRoot, new RetrievedCapability(getPointer().capability, access), entryWriter, ownername);
    }

    @JsMethod
    public boolean equals(Object other) {
        if (other == null)
            return false;
        if (!(other instanceof FileWrapper))
            return false;
        return pointer.equals(((FileWrapper) other).getPointer());
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

    public CompletableFuture<Optional<FileWrapper>> getDescendentByPath(String path, NetworkAccess network) {
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
        return getChildren(network).thenCompose(children -> {
            for (FileWrapper child : children)
                if (child.getFileProperties().name.equals(prefix)) {
                    return child.getDescendentByPath(suffix, network);
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

    public CompletableFuture<FileWrapper> updateChildLinks(
            Collection<Pair<RetrievedCapability, RetrievedCapability>> childCases,
            NetworkAccess network,
            SafeRandom random) {
        return ((DirAccess) pointer.fileAccess)
                .updateChildLinks((WritableAbsoluteCapability) pointer.capability, entryWriter,
                        childCases, network, random)
                .thenApply(committedCryptree ->
                        new FileWrapper(pointer.withCryptree(committedCryptree), entryWriter, ownername));
    }

    /**
     *
     * @param children
     * @param network
     * @param random
     * @return An updated version of this directory
     */
    public CompletableFuture<FileWrapper> addChildLinks(Collection<RetrievedCapability> children,
                                                        NetworkAccess network,
                                                        SafeRandom random) {
        return ((DirAccess) pointer.fileAccess)
                .addChildrenAndCommit(children.stream()
                                .map(p -> ((WritableAbsoluteCapability)pointer.capability).relativise(p.capability))
                                .collect(Collectors.toList()), (WritableAbsoluteCapability) pointer.capability, entryWriter,
                        network, random)
                .thenApply(committedCryptree ->
                        new FileWrapper(pointer.withCryptree(committedCryptree), entryWriter, ownername));
    }

    /**
     * Marks a file/directory and all its descendants as dirty. Directories are immediately cleaned,
     * but files have all their keys except the actual data key cleaned. That is cleaned lazily, the next time it is modified
     *
     * @param network
     * @param parent
     * @return The updated version of this file/directory
     * @throws IOException
     */
    public CompletableFuture<FileWrapper> rotateReadKeys(NetworkAccess network, SafeRandom random, FileWrapper parent) {
        return rotateReadKeys(true, network, random, parent, Optional.empty());
    }

    private CompletableFuture<FileWrapper> rotateReadKeys(boolean updateParent,
                                                          NetworkAccess network,
                                                          SafeRandom random,
                                                          FileWrapper parent,
                                                          Optional<SymmetricKey> newBaseKey) {
        if (!isWritable())
            throw new IllegalStateException("You cannot rotate read keys without write access!");
        WritableAbsoluteCapability cap = writableFilePointer();
        if (isDirectory()) {
            // create a new rBaseKey == subfoldersKey and make all descendants dirty
            SymmetricKey newSubfoldersKey = newBaseKey.orElseGet(SymmetricKey::random);
            WritableAbsoluteCapability ourNewPointer = cap.withBaseKey(newSubfoldersKey);
            SymmetricKey newParentKey = SymmetricKey.random();
            FileProperties props = getFileProperties();

            DirAccess existing = (DirAccess) pointer.fileAccess;

            Optional<byte[]> nextChunkMapKey = existing.getNextChunkLocation(cap.rBaseKey);
            Optional<WritableAbsoluteCapability> nextChunkCap = nextChunkMapKey.map(cap::withMapKey);
            Optional<WritableAbsoluteCapability> updatedNextChunkCap = nextChunkMapKey.map(ourNewPointer::withMapKey);

            // Create new DirAccess
            DirAccess newDirAccess = DirAccess.create(existing.committedHash(), newSubfoldersKey, null, Optional.empty(), props,
                    cap.relativise(parent.writableFilePointer()), newParentKey)
                    .withWriterLink(existing.getWriterLink())
                    .withNextBlob(updatedNextChunkCap.map(nextCap ->
                            EncryptedCapability.create(newSubfoldersKey, ourNewPointer.relativise(nextCap))));

            // re add children
            List<RelativeCapability> children = existing.getDirectChildren(pointer.capability.rBaseKey);
            return newDirAccess.addChildrenAndCommit(children, ourNewPointer, entryWriter, network, random)
                    .thenCompose(updatedDirAccess -> {
                        RetrievedCapability ourNewRetrievedPointer = new RetrievedCapability(ourNewPointer, updatedDirAccess);
                        FileWrapper theNewUs = new FileWrapper(ourNewRetrievedPointer, entryWriter, ownername);

                        // clean all subtree keys except file dataKeys (lazily re-key and re-encrypt them)
                        return getDirectChildren(network).thenCompose(childFiles -> {
                            List<CompletableFuture<Pair<RetrievedCapability, RetrievedCapability>>> cleanedChildren = childFiles.stream()
                                    .map(child -> child.rotateReadKeys(false, network, random, theNewUs,
                                            Optional.empty())
                                            .thenApply(updated -> new Pair<>(child.pointer, updated.pointer)))
                                    .collect(Collectors.toList());

                            return Futures.combineAll(cleanedChildren);
                        }).thenCompose(childrenCases -> theNewUs.updateChildLinks(childrenCases, network, random))
                                .thenCompose(finished ->
                                        // update pointer from parent to us
                                        (updateParent ? ((DirAccess) parent.pointer.fileAccess)
                                                .updateChildLink((WritableAbsoluteCapability) parent.pointer.capability,
                                                        parent.entryWriter, this.pointer,
                                                        ourNewRetrievedPointer, network, random) :
                                                CompletableFuture.completedFuture(null))
                                                .thenApply(x -> theNewUs));
                    }).thenCompose(updated -> {
                        if (! nextChunkMapKey.isPresent())
                            return CompletableFuture.completedFuture(updated);

                        return network.getMetadata(nextChunkCap.get().getLocation())
                                .thenCompose(mOpt -> {
                                    if (! mOpt.isPresent())
                                        return CompletableFuture.completedFuture(updated);
                                    return new FileWrapper(new RetrievedCapability(nextChunkCap.get(), mOpt.get()), entryWriter, ownername)
                                            .rotateReadKeys(false, network, random, parent, Optional.of(newSubfoldersKey))
                                            .thenApply(x -> updated);
                                });
                    }).thenApply(x -> {
                        setModified();
                        return x;
                    });
        } else {
            // create a new rBaseKey == parentKey
            SymmetricKey baseReadKey = newBaseKey.orElseGet(SymmetricKey::random);
            return ((FileAccess) pointer.fileAccess).rotateBaseReadKey(writableFilePointer(), entryWriter,
                    cap.relativise(parent.getMinimalReadPointer()), baseReadKey, network)
                    .thenCompose(updated -> {
                        Optional<byte[]> nextChunkMapKey = pointer.fileAccess.getNextChunkLocation(cap.rBaseKey);
                        if (! nextChunkMapKey.isPresent())
                            return CompletableFuture.completedFuture(updated);
                        WritableAbsoluteCapability nextChunkCap = cap.withMapKey(nextChunkMapKey.get());
                        return network.getMetadata(nextChunkCap.getLocation())
                                .thenCompose(mOpt -> {
                                    if (! mOpt.isPresent())
                                        return CompletableFuture.completedFuture(updated);
                                    return new FileWrapper(new RetrievedCapability(nextChunkCap, mOpt.get()), entryWriter, ownername)
                                            .rotateReadKeys(false, network, random, parent, Optional.of(baseReadKey))
                                            .thenApply(x -> updated);
                                });
                    }).thenCompose(newFileAccess -> {
                        RetrievedCapability newPointer = new RetrievedCapability(this.pointer.capability.withBaseKey(baseReadKey), newFileAccess);
                        // only update link from parent folder to file if we are the first chunk
                        return (updateParent ?
                                ((DirAccess) parent.pointer.fileAccess)
                                        .updateChildLink(parent.writableFilePointer(), parent.entryWriter, pointer,
                                                newPointer, network, random) :
                                CompletableFuture.completedFuture(null)
                        ).thenApply(x -> new FileWrapper(newPointer, entryWriter, ownername));
                    }).thenApply(x -> {
                        setModified();
                        return x;
                    });
        }
    }

    /**
     * Change all the symmetric writing keys for this file/dir and its subtree.
     * @param parent
     * @param network
     * @param random
     * @return The updated version of this file/directory and its parent
     */
    public CompletableFuture<Pair<FileWrapper, FileWrapper>> rotateWriteKeys(FileWrapper parent,
                                                                             NetworkAccess network,
                                                                             SafeRandom random) {
        return rotateWriteKeys(true, parent, Optional.empty(), network, random);
    }

    private CompletableFuture<Pair<FileWrapper, FileWrapper>> rotateWriteKeys(boolean updateParent,
                                                                             FileWrapper parent,
                                                                             Optional<SymmetricKey> suppliedBaseWriteKey,
                                                                             NetworkAccess network,
                                                                             SafeRandom random) {
        if (!isWritable())
            throw new IllegalStateException("You cannot rotate write keys without write access!");
        WritableAbsoluteCapability cap = writableFilePointer();
        SymmetricKey newBaseWriteKey = suppliedBaseWriteKey.orElseGet(SymmetricKey::random);
        WritableAbsoluteCapability ourNewPointer = cap.withBaseWriteKey(newBaseWriteKey);

        if (isDirectory()) {
            DirAccess existing = (DirAccess) pointer.fileAccess;
            Optional<SymmetricLinkToSigner> updatedWriter = existing.getWriterLink()
                    .map(toSigner -> SymmetricLinkToSigner.fromPair(newBaseWriteKey, toSigner.target(cap.wBaseKey.get())));
            DirAccess updatedDirAccess = existing.withWriterLink(updatedWriter)
                    .withoutChildren(cap.rBaseKey);

            Optional<byte[]> nextChunkMapKey = existing.getNextChunkLocation(cap.rBaseKey);
            Optional<WritableAbsoluteCapability> nextChunkCap = nextChunkMapKey.map(cap::withMapKey);

            RetrievedCapability ourNewRetrievedPointer = new RetrievedCapability(ourNewPointer, updatedDirAccess);
            FileWrapper theNewUs = new FileWrapper(ourNewRetrievedPointer, entryWriter, ownername);

            // clean all subtree write keys
            return getDirectChildren(network).thenCompose(childFiles -> {
                List<CompletableFuture<Pair<RetrievedCapability, RetrievedCapability>>> cleanedChildren = childFiles.stream()
                        .map(child -> child.rotateWriteKeys(false, theNewUs, Optional.empty(), network, random)
                                .thenApply(updated -> new Pair<>(child.pointer, updated.left.pointer)))
                        .collect(Collectors.toList());

                return Futures.combineAll(cleanedChildren);
            }).thenCompose(childrenCases -> theNewUs.addChildLinks(childrenCases.stream()
                    .map(p -> p.left)
                    .collect(Collectors.toList()), network, random)
            ).thenCompose(updatedDir ->
                    // update pointer from parent to us
                    (updateParent ? ((DirAccess) parent.pointer.fileAccess)
                            .updateChildLink((WritableAbsoluteCapability) parent.pointer.capability,
                                    parent.entryWriter, this.pointer,
                                    updatedDir.pointer, network, random)
                            .thenApply(parentDa -> new FileWrapper(parent.pointer.withCryptree(parentDa),
                                    parent.entryWriter, parent.ownername)):
                            CompletableFuture.completedFuture(parent))
                            .thenApply(newParent -> new Pair<>(updatedDir, newParent))
            ).thenCompose(updatedPair -> {
                if (! nextChunkMapKey.isPresent())
                    return CompletableFuture.completedFuture(updatedPair);

                return network.getMetadata(nextChunkCap.get().getLocation())
                        .thenCompose(mOpt -> {
                            if (! mOpt.isPresent())
                                return CompletableFuture.completedFuture(updatedPair);
                            return new FileWrapper(new RetrievedCapability(nextChunkCap.get(), mOpt.get()), Optional.of(signingPair()), ownername)
                                    .rotateWriteKeys(false, parent, Optional.of(newBaseWriteKey), network, random)
                                    .thenApply(x -> updatedPair);
                        });
            }).thenApply(x -> {
                setModified();
                return x;
            });
        } else {
            FileAccess existing = (FileAccess) pointer.fileAccess;
            // Only need to do the first chunk, because only those can have writer links
            return existing.rotateBaseWriteKey(cap, entryWriter, newBaseWriteKey, network)
                    .thenApply(updatedFa -> new Pair<>(
                            new FileWrapper(new RetrievedCapability(ourNewPointer, updatedFa), entryWriter, ownername), parent));
        }
    }

    public CompletableFuture<Boolean> hasChildWithName(String name, NetworkAccess network) {
        ensureUnmodified();
        return getChildren(network)
                .thenApply(children -> children.stream().anyMatch(c -> c.props.name.equals(name)));
    }

    /**
     *
     * @param child
     * @param network
     * @return Updated version of this directory without the child
     */
    public CompletableFuture<FileWrapper> removeChild(FileWrapper child, NetworkAccess network) {
        setModified();
        return ((DirAccess) pointer.fileAccess)
                .removeChildren(Arrays.asList(child.getPointer()), writableFilePointer(), entryWriter, network)
                .thenApply(updated -> new FileWrapper(globalRoot,
                        new RetrievedCapability(getPointer().capability, updated), entryWriter, ownername));
    }

    public CompletableFuture<FileWrapper> addLinkTo(FileWrapper file, NetworkAccess network, SafeRandom random) {
        ensureUnmodified();
        CompletableFuture<FileWrapper> error = new CompletableFuture<>();
        if (!this.isDirectory() || !this.isWritable()) {
            error.completeExceptionally(new IllegalArgumentException("Can only add link to a writable directory!"));
            return error;
        }
        String name = file.getFileProperties().name;
        return hasChildWithName(name, network).thenCompose(hasChild -> {
            if (hasChild) {
                error.completeExceptionally(new IllegalStateException("Child already exists with name: " + name));
                return error;
            }
            DirAccess toUpdate = (DirAccess) pointer.fileAccess;
            return toUpdate.addChildAndCommit(writableFilePointer().relativise(file.writableFilePointer()),
                    writableFilePointer(), entryWriter, network, random)
                    .thenApply(dirAccess -> new FileWrapper(this.pointer.withCryptree(dirAccess), entryWriter, ownername));
        });
    }

    @JsMethod
    public String toLink() {
        return pointer.capability.toLink();
    }

    @JsMethod
    public boolean isWritable() {
        return pointer != null && pointer.capability instanceof WritableAbsoluteCapability;
    }

    @JsMethod
    public boolean isReadable() {
        try {
            pointer.fileAccess.getMetaKey(pointer.capability.rBaseKey);
            return false;
        } catch (Exception e) {}
        return true;
    }

    public SymmetricKey getKey() {
        return pointer.capability.rBaseKey;
    }

    public Location getLocation() {
        return new Location(pointer.capability.owner, pointer.capability.writer, pointer.capability.getMapKey());
    }

    public Location getNextChunkLocation() {
        return new Location(pointer.capability.owner, pointer.capability.writer,
                pointer.fileAccess.getNextChunkLocation(pointer.capability.rBaseKey).get());
    }

    public Set<Location> getChildrenLocations() {
        ensureUnmodified();
        if (!this.isDirectory())
            return Collections.emptySet();
        return ((DirAccess) pointer.fileAccess).getChildrenLocations(pointer.capability);
    }

    public CompletableFuture<Optional<FileWrapper>> retrieveParent(NetworkAccess network) {
        ensureUnmodified();
        if (pointer == null)
            return CompletableFuture.completedFuture(Optional.empty());
        SymmetricKey parentKey = getParentKey();
        CompletableFuture<RetrievedCapability> parent = pointer.fileAccess.getParent(pointer.capability.owner, pointer.capability.writer, parentKey, network);
        return parent.thenApply(parentRFP -> {
            if (parentRFP == null)
                return Optional.empty();
            return Optional.of(new FileWrapper(parentRFP, Optional.empty(), ownername));
        });
    }

    public SymmetricKey getParentKey() {
        ensureUnmodified();
        SymmetricKey parentKey = pointer.capability.rBaseKey;
        if (this.isDirectory())
            try {
                parentKey = pointer.fileAccess.getParentKey(parentKey);
            } catch (Exception e) {
                // if we don't have read access to this folder, then we must just have the parent key already
            }
        return parentKey;
    }

    @JsMethod
    public CompletableFuture<Set<FileWrapper>> getChildren(NetworkAccess network) {
        ensureUnmodified();
        if (globalRoot.isPresent())
            return globalRoot.get().getChildren("/", network);
        if (isReadable()) {
            Optional<SigningPrivateKeyAndPublicHash> childsEntryWriter = pointer.capability.wBaseKey
                    .map(wBase -> pointer.fileAccess.getSigner(wBase, entryWriter));
            return retrieveChildren(network).thenApply(childrenRFPs -> {
                Set<FileWrapper> newChildren = childrenRFPs.stream()
                        .map(x -> new FileWrapper(x, childsEntryWriter, ownername))
                        .collect(Collectors.toSet());
                return newChildren.stream().collect(Collectors.toSet());
            });
        }
        throw new IllegalStateException("Unreadable FileWrapper!");
    }

    public CompletableFuture<Set<FileWrapper>> getDirectChildren(NetworkAccess network) {
        ensureUnmodified();
        if (globalRoot.isPresent())
            return globalRoot.get().getChildren("/", network);
        if (isReadable()) {
            Optional<SigningPrivateKeyAndPublicHash> childsEntryWriter = pointer.capability.wBaseKey
                    .map(wBase -> pointer.fileAccess.getSigner(wBase, entryWriter));
            return retrieveDirectChildren(network).thenApply(childrenRFPs -> {
                Set<FileWrapper> newChildren = childrenRFPs.stream()
                        .map(x -> new FileWrapper(x, childsEntryWriter, ownername))
                        .collect(Collectors.toSet());
                return newChildren.stream().collect(Collectors.toSet());
            });
        }
        throw new IllegalStateException("Unreadable FileWrapper!");
    }

    public CompletableFuture<Optional<FileWrapper>> getChild(String name, NetworkAccess network) {
        return getChildren(network)
                .thenApply(children -> children.stream().filter(f -> f.getName().equals(name)).findAny());
    }

    private CompletableFuture<Set<RetrievedCapability>> retrieveChildren(NetworkAccess network) {
        CryptreeNode fileAccess = pointer.fileAccess;

        if (isReadable())
            return ((DirAccess) fileAccess).getChildren(network, pointer.capability);
        throw new IllegalStateException("No credentials to retrieve children!");
    }

    private CompletableFuture<Set<RetrievedCapability>> retrieveDirectChildren(NetworkAccess network) {
        CryptreeNode fileAccess = pointer.fileAccess;

        if (isReadable())
            return ((DirAccess) fileAccess).getDirectChildren(network, pointer.capability);
        throw new IllegalStateException("No credentials to retrieve children!");
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

    public boolean isDirty() {
        ensureUnmodified();
        return pointer.fileAccess.isDirty(pointer.capability.rBaseKey);
    }

    /**
     *
     * @param network
     * @param random
     * @param parent
     * @param fragmenter
     * @return updated parent dir
     */
    public CompletableFuture<FileWrapper> clean(NetworkAccess network,
                                                SafeRandom random,
                                                FileWrapper parent,
                                                Fragmenter fragmenter) {
        if (!isDirty())
            return CompletableFuture.completedFuture(this);
        if (isDirectory()) {
            throw new IllegalStateException("Directories are never dirty (they are cleaned immediately)!");
        } else {
            return ((FileAccess)pointer.fileAccess).cleanAndCommit(writableFilePointer(), signingPair(),
                    parent.getLocation(), parent.getParentKey(), network, random, fragmenter)
                    .thenApply(res -> {
                        setModified();
                        return parent;
                    });
        }
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
                                                       NetworkAccess network,
                                                       SafeRandom random,
                                                       ProgressConsumer<Long> monitor,
                                                       Fragmenter fragmenter,
                                                       TransactionService transactions) {
        long fileSize = lengthLow + ((lengthHi & 0xFFFFFFFFL) << 32);
        return getPath(network).thenCompose(path ->
                Transaction.buildFileUploadTransaction(Paths.get(path).resolve(filename).toString(), fileSize, fileData, signingPair(),
                        generateChildLocationsFromSize(fileSize, random)))
                .thenCompose(txn -> transactions.open(txn)
                        .thenCompose(x -> fileData.reset())
                        .thenCompose(reset -> uploadFileSection(filename, reset, false, 0, fileSize,
                                Optional.empty(), overwriteExisting, network, random, monitor, fragmenter, txn.getLocations()))
                        .thenCompose(res -> transactions.close(txn).thenApply(x -> res)));
    }

    public CompletableFuture<FileWrapper> uploadOrOverwriteFile(String filename,
                                                                AsyncReader fileData,
                                                                long length,
                                                                NetworkAccess network,
                                                                SafeRandom random,
                                                                ProgressConsumer<Long> monitor,
                                                                Fragmenter fragmenter,
                                                                List<Location> locations) {
        return uploadFileSection(filename, fileData, false, 0, length, Optional.empty(),
                true, network, random, monitor, fragmenter, locations);
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
     * @param random
     * @param monitor A way to report back progress in number of bytes of file written
     * @param fragmenter
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
                                                            SafeRandom random,
                                                            ProgressConsumer<Long> monitor,
                                                            Fragmenter fragmenter,
                                                            List<Location> locations) {
        if (!isLegalName(filename)) {
            CompletableFuture<FileWrapper> res = new CompletableFuture<>();
            res.completeExceptionally(new IllegalStateException("Illegal filename: " + filename));
            return res;
        }
        if (! isDirectory()) {
            CompletableFuture<FileWrapper> res = new CompletableFuture<>();
            res.completeExceptionally(new IllegalStateException("Cannot upload a sub file to a file!"));
            return res;
        }
        return getDescendentByPath(filename, network).thenCompose(childOpt -> {
            if (childOpt.isPresent()) {
                if (! overwriteExisting)
                    throw new IllegalStateException("File already exists with name " + filename);
                return updateExistingChild(childOpt.get(), fileData, startIndex, endIndex, network, random, monitor, fragmenter);
            }
            if (startIndex > 0) {
                // TODO if startIndex > 0 prepend with a zero section
                throw new IllegalStateException("Unimplemented!");
            }
            SymmetricKey fileWriteKey = SymmetricKey.random();
            SymmetricKey fileKey = baseKey.orElseGet(SymmetricKey::random);
            SymmetricKey rootRKey = pointer.capability.rBaseKey;
            DirAccess dirAccess = (DirAccess) pointer.fileAccess;
            SymmetricKey dirParentKey = dirAccess.getParentKey(rootRKey);
            Location parentLocation = getLocation();
            int thumbnailSrcImageSize = startIndex == 0 && endIndex < Integer.MAX_VALUE ? (int) endIndex : 0;
            return generateThumbnail(network, fileData, thumbnailSrcImageSize, filename)
                    .thenCompose(thumbData -> fileData.reset()
                            .thenCompose(forMime -> calculateMimeType(forMime, endIndex)
                                    .thenCompose(mimeType -> fileData.reset().thenCompose(resetReader -> {
                                        FileProperties fileProps = new FileProperties(filename, mimeType, endIndex,
                                                LocalDateTime.now(), isHidden, Optional.of(thumbData));
                                        FileUploader chunks = new FileUploader(filename, mimeType, resetReader,
                                                startIndex, endIndex, fileKey, parentLocation, dirParentKey, monitor, fileProps,
                                                fragmenter, locations);
                                        SigningPrivateKeyAndPublicHash signer = signingPair();
                                        return chunks.upload(network, parentLocation.owner, signer)
                                                .thenCompose(fileLocation -> {
                                                    WritableAbsoluteCapability fileWriteCap =
                                                            new WritableAbsoluteCapability(owner(), signer.publicKeyHash,
                                                                    locations.get(0).getMapKey(), fileKey, fileWriteKey);
                                                    return addChildPointer(filename, fileWriteCap, network, random, 2);
                                                });
                                    }))
                            )
                    );
        });
    }

    private CompletableFuture<FileWrapper> addChildPointer(String filename,
                                                           WritableAbsoluteCapability childPointer,
                                                           NetworkAccess network,
                                                           SafeRandom random,
                                                           int retries) {
        CompletableFuture<FileWrapper> result = new CompletableFuture<>();
        ((DirAccess) pointer.fileAccess).addChildAndCommit(writableFilePointer().relativise(childPointer),
                writableFilePointer(), entryWriter, network, random)
                .thenAccept(uploadResult -> {
                    setModified();
                    result.complete(this.withCryptreeNode(uploadResult));
                }).exceptionally(e -> {

            if (e instanceof MutableTree.CasException || e.getCause() instanceof MutableTree.CasException) {
                // reload directory and try again
                network.getMetadata(getLocation()).thenCompose(opt -> {
                    DirAccess updatedUs = (DirAccess) opt.get();
                    // Check another file of same name hasn't been added in the concurrent change

                    RetrievedCapability updatedPointer = new RetrievedCapability(pointer.capability, updatedUs);
                    FileWrapper us = new FileWrapper(globalRoot, updatedPointer, entryWriter, ownername);
                    return us.getChildren(network).thenCompose(children -> {
                        Set<String> childNames = children.stream()
                                .map(f -> f.getName())
                                .collect(Collectors.toSet());
                        if (! childNames.contains(filename)) {
                            return us.addChildPointer(filename, childPointer, network, random, retries)
                                    .thenAccept(res -> {
                                        result.complete(res);
                                    });
                        }
                        throw new IllegalStateException("File upload aborted: file already exists!");
                    });
                }).exceptionally(ex -> {
                    if ((e instanceof MutableTree.CasException ||
                            e.getCause() instanceof MutableTree.CasException) && retries > 0)
                        addChildPointer(filename, childPointer, network, random, retries - 1)
                                .thenApply(f -> result.complete(f))
                                .exceptionally(e2 -> {
                                    result.completeExceptionally(e2);
                                    return null;
                                });
                    else
                        result.completeExceptionally(e);
                    return null;
                });
            } else
                result.completeExceptionally(e);
            return null;
        });
        return result;
    }

    public CompletableFuture<FileWrapper> updateExistingChild(String existingChildName, AsyncReader fileData,
                                                               long inputStartIndex, long endIndex,
                                                               NetworkAccess network, SafeRandom random,
                                                               ProgressConsumer<Long> monitor, Fragmenter fragmenter) {
        return getDescendentByPath(existingChildName, network)
                .thenCompose(childOpt -> updateExistingChild(childOpt.get(), fileData, inputStartIndex, endIndex,
                        network, random, monitor, fragmenter));
    }

    private CompletableFuture<FileWrapper> updateExistingChild(FileWrapper existingChild, AsyncReader fileData,
                                                               long inputStartIndex, long endIndex,
                                                               NetworkAccess network, SafeRandom random,
                                                               ProgressConsumer<Long> monitor, Fragmenter fragmenter) {

        String filename = existingChild.getFileProperties().name;
        LOG.info("Overwriting section [" + Long.toHexString(inputStartIndex) + ", " + Long.toHexString(endIndex) + "] of child with name: " + filename);

        Supplier<Location> locationSupplier = () -> new Location(getLocation().owner, getLocation().writer, random.randomBytes(32));

        return (existingChild.isDirty() ?
                existingChild.clean(network, random, this, fragmenter)
                        .thenCompose(us -> us.getChild(filename, network)
                                .thenApply(cleanedChild -> new Pair<>(us, cleanedChild.get()))) :
                CompletableFuture.completedFuture(new Pair<>(this, existingChild))
        ).thenCompose(updatedPair -> {
            FileWrapper us = updatedPair.left;
            FileWrapper child = updatedPair.right;
            FileProperties childProps = child.getFileProperties();
            final AtomicLong filesSize = new AtomicLong(childProps.size);
            FileRetriever retriever = child.getRetriever();
            SymmetricKey baseKey = child.pointer.capability.rBaseKey;
            FileAccess fileAccess = (FileAccess) child.pointer.fileAccess;
            SymmetricKey dataKey = fileAccess.getDataKey(baseKey);

            List<Long> startIndexes = new ArrayList<>();

            for (long startIndex = inputStartIndex; startIndex < endIndex; startIndex = startIndex + Chunk.MAX_SIZE - (startIndex % Chunk.MAX_SIZE))
                startIndexes.add(startIndex);


            boolean identity = true;

            BiFunction<Boolean, Long, CompletableFuture<Boolean>> composer = (id, startIndex) -> {
                return retriever.getChunkInputStream(network, random, dataKey, startIndex, filesSize.get(),
                        child.getLocation(), child.pointer.fileAccess.committedHash(), monitor)
                        .thenCompose(currentLocation -> {
                                    CompletableFuture<Optional<Location>> locationAt = retriever
                                            .getLocationAt(child.getLocation(), startIndex + Chunk.MAX_SIZE, dataKey, network)
                                            .thenApply(x -> x.map(m -> getLocation().withMapKey(m)));
                                    return locationAt.thenCompose(location ->
                                            CompletableFuture.completedFuture(new Pair<>(currentLocation, location)));
                                }
                        ).thenCompose(pair -> {

                            if (!pair.left.isPresent()) {
                                CompletableFuture<Boolean> result = new CompletableFuture<>();
                                result.completeExceptionally(new IllegalStateException("Current chunk not present"));
                                return result;
                            }

                            LocatedChunk currentOriginal = pair.left.get();
                            Optional<Location> nextChunkLocationOpt = pair.right;
                            Location nextChunkLocation = nextChunkLocationOpt.orElseGet(locationSupplier);
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
                                    ((FileAccess) child.pointer.fileAccess).writerLink :
                                    Optional.empty();

                            return fileData.readIntoArray(raw, internalStart, internalEnd - internalStart).thenCompose(read -> {

                                Chunk updated = new Chunk(raw, dataKey, currentOriginal.location.getMapKey(), dataKey.createNonce());
                                LocatedChunk located = new LocatedChunk(currentOriginal.location, currentOriginal.existingHash, updated);
                                long currentSize = filesSize.get();
                                FileProperties newProps = new FileProperties(childProps.name, childProps.mimeType,
                                        endIndex > currentSize ? endIndex : currentSize,
                                        LocalDateTime.now(), childProps.isHidden, childProps.thumbnail);

                                CompletableFuture<Multihash> chunkUploaded = FileUploader.uploadChunk(child.signingPair(),
                                        newProps, getLocation(), us.getParentKey(), baseKey, located,
                                        fragmenter,
                                        nextChunkLocation, writerLink, network, monitor);

                                return chunkUploaded.thenCompose(isUploaded -> {
                                    //update indices to be relative to next chunk
                                    long updatedLength = startIndex + internalEnd - internalStart;
                                    if (updatedLength > filesSize.get()) {
                                        filesSize.set(updatedLength);

                                        if (updatedLength > Chunk.MAX_SIZE) {
                                            // update file size in FileProperties of first chunk
                                            return getChildren(network).thenCompose(children -> {
                                                Optional<FileWrapper> updatedChild = children.stream()
                                                        .filter(f -> f.getFileProperties().name.equals(filename))
                                                        .findAny();
                                                return updatedChild.get().setProperties(child.getFileProperties().withSize(endIndex), network, this);
                                            });
                                        }
                                    }
                                    return CompletableFuture.completedFuture(true);
                                });
                            });
                        });
            };

            BiFunction<Boolean, Boolean, Boolean> combiner = (left, right) -> left && right;
            return Futures.reduceAll(startIndexes, identity, composer, combiner)
                    .thenApply(b -> us);
        });
    }

    static boolean isLegalName(String name) {
        return !name.contains("/");
    }

    @JsMethod
    public CompletableFuture<RelativeCapability> mkdir(String newFolderName, NetworkAccess network, boolean isSystemFolder,
                                                       SafeRandom random) {
        return mkdir(newFolderName, network, null, isSystemFolder, random);
    }

    public CompletableFuture<RelativeCapability> mkdir(String newFolderName,
                                                       NetworkAccess network,
                                                       SymmetricKey requestedBaseSymmetricKey,
                                                       boolean isSystemFolder,
                                                       SafeRandom random) {

        CompletableFuture<RelativeCapability> result = new CompletableFuture<>();
        if (!this.isDirectory()) {
            result.completeExceptionally(new IllegalStateException("Cannot mkdir in a file!"));
            return result;
        }
        if (!isLegalName(newFolderName)) {
            result.completeExceptionally(new IllegalStateException("Illegal directory name: " + newFolderName));
            return result;
        }
        return hasChildWithName(newFolderName, network).thenCompose(hasChild -> {
            if (hasChild) {
                result.completeExceptionally(new IllegalStateException("Child already exists with name: " + newFolderName));
                return result;
            }
            DirAccess dirAccess = (DirAccess) pointer.fileAccess;
            return dirAccess.mkdir(newFolderName, network, writableFilePointer(), entryWriter,
                    requestedBaseSymmetricKey, isSystemFolder, random).thenApply(x -> {
                setModified();
                return x;
            });
        });
    }

    @JsMethod
    public CompletableFuture<FileWrapper> rename(String newFilename, NetworkAccess network, FileWrapper parent) {
        return rename(newFilename, network, parent, false);
    }

    /**
     * @param newFilename
     * @param network
     * @param parent
     * @param overwrite
     * @return the updated parent
     */
    public CompletableFuture<FileWrapper> rename(String newFilename, NetworkAccess network,
                                                 FileWrapper parent, boolean overwrite) {
        setModified();
        if (! isLegalName(newFilename))
            return CompletableFuture.completedFuture(parent);
        CompletableFuture<Optional<FileWrapper>> childExists = parent == null ?
                CompletableFuture.completedFuture(Optional.empty()) :
                parent.getDescendentByPath(newFilename, network);
        return childExists
                .thenCompose(existing -> {
                    if (existing.isPresent() && !overwrite)
                        throw new IllegalStateException("Cannot rename, child already exists with name: " + newFilename);

                    return ((overwrite && existing.isPresent()) ?
                            existing.get().remove(parent, network) :
                            CompletableFuture.completedFuture(parent)
                    ).thenCompose(res -> {

                        //get current props
                        AbsoluteCapability relativeCapability = pointer.capability;
                        SymmetricKey baseKey = relativeCapability.rBaseKey;
                        CryptreeNode fileAccess = pointer.fileAccess;

                        SymmetricKey key = this.isDirectory() ? fileAccess.getParentKey(baseKey) : baseKey;
                        FileProperties currentProps = fileAccess.getProperties(key);

                        FileProperties newProps = new FileProperties(newFilename, currentProps.mimeType, currentProps.size,
                                currentProps.modified, currentProps.isHidden, currentProps.thumbnail);

                        return fileAccess.updateProperties(writableFilePointer(), entryWriter, newProps, network)
                                .thenApply(fa -> res);
                    });
                });
    }

    public CompletableFuture<Boolean> setProperties(FileProperties updatedProperties, NetworkAccess network, FileWrapper parent) {
        setModified();
        String newName = updatedProperties.name;
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        if (!isLegalName(newName)) {
            result.completeExceptionally(new IllegalArgumentException("Illegal file name: " + newName));
            return result;
        }
        return (parent == null ?
                CompletableFuture.completedFuture(false) :
                parent.hasChildWithName(newName, network))
                .thenCompose(hasChild -> {
                    if (hasChild && parent != null && !parent.getChildrenLocations().stream()
                            .map(l -> new ByteArrayWrapper(l.getMapKey()))
                            .collect(Collectors.toSet())
                            .contains(new ByteArrayWrapper(pointer.capability.getMapKey()))) {
                        result.completeExceptionally(new IllegalStateException("Cannot rename to same name as an existing file"));
                        return result;
                    }
                    CryptreeNode fileAccess = pointer.fileAccess;

                    return fileAccess.updateProperties(writableFilePointer(), entryWriter, updatedProperties, network)
                            .thenApply(fa -> true);
                });
    }

    /**
     *
     * @return A capability based on the parent key
     */
    public AbsoluteCapability getMinimalReadPointer() {
        if (isDirectory()) {
            return pointer.capability.withBaseKey(getParentKey());
        }
        return pointer.capability;
    }

    public WritableAbsoluteCapability writableFilePointer() {
        if (! isWritable())
            throw new IllegalStateException("File is not writable!");
        return (WritableAbsoluteCapability) pointer.capability;
    }

    public SigningPrivateKeyAndPublicHash signingPair() {
        if (! isWritable())
            throw new IllegalStateException("File is not writable!");
        return pointer.fileAccess.getSigner(pointer.capability.wBaseKey.get(), entryWriter);
    }

    @JsMethod
    public CompletableFuture<FileWrapper> copyTo(FileWrapper target,
                                                 NetworkAccess network,
                                                 SafeRandom random,
                                                 Fragmenter fragmenter) {
        ensureUnmodified();
        CompletableFuture<FileWrapper> result = new CompletableFuture<>();
        if (! target.isDirectory()) {
            result.completeExceptionally(new IllegalStateException("CopyTo target " + target + " must be a directory"));
            return result;
        }
        return target.hasChildWithName(getFileProperties().name, network).thenCompose(childExists -> {
            if (childExists) {
                result.completeExceptionally(new IllegalStateException("CopyTo target " + target + " already has child with name " + getFileProperties().name));
                return result;
            }
            boolean sameWriter = getLocation().writer.equals(target.getLocation().writer);
            //make new FileWrapper pointing to the same file if we are the same writer, but with a different location
            if (sameWriter) {
                byte[] newMapKey = random.randomBytes(32);
                SymmetricKey newBaseKey = SymmetricKey.random();
                SymmetricKey newWriterBaseKey = SymmetricKey.random();
                WritableAbsoluteCapability newRFP = new WritableAbsoluteCapability(target.owner(), target.writer(), newMapKey, newBaseKey,
                        newWriterBaseKey);
                SymmetricKey newParentParentKey = target.getParentKey();

                return pointer.fileAccess.copyTo(pointer.capability, newBaseKey, target.writableFilePointer(), target.entryWriter, newParentParentKey,
                        newMapKey, network, random)
                        .thenCompose(newAccess -> {
                            // upload new metadatablob
                            RetrievedCapability newRetrievedCapability = new RetrievedCapability(newRFP, newAccess);
                            FileWrapper newFileWrapper = new FileWrapper(newRetrievedCapability, target.entryWriter, target.getOwnerName());
                            return target.addLinkTo(newFileWrapper, network, random);
                        });
            } else {
                return getInputStream(network, random, x -> {})
                        .thenCompose(stream -> target.uploadFileSection(getName(), stream, false, 0, getSize(),
                                Optional.empty(), false, network, random, x -> {}, fragmenter, target.generateChildLocations(props.getNumberOfChunks(), random))
                        .thenApply(b -> target));
            }
        });
    }

    /**
     * Move this file/dir and subtree to a new signing key pair.
     * @param signer
     * @param parent
     * @param network
     * @param random
     * @return The updated version of this file/dir and its parent
     */
    public CompletableFuture<Pair<FileWrapper, FileWrapper>> changeSigningKey(SigningPrivateKeyAndPublicHash signer,
                                                                              FileWrapper parent,
                                                                              NetworkAccess network,
                                                                              SafeRandom random) {
        ensureUnmodified();
        WritableAbsoluteCapability cap = (WritableAbsoluteCapability)getPointer().capability;
        SymmetricLinkToSigner signerLink = SymmetricLinkToSigner.fromPair(cap.wBaseKey.get(), signer);
        CryptreeNode fileAccess = getPointer().fileAccess;
        EncryptedCapability newParentLink = EncryptedCapability.create(fileAccess.getParentKey(cap.rBaseKey), parent.getParentKey(),
                Optional.of(parent.writer()), parent.getLocation().getMapKey());

        CryptreeNode newFileAccess = fileAccess.withWriterLink(signerLink).withParentLink(newParentLink);
        RetrievedCapability newRetrievedCapability = new RetrievedCapability(cap.withSigner(signer.publicKeyHash), newFileAccess);

        return IpfsTransaction.call(owner(),
                tid -> network.uploadChunk(newFileAccess, owner(), getPointer().capability.getMapKey(), signer, tid)
                        .thenCompose(ourNewHash -> copyAllChunks(false, cap, signer, tid, network)
                        .thenCompose(y -> ((DirAccess)parent.getPointer().fileAccess).updateChildLink(parent.writableFilePointer(),
                                parent.entryWriter,
                                getPointer(),
                                newRetrievedCapability, network, random))
                        .thenCompose(updatedParentDA -> deleteAllChunks(cap, signingPair(), tid, network)
                                .thenApply(x -> new FileWrapper(parent.pointer
                                        .withCryptree(updatedParentDA), parent.entryWriter, parent.ownername)))
                        .thenApply(updatedParent -> new Pair<>(new FileWrapper(newRetrievedCapability.withHash(ourNewHash),
                                Optional.of(signer), ownername), updatedParent))),
                network.dhtClient);
    }

    /** This copies all the cryptree nodes from one signing key to another for a file or subtree
     *
     * @param includeFirst
     * @param currentCap
     * @param targetSigner
     * @param tid
     * @param network
     * @return
     */
    private static CompletableFuture<Boolean> copyAllChunks(boolean includeFirst,
                                                            AbsoluteCapability currentCap,
                                                            SigningPrivateKeyAndPublicHash targetSigner,
                                                            TransactionId tid,
                                                            NetworkAccess network) {

        return network.getMetadata(currentCap.getLocation())
                .thenCompose(mOpt -> {
                    if (! mOpt.isPresent()) {
                        return CompletableFuture.completedFuture(true);
                    }
                    return (includeFirst ?
                            network.addPreexistingChunk(mOpt.get(), currentCap.owner, currentCap.getMapKey(), targetSigner, tid) :
                            CompletableFuture.completedFuture(true))
                            .thenCompose(b -> {
                                CryptreeNode chunk = mOpt.get();
                                Optional<byte[]> nextChunkMapKey = chunk.getNextChunkLocation(currentCap.rBaseKey);
                                if (! nextChunkMapKey.isPresent())
                                    return CompletableFuture.completedFuture(true);
                                return copyAllChunks(true, currentCap.withMapKey(nextChunkMapKey.get()), targetSigner, tid, network);
                            })
                            .thenCompose(b -> {
                                if (! (mOpt.get() instanceof DirAccess))
                                    return CompletableFuture.completedFuture(true);
                                Set<AbsoluteCapability> childCaps = ((DirAccess) mOpt.get()).getChildrenCapabilities(currentCap);
                                return Futures.reduceAll(childCaps,
                                        true,
                                        (x, cap) -> copyAllChunks(true, cap, targetSigner, tid, network),
                                        (x, y) -> x && y);
                            });
                });
    }

    public static CompletableFuture<Boolean> deleteAllChunks(WritableAbsoluteCapability currentCap,
                                                             SigningPrivateKeyAndPublicHash signer,
                                                             TransactionId tid,
                                                             NetworkAccess network) {
        return network.getMetadata(currentCap.getLocation())
                .thenCompose(mOpt -> {
                    if (! mOpt.isPresent()) {
                        return CompletableFuture.completedFuture(true);
                    }
                    SigningPrivateKeyAndPublicHash ourSigner = mOpt.get().getSigner(currentCap.wBaseKey.get(), Optional.of(signer));
                    return network.deleteChunk(mOpt.get(), currentCap.owner, currentCap.getMapKey(), ourSigner, tid)
                            .thenCompose(b -> {
                                CryptreeNode chunk = mOpt.get();
                                Optional<byte[]> nextChunkMapKey = chunk.getNextChunkLocation(currentCap.rBaseKey);
                                if (! nextChunkMapKey.isPresent())
                                    return CompletableFuture.completedFuture(true);
                                return deleteAllChunks(currentCap.withMapKey(nextChunkMapKey.get()), signer, tid, network);
                            })
                            .thenCompose(b -> {
                                if (! (mOpt.get() instanceof DirAccess))
                                    return CompletableFuture.completedFuture(true);
                                Set<AbsoluteCapability> childCaps = ((DirAccess) mOpt.get()).getChildrenCapabilities(currentCap);
                                return Futures.reduceAll(childCaps,
                                        true,
                                        (x, cap) -> deleteAllChunks((WritableAbsoluteCapability) cap, signer, tid, network),
                                        (x, y) -> x && y);
                            })
                            .thenCompose(b -> removeSigningKey(currentCap.writer, signer, currentCap.owner, network));
                });
    }

    /**
     * @param parent
     * @param network
     * @return updated parent
     */
    @JsMethod
    public CompletableFuture<FileWrapper> remove(FileWrapper parent, NetworkAccess network) {
        ensureUnmodified();
        if (! pointer.capability.isWritable())
            return Futures.errored(new IllegalStateException("Cannot delete file without write access to it"));

        boolean writableParent = parent.isWritable();
        return (writableParent ? parent.removeChild(this, network) : CompletableFuture.completedFuture(parent))
                .thenCompose(updatedParent -> IpfsTransaction.call(owner(),
                        tid -> FileWrapper.deleteAllChunks(writableFilePointer(),
                                writableParent ? parent.signingPair() : signingPair(), tid, network), network.dhtClient)
                        .thenApply(b -> updatedParent));
    }

    public static CompletableFuture<Boolean> removeSigningKey(PublicKeyHash signerToRemove,
                                                              SigningPrivateKeyAndPublicHash parentSigner,
                                                              PublicKeyHash owner,
                                                              NetworkAccess network) {
        if (parentSigner.publicKeyHash.equals(signerToRemove))
            return CompletableFuture.completedFuture(true);

        PublicKeyHash parentWriter = parentSigner.publicKeyHash;
        return WriterData.getWriterData(owner, parentWriter, network.mutable, network.dhtClient)
                .thenCompose(parentWriterData -> {

            Set<PublicKeyHash> ownedKeys = new HashSet<>(parentWriterData.props.ownedKeys);
            ownedKeys.remove(signerToRemove);
            WriterData updatedParentWD = parentWriterData.props.withOwnedKeys(ownedKeys);
            return IpfsTransaction.call(owner, tid ->
                    updatedParentWD.commit(owner, parentSigner,
                            parentWriterData.hash, network, tid).thenApply(cwd -> true), network.dhtClient);

        });
    }

    public CompletableFuture<? extends AsyncReader> getInputStream(NetworkAccess network, SafeRandom random,
                                                                   ProgressConsumer<Long> monitor) {
        return getInputStream(network, random, getFileProperties().size, monitor);
    }

    @JsMethod
    public CompletableFuture<? extends AsyncReader> getInputStream(NetworkAccess network,
                                                                   SafeRandom random,
                                                                   int fileSizeHi,
                                                                   int fileSizeLow,
                                                                   ProgressConsumer<Long> monitor) {
        return getInputStream(network, random, fileSizeLow + ((fileSizeHi & 0xFFFFFFFFL) << 32), monitor);
    }

    public CompletableFuture<? extends AsyncReader> getInputStream(NetworkAccess network,
                                                                   SafeRandom random,
                                                                   long fileSize,
                                                                   ProgressConsumer<Long> monitor) {
        ensureUnmodified();
        if (pointer.fileAccess.isDirectory())
            throw new IllegalStateException("Cannot get input stream for a directory!");
        FileAccess fileAccess = (FileAccess) pointer.fileAccess;
        SymmetricKey baseKey = pointer.capability.rBaseKey;
        SymmetricKey dataKey = fileAccess.getDataKey(baseKey);
        return fileAccess.retriever().getFile(network, random, dataKey, fileSize, getLocation(), fileAccess.committedHash(), monitor);
    }

    private FileRetriever getRetriever() {
        if (pointer.fileAccess.isDirectory())
            throw new IllegalStateException("Cannot get input stream for a directory!");
        FileAccess fileAccess = (FileAccess) pointer.fileAccess;
        return fileAccess.retriever();
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
        return new FileWrapper(Optional.of(root), null, Optional.empty(), null);
    }

    public static byte[] generateThumbnail(byte[] imageBlob) {
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
            return baos.toByteArray();
        } catch (IOException ioe) {
            LOG.log(Level.WARNING, ioe.getMessage(), ioe);
        }
        return new byte[0];
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

    private CompletableFuture<byte[]> generateThumbnail(NetworkAccess network, AsyncReader fileData, int fileSize, String filename) {
        CompletableFuture<byte[]> fut = new CompletableFuture<>();
        if (fileSize > MimeTypes.HEADER_BYTES_TO_IDENTIFY_MIME_TYPE) {
            getFileType(fileData).thenAccept(mimeType -> {
                if (mimeType.startsWith("image")) {
                    if (network.isJavascript()) {
                        thumbnail.generateThumbnail(fileData, fileSize, filename).thenAccept(base64Str -> {
                            byte[] bytesOfData = Base64.getDecoder().decode(base64Str);
                            fut.complete(bytesOfData);
                        });
                    } else {
                        byte[] bytes = new byte[fileSize];
                        fileData.readIntoArray(bytes, 0, fileSize).thenAccept(data -> {
                            fut.complete(generateThumbnail(bytes));
                        });
                    }
                } else if (mimeType.startsWith("video")) {
                    if (network.isJavascript()) {
                        thumbnail.generateVideoThumbnail(fileData, fileSize, filename).thenAccept(base64Str -> {
                            byte[] bytesOfData = Base64.getDecoder().decode(base64Str);
                            fut.complete(bytesOfData);
                        });
                    } else {
                        byte[] bytes = new byte[fileSize];
                        fileData.readIntoArray(bytes, 0, fileSize).thenAccept(data -> {
                            fut.complete(generateVideoThumbnail(bytes));
                        });
                    }
                } else if (mimeType.startsWith("audio/mpeg")) {
                    byte[] mp3Data = new byte[fileSize];
                    fileData.readIntoArray(mp3Data, 0, fileSize).thenAccept(read -> {
                        Mp3CoverImage mp3CoverImage = Mp3CoverImage.extractCoverArt(mp3Data);
                        if (network.isJavascript()) {
                            AsyncReader.ArrayBacked imageBlob = new AsyncReader.ArrayBacked(mp3CoverImage.imageData);
                            thumbnail.generateThumbnail(imageBlob, mp3CoverImage.imageData.length, filename)
                                    .thenAccept(base64Str -> {
                                        byte[] bytesOfData = Base64.getDecoder().decode(base64Str);
                                        fut.complete(bytesOfData);
                                    });
                        } else {
                            fut.complete(generateThumbnail(mp3CoverImage.imageData));
                        }
                    });
                } else {
                    fut.complete(new byte[0]);
                }
            });
        } else {
            fut.complete(new byte[0]);
        }
        return fut;
    }

    private CompletableFuture<String> getFileType(AsyncReader imageBlob) {
        CompletableFuture<String> result = new CompletableFuture<>();
        byte[] data = new byte[MimeTypes.HEADER_BYTES_TO_IDENTIFY_MIME_TYPE];
        imageBlob.readIntoArray(data, 0, data.length).thenAccept(numBytesRead -> {
            imageBlob.reset().thenAccept(resetResult -> {
                if (numBytesRead < data.length) {
                    result.complete("");
                } else {
                    String mimeType = MimeTypes.calculateMimeType(data);
                    result.complete(mimeType);
                }
            });
        });
        return result;
    }

    public static CompletableFuture<String> calculateMimeType(AsyncReader data, long fileSize) {
        byte[] header = new byte[(int) Math.min(fileSize, MimeTypes.HEADER_BYTES_TO_IDENTIFY_MIME_TYPE)];
        return data.readIntoArray(header, 0, header.length)
                .thenApply(read -> MimeTypes.calculateMimeType(header));
    }
}
