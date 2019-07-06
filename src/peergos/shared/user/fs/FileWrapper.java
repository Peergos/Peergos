package peergos.shared.user.fs;
import java.nio.file.*;
import java.util.logging.*;

import jsinterop.annotations.*;
import peergos.shared.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.crypto.random.*;
import peergos.shared.crypto.symmetric.*;
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
    private final Optional<SigningPrivateKeyAndPublicHash> entryWriter;
    private final FileProperties props;
    private final String ownername;
    private final Optional<TrieNode> globalRoot;
    private final Snapshot version;
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
                       String ownername,
                       Snapshot version) {
        this.globalRoot = globalRoot;
        this.pointer = pointer;
        this.entryWriter = entryWriter;
        this.ownername = ownername;
        this.version = version;
        if (pointer == null)
            props = new FileProperties("/", true, "", 0, LocalDateTime.MIN, false, Optional.empty());
        else {
            SymmetricKey parentKey = this.getParentKey();
            props = pointer.fileAccess.getProperties(parentKey);
        }
        if (isWritable() && !signingPair().publicKeyHash.equals(pointer.capability.writer))
            throw new IllegalStateException("Invalid FileWrapper! public writing keys don't match!");
    }

    public FileWrapper(RetrievedCapability pointer,
                       Optional<SigningPrivateKeyAndPublicHash> entryWriter,
                       String ownername,
                       Snapshot version) {
        this(Optional.empty(), pointer, entryWriter, ownername, version);
    }

    public FileWrapper withTrieNode(TrieNode trie) {
        return new FileWrapper(Optional.of(trie), pointer, entryWriter, ownername, version);
    }

    public FileWrapper withVersion(Snapshot version) {
        return new FileWrapper(globalRoot, pointer, entryWriter, ownername, version);
    }

    @JsMethod
    public boolean equals(Object other) {
        if (other == null)
            return false;
        if (!(other instanceof FileWrapper))
            return false;
        return pointer.equals(((FileWrapper) other).getPointer());
    }

    public CompletableFuture<FileWrapper> getUpdated(Snapshot version, NetworkAccess network) {
        if (this.version.get(writer()).equals(version.get(writer())))
            return CompletableFuture.completedFuture(this);
        return network.getFile(version, pointer.capability, entryWriter, ownername)
                .thenApply(Optional::get);
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
        return getChildren(version, network).thenCompose(children -> {
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

    public CompletableFuture<Snapshot> updateChildLinks(
            Snapshot version,
            Committer committer,
            Collection<Pair<AbsoluteCapability, AbsoluteCapability>> childCases,
            NetworkAccess network,
            SafeRandom random,
            Hasher hasher) {
        return pointer.fileAccess
                .updateChildLinks(version, committer, (WritableAbsoluteCapability) pointer.capability, entryWriter,
                        childCases, network, hasher);
    }

    /**
     *
     * @param version
     * @param committer
     * @param childrenCaps
     * @param network
     * @param crypto
     * @return An updated version of this directory
     */
    public CompletableFuture<Snapshot> addChildLinks(Snapshot version,
                                                     Committer committer,
                                                     Collection<WritableAbsoluteCapability> childrenCaps,
                                                     NetworkAccess network,
                                                     Crypto crypto) {
        return pointer.fileAccess
                .addChildrenAndCommit(version, committer, childrenCaps.stream()
                                .map(childCap -> ((WritableAbsoluteCapability)pointer.capability).relativise(childCap))
                                .collect(Collectors.toList()), (WritableAbsoluteCapability) pointer.capability, entryWriter,
                        network, crypto);
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
    public CompletableFuture<FileWrapper> rotateReadKeys(NetworkAccess network, SafeRandom random, Hasher hasher, FileWrapper parent) {
        Optional<SymmetricKey> newBaseKey = Optional.of(SymmetricKey.random());
        WritableAbsoluteCapability ourNewCap = writableFilePointer().withBaseKey(newBaseKey.get());
        return network.synchronizer.applyComplexUpdate(owner(), signingPair(), (current, committer) ->
                rotateReadKeys(true, network, random, hasher, parent, newBaseKey, current, committer))
                .thenCompose(version -> network.getFile(version, ourNewCap, entryWriter, ownername)
                        .thenApply(newUs -> newUs.get()));
    }

    private CompletableFuture<Snapshot> rotateReadKeys(boolean updateParent,
                                                       NetworkAccess network,
                                                       SafeRandom random,
                                                       Hasher hasher,
                                                       FileWrapper parent,
                                                       Optional<SymmetricKey> newBaseKey,
                                                       Snapshot version,
                                                       Committer committer) {
        if (!isWritable())
            throw new IllegalStateException("You cannot rotate read keys without write access!");
        WritableAbsoluteCapability cap = writableFilePointer();
        if (isDirectory()) {
            // create a new rBaseKey == subfoldersKey and make all descendants dirty
            SymmetricKey newSubfoldersKey = newBaseKey.orElseGet(SymmetricKey::random);
            WritableAbsoluteCapability ourNewPointer = cap.withBaseKey(newSubfoldersKey);
            SymmetricKey newParentKey = SymmetricKey.random();
            FileProperties props = getFileProperties();

            CryptreeNode existing = pointer.fileAccess;

            byte[] nextChunkMapKey = existing.getNextChunkLocation(cap.rBaseKey);
            WritableAbsoluteCapability nextChunkCap = cap.withMapKey(nextChunkMapKey);
            WritableAbsoluteCapability updatedNextChunkCap = ourNewPointer.withMapKey(nextChunkMapKey);
            RelativeCapability toNextChunk = ourNewPointer.relativise(updatedNextChunkCap);

            Optional<SymmetricLinkToSigner> writerLink = existing.getWriterLink(cap.rBaseKey);
            Optional<SigningPrivateKeyAndPublicHash> signer = writerLink.map(link -> link.target(cap.wBaseKey.get()));

            // re add children
            return existing.getDirectChildren(pointer.capability.rBaseKey, network)
                    .thenCompose(children ->  IpfsTransaction.call(owner(),
                            tid -> CryptreeNode.createDir(existing.committedHash(), newSubfoldersKey, cap.wBaseKey.get(),
                                    signer, props, Optional.of(cap.relativise(parent.writableFilePointer())),
                                    newParentKey, toNextChunk, new CryptreeNode.ChildrenLinks(children), hasher)
                                    .commit(version, committer, ourNewPointer, entryWriter, network, tid), network.dhtClient))
                    .thenCompose(updatedVersion -> network.getFile(updatedVersion, ourNewPointer, entryWriter, ownername)
                            .thenCompose(usOpt -> {
                                FileWrapper theNewUs = usOpt.get();

                                // clean all subtree keys except file dataKeys (lazily re-key and re-encrypt them)
                                return getDirectChildren(network, updatedVersion).thenCompose(childFiles -> {
                                    List<Pair<FileWrapper, SymmetricKey>> childrenWithNewKeys = childFiles.stream()
                                            .map(c -> new Pair<>(c, SymmetricKey.random()))
                                            .collect(Collectors.toList());
                                    return Futures.reduceAll(childrenWithNewKeys, updatedVersion,
                                            (s, childAndKey) -> childAndKey.left.rotateReadKeys(false,
                                                    network, random, hasher, theNewUs,
                                                    Optional.of(childAndKey.right), s, committer), (a, b) -> b)
                                            .thenCompose(newVersion -> theNewUs.updateChildLinks(newVersion, committer,
                                                    childrenWithNewKeys.stream()
                                                            .map(p -> new Pair<>(p.left.pointer.capability,
                                                                    p.left.pointer.capability.withBaseKey(p.right)))
                                                            .collect(Collectors.toList()),
                                                    network, random, hasher))
                                            .thenCompose(finished ->
                                                    // update pointer from parent to us
                                                    (updateParent ? parent.pointer.fileAccess
                                                            .updateChildLink(finished, committer,
                                                                    (WritableAbsoluteCapability) parent.pointer.capability,
                                                                    parent.entryWriter, this.pointer,
                                                                    theNewUs.pointer, network, hasher) :
                                                            CompletableFuture.completedFuture(finished))
                                            );
                                });
                            })
                    ).thenCompose(updated -> {
                        return network.getMetadata(updated.get(nextChunkCap.writer).props, nextChunkCap)
                                .thenCompose(mOpt -> {
                                    if (! mOpt.isPresent())
                                        return CompletableFuture.completedFuture(updated);
                                    return new FileWrapper(new RetrievedCapability(nextChunkCap, mOpt.get()),
                                            entryWriter, ownername, updated)
                                            .rotateReadKeys(false, network, random, hasher, parent,
                                                    Optional.of(newSubfoldersKey), updated, committer);
                                });
                    }).thenApply(x -> {
                        setModified();
                        return x;
                    });
        } else {
            // create a new rBaseKey == parentKey
            SymmetricKey baseReadKey = newBaseKey.orElseGet(SymmetricKey::random);
            return pointer.fileAccess.rotateBaseReadKey(writableFilePointer(), entryWriter,
                    cap.relativise(parent.getMinimalReadPointer()), baseReadKey, network, version, committer)
                    .thenCompose(updatedVersion -> {
                        byte[] nextChunkMapKey = pointer.fileAccess.getNextChunkLocation(cap.rBaseKey);
                        WritableAbsoluteCapability nextChunkCap = cap.withMapKey(nextChunkMapKey);
                        return network.getMetadata(updatedVersion.get(nextChunkCap.writer).props, nextChunkCap)
                                .thenCompose(mOpt -> {
                                    if (! mOpt.isPresent())
                                        return CompletableFuture.completedFuture(updatedVersion);
                                    return new FileWrapper(new RetrievedCapability(nextChunkCap, mOpt.get()),
                                            entryWriter, ownername, updatedVersion)
                                            .rotateReadKeys(false, network, random, hasher, parent,
                                                    Optional.of(baseReadKey), updatedVersion, committer);
                                });
                    }).thenCompose(newVersion -> {
                        // only update link from parent folder to file if we are the first chunk
                        return (updateParent ?
                                network.retrieveMetadata(this.writableFilePointer().withBaseKey(baseReadKey), newVersion)
                                        .thenCompose(meta -> parent.pointer.fileAccess
                                                .updateChildLink(newVersion, committer, parent.writableFilePointer(),
                                                        parent.entryWriter, pointer, meta.get(), network, hasher)) :
                                CompletableFuture.completedFuture(newVersion)
                        );
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
     * @param crypto
     * @return The updated version of this file/directory and its parent
     */
    public CompletableFuture<Pair<FileWrapper, FileWrapper>> rotateWriteKeys(FileWrapper parent,
                                                                             NetworkAccess network,
                                                                             Crypto crypto) {
        Optional<SymmetricKey> newBaseWriteKey = Optional.of(SymmetricKey.random());
        WritableAbsoluteCapability ourNewCap = writableFilePointer().withBaseWriteKey(newBaseWriteKey.get());
        return network.synchronizer.applyComplexUpdate(owner(), signingPair(), (current, committer) ->
                rotateWriteKeys(true, parent, newBaseWriteKey, network, crypto, current, committer))
                .thenCompose(s -> s.withWriter(owner(), parent.writer(), network))
                .thenCompose(s -> parent.getUpdated(s, network)
                        .thenCompose(p -> network.getFile(s, ourNewCap, entryWriter, ownername)
                                .thenApply(newUs -> new Pair<>(newUs.get(), p))));
    }

    private CompletableFuture<Snapshot> rotateWriteKeys(boolean updateParent,
                                                        FileWrapper parent,
                                                        Optional<SymmetricKey> suppliedBaseWriteKey,
                                                        NetworkAccess network,
                                                        Crypto crypto,
                                                        Snapshot version,
                                                        Committer committer) {
        if (!isWritable())
            throw new IllegalStateException("You cannot rotate write keys without write access!");
        WritableAbsoluteCapability cap = writableFilePointer();
        SymmetricKey newBaseWriteKey = suppliedBaseWriteKey.orElseGet(SymmetricKey::random);
        WritableAbsoluteCapability ourNewPointer = cap.withBaseWriteKey(newBaseWriteKey);

        if (isDirectory()) {
            CryptreeNode existing = pointer.fileAccess;
            Optional<SymmetricLinkToSigner> updatedWriter = existing.getWriterLink(cap.rBaseKey)
                    .map(toSigner -> SymmetricLinkToSigner.fromPair(newBaseWriteKey, toSigner.target(cap.wBaseKey.get())));
            CryptreeNode.DirAndChildren updatedDirAccess = existing.withWriterLink(cap.rBaseKey, updatedWriter)
                    .withChildren(cap.rBaseKey, CryptreeNode.ChildrenLinks.empty(), crypto.hasher);

            byte[] nextChunkMapKey = existing.getNextChunkLocation(cap.rBaseKey);
            WritableAbsoluteCapability nextChunkCap = cap.withMapKey(nextChunkMapKey);

            RetrievedCapability ourNewRetrievedPointer = new RetrievedCapability(ourNewPointer, updatedDirAccess.dir);
            FileWrapper theNewUs = new FileWrapper(ourNewRetrievedPointer, entryWriter, ownername, version);

            // clean all subtree write keys
            return IpfsTransaction.call(owner(),
                    tid -> updatedDirAccess.commitChildrenLinks(ourNewPointer, entryWriter, network, tid), network.dhtClient)
                    .thenCompose(hashes -> getDirectChildren(network, version))
                    .thenCompose(childFiles -> {
                        Set<Pair<FileWrapper, SymmetricKey>> withNewBaseWriteKeys = childFiles.stream()
                                .map(c -> new Pair<>(c, SymmetricKey.random()))
                                .collect(Collectors.toSet());
                        List<WritableAbsoluteCapability> childPointers = withNewBaseWriteKeys.stream()
                                .map(p -> ((WritableAbsoluteCapability)p.left.pointer.capability)
                                        .withBaseWriteKey(p.right))
                                .collect(Collectors.toList());

                        return Futures.reduceAll(withNewBaseWriteKeys, version,
                                (s, pair) -> pair.left.rotateWriteKeys(false, theNewUs, Optional.of(pair.right),
                                        network, crypto, s, committer), (a, b) -> b)
                                .thenCompose(version2 -> theNewUs.addChildLinks(version2, committer, childPointers, network, crypto));
                    }).thenCompose(updatedVersion ->
                            // update pointer from parent to us
                            (updateParent ?
                                    updatedVersion.withWriter(owner(), parent.writer(), network)
                                            .thenCompose(withParent -> network.retrieveMetadata(this.writableFilePointer(), withParent)
                                                    .thenCompose(updatedUs -> parent.pointer.fileAccess
                                                            .updateChildLink(withParent, committer,
                                                                    parent.writableFilePointer(),
                                                                    parent.entryWriter, this.pointer,
                                                                    updatedUs.get(), network, crypto.hasher))) :
                                    CompletableFuture.completedFuture(updatedVersion))
                    ).thenCompose(updatedVersion -> {
                        return network.getMetadata(version.get(nextChunkCap.writer).props, nextChunkCap)
                                .thenCompose(mOpt -> {
                                    if (! mOpt.isPresent())
                                        return CompletableFuture.completedFuture(updatedVersion);
                                    return new FileWrapper(new RetrievedCapability(nextChunkCap, mOpt.get()),
                                            Optional.of(signingPair()), ownername, version)
                                            .rotateWriteKeys(false, parent,
                                                    Optional.of(newBaseWriteKey), network, crypto, updatedVersion, committer);
                                });
                    }).thenApply(x -> {
                        setModified();
                        return x;
                    });
        } else {
            CryptreeNode existing = pointer.fileAccess;
            // Only need to do the first chunk, because only those can have writer links
            return existing.rotateBaseWriteKey(cap, entryWriter, newBaseWriteKey, network, version, committer);
        }
    }

    public CompletableFuture<Boolean> hasChildWithName(Snapshot version, String name, NetworkAccess network) {
        ensureUnmodified();
        return getChildren(version, network)
                .thenApply(children -> children.stream().anyMatch(c -> c.props.name.equals(name)));
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

    public CompletableFuture<Snapshot> addLinkTo(Snapshot version,
                                                 Committer committer,
                                                 String name,
                                                 WritableAbsoluteCapability fileCap,
                                                 NetworkAccess network,
                                                 Crypto crypto) {
        ensureUnmodified();
        if (!this.isDirectory() || !this.isWritable()) {
            return Futures.errored(new IllegalArgumentException("Can only add link to a writable directory!"));
        }
        return hasChildWithName(version, name, network).thenCompose(hasChild -> {
            if (hasChild) {
                return Futures.errored(new IllegalStateException("Child already exists with name: " + name));
            }
            CryptreeNode toUpdate = pointer.fileAccess;
            return toUpdate.addChildAndCommit(version, committer, writableFilePointer().relativise(fileCap),
                    writableFilePointer(), entryWriter, network, crypto);
        });
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
        return pointer != null && pointer.capability instanceof WritableAbsoluteCapability;
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

    public Location getNextChunkLocation() {
        return new Location(pointer.capability.owner, pointer.capability.writer,
                pointer.fileAccess.getNextChunkLocation(pointer.capability.rBaseKey));
    }

    public CompletableFuture<Set<AbsoluteCapability>> getChildrenCapabilities(NetworkAccess network) {
        ensureUnmodified();
        if (!this.isDirectory())
            return CompletableFuture.completedFuture(Collections.emptySet());
        return pointer.fileAccess.getAllChildrenCapabilities(version, pointer.capability, network);
    }

    public CompletableFuture<Optional<FileWrapper>> retrieveParent(NetworkAccess network) {
        ensureUnmodified();
        if (pointer == null)
            return CompletableFuture.completedFuture(Optional.empty());
        AbsoluteCapability cap = pointer.capability;
        CompletableFuture<RetrievedCapability> parent = pointer.fileAccess.getParent(cap.owner, cap.writer, cap.rBaseKey, network, version);
        return parent.thenApply(parentRFP -> {
            if (parentRFP == null)
                return Optional.empty();
            return Optional.of(new FileWrapper(parentRFP, Optional.empty(), ownername, version));
        });
    }

    @JsMethod
    public boolean isUserRoot() {
        if (pointer == null)
            return false;
        return ! pointer.fileAccess.hasParentLink(pointer.capability.rBaseKey);
    }

    public SymmetricKey getParentKey() {
        ensureUnmodified();
        SymmetricKey baseKey = pointer.capability.rBaseKey;
        if (this.isDirectory())
            try {
                return pointer.fileAccess.getParentKey(baseKey);
            } catch (Exception e) {
                // if we don't have read access to this folder, then we must just have the parent key already
            }
        return baseKey;
    }

    private Optional<SigningPrivateKeyAndPublicHash> getChildsEntryWriter() {
        return pointer.capability.wBaseKey
                .map(wBase -> pointer.fileAccess.getSigner(pointer.capability.rBaseKey, wBase, entryWriter));
    }

    @JsMethod
    public CompletableFuture<Set<FileWrapper>> getChildren(NetworkAccess network) {
        return getChildren(version, network);
    }

    private CompletableFuture<Set<FileWrapper>> getChildren(Snapshot version, NetworkAccess network) {
        if (globalRoot.isPresent())
            return globalRoot.get().getChildren("/", network);
        if (isReadable()) {
            Optional<SigningPrivateKeyAndPublicHash> childsEntryWriter = pointer.capability.wBaseKey
                    .map(wBase -> pointer.fileAccess.getSigner(pointer.capability.rBaseKey, wBase, entryWriter));
            return pointer.fileAccess.getAllChildrenCapabilities(version, pointer.capability, network)
                    .thenCompose(childCaps ->
                            getFiles(owner(), childCaps, childsEntryWriter, ownername, network, version));
        }
        throw new IllegalStateException("Unreadable FileWrapper!");
    }

    private CompletableFuture<Set<FileWrapper>> getDirectChildren(NetworkAccess network, Snapshot version) {
        ensureUnmodified();
        if (globalRoot.isPresent())
            return globalRoot.get().getChildren("/", network);
        if (isReadable()) {
            Optional<SigningPrivateKeyAndPublicHash> childsEntryWriter = pointer.capability.wBaseKey
                    .map(wBase -> pointer.fileAccess.getSigner(pointer.capability.rBaseKey, wBase, entryWriter));
            return pointer.fileAccess.getDirectChildrenCapabilities(pointer.capability, network)
                    .thenCompose(childCaps ->
                            getFiles(owner(), childCaps, childsEntryWriter, ownername, network, version));
        }
        throw new IllegalStateException("Unreadable FileWrapper!");
    }

    private static CompletableFuture<Set<FileWrapper>> getFiles(PublicKeyHash owner,
                                                               Set<AbsoluteCapability> caps,
                                                               Optional<SigningPrivateKeyAndPublicHash> entryWriter,
                                                               String ownername,
                                                               NetworkAccess network,
                                                               Snapshot version) {
        Set<PublicKeyHash> childWriters = caps.stream()
                .map(c -> c.writer)
                .collect(Collectors.toSet());
        return version.withWriters(owner, childWriters, network)
                .thenCompose(fullVersion -> network.retrieveAllMetadata(new ArrayList<>(caps), fullVersion)
                        .thenApply(rcs -> rcs.stream()
                                .map(rc -> new FileWrapper(rc, entryWriter, ownername, fullVersion))
                                .collect(Collectors.toSet())));
    }

    public CompletableFuture<Optional<FileWrapper>> getChild(String name, NetworkAccess network) {
        return getChild(version, name, network);
    }

    private CompletableFuture<Optional<FileWrapper>> getChild(Snapshot version, String name, NetworkAccess network) {
        return getChildren(version, network)
                .thenApply(children -> children.stream().filter(f -> f.getName().equals(name)).findAny());
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

    @JsMethod
    public boolean isShared(UserContext context) {
        return context.sharedWithCache.isShared(pointer.capability);
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
     * @param parent
     * @return updated parent dir
     */
    public CompletableFuture<Pair<FileWrapper, Snapshot>> clean(Snapshot current,
                                                                Committer committer,
                                                                NetworkAccess network,
                                                                Crypto crypto,
                                                                FileWrapper parent) {
        if (!isDirty())
            return CompletableFuture.completedFuture(new Pair<>(this, current));
        if (isDirectory()) {
            throw new IllegalStateException("Directories are never dirty (they are cleaned immediately)!");
        } else {
            return pointer.fileAccess.cleanAndCommit(current, committer, writableFilePointer(), signingPair(),
                    SymmetricKey.random(), parent.getLocation(), parent.getParentKey(), network, crypto)
                    .thenApply(cwd -> {
                        setModified();
                        return new Pair<>(parent, cwd);
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
                                                       Crypto crypto,
                                                       ProgressConsumer<Long> monitor,
                                                       TransactionService transactions) {
        long fileSize = (lengthLow & 0xFFFFFFFFL) + ((lengthHi & 0xFFFFFFFFL) << 32);
        if (transactions == null) // we are in a public writable link
            return network.synchronizer.applyComplexUpdate(owner(), signingPair(),
                    (s, committer) -> uploadFileSection(s, committer, filename, fileData,
                            false, 0, fileSize, Optional.empty(), overwriteExisting,
                            network, crypto, monitor, generateChildLocationsFromSize(fileSize, crypto.random))
            ).thenCompose(finished -> getUpdated(finished, network));
        return getPath(network).thenCompose(path ->
                Transaction.buildFileUploadTransaction(Paths.get(path).resolve(filename).toString(), fileSize, fileData, signingPair(),
                        generateChildLocationsFromSize(fileSize, crypto.random)))
                .thenCompose(txn -> network.synchronizer.applyComplexUpdate(owner(), transactions.getSigner(),
                        (s, committer) -> transactions.open(s, committer, txn).thenCompose(v -> fileData.reset()
                                .thenCompose(reset -> uploadFileSection(v, committer, filename, reset,
                                        false, 0, fileSize, Optional.empty(), overwriteExisting,
                                        network, crypto, monitor, txn.getLocations()))
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

    public CompletableFuture<FileWrapper> uploadOrOverwriteFile(String filename,
                                                                AsyncReader fileData,
                                                                long length,
                                                                NetworkAccess network,
                                                                Crypto crypto,
                                                                ProgressConsumer<Long> monitor,
                                                                List<Location> locations) {
        return uploadFileSection(filename, fileData, false, 0, length, Optional.empty(),
                true, network, crypto, monitor, locations);
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
     * @param locations The planned locations for the chunks
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
                                                            List<Location> locations) {
        return network.synchronizer.applyComplexUpdate(owner(), signingPair(), (current, committer) ->
                uploadFileSection(current, committer, filename, fileData, isHidden, startIndex, endIndex,
                        baseKey, overwriteExisting, network, crypto, monitor, locations))
                .thenCompose(finalBase -> getUpdated(finalBase, network));
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
                                                         NetworkAccess network,
                                                         Crypto crypto,
                                                         ProgressConsumer<Long> monitor,
                                                         List<Location> locations) {
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
                        .thenCompose(latest -> latest.getChild(current, filename, network)
                                        .thenCompose(childOpt -> {
                                            if (childOpt.isPresent()) {
                                                if (! overwriteExisting)
                                                    throw new IllegalStateException("File already exists with name " + filename);
                                                return updateExistingChild(current, committer, latest, childOpt.get(), fileData,
                                                        startIndex, endIndex, network, crypto, monitor);
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
                                            int thumbnailSrcImageSize = startIndex == 0 && endIndex < Integer.MAX_VALUE ? (int) endIndex : 0;

                                            return calculateMimeType(fileData, endIndex, filename).thenCompose(mimeType -> fileData.reset()
                                                    .thenCompose(resetReader -> {
                                                        FileProperties fileProps = new FileProperties(filename, false, mimeType, endIndex,
                                                                LocalDateTime.now(), isHidden, Optional.empty());

                                                        FileUploader chunks = new FileUploader(filename, mimeType, resetReader,
                                                                startIndex, endIndex, fileKey, dataKey, parentLocation, dirParentKey, monitor, fileProps,
                                                                locations);

                                                        SigningPrivateKeyAndPublicHash signer = signingPair();
                                                        WritableAbsoluteCapability fileWriteCap = new
                                                                WritableAbsoluteCapability(owner(),
                                                                signer.publicKeyHash,
                                                                locations.get(0).getMapKey(), fileKey,
                                                                fileWriteKey);

                                                        return chunks.upload(current, committer, network, parentLocation.owner, signer, crypto.hasher)
                                                                .thenCompose(updatedWD -> latest.addChildPointer(updatedWD, committer, fileWriteCap, network, crypto))
                                                                .thenCompose(cwd -> fileData.reset().thenCompose(resetAgain ->
                                                                        generateThumbnailAndUpdate(cwd, committer, fileWriteCap, filename, resetAgain,
                                                                                network, thumbnailSrcImageSize, isHidden, mimeType,
                                                                                endIndex, LocalDateTime.now())));
                                                    }));
                                        })
                        )
                );
    }

    private CompletableFuture<Snapshot> generateThumbnailAndUpdate(Snapshot base,
                                                                   Committer committer,
                                                                   WritableAbsoluteCapability cap,
                                                                   String fileName,
                                                                   AsyncReader fileData,
                                                                   NetworkAccess network,
                                                                   int thumbNailSize,
                                                                   Boolean isHidden,
                                                                   String mimeType,
                                                                   long endIndex,
                                                                   LocalDateTime updatedDateTime) {
        return generateThumbnail(network, fileData, thumbNailSize, fileName)
                .thenCompose(thumbData -> {
                    FileProperties fileProps = new FileProperties(fileName, false, mimeType, endIndex,
                            updatedDateTime, isHidden, thumbData);

                    return network.getFile(base, cap, getChildsEntryWriter(), ownername)
                            .thenCompose(child -> child.get()
                                    .getPointer().fileAccess.updateProperties(base, committer, cap,
                                            getChildsEntryWriter(), fileProps, network));
                });
    }

    private CompletableFuture<Snapshot> addChildPointer(Snapshot current,
                                                        Committer committer,
                                                        WritableAbsoluteCapability childPointer,
                                                        NetworkAccess network,
                                                        Crypto crypto) {
        return pointer.fileAccess.addChildAndCommit(current, committer, writableFilePointer().relativise(childPointer),
                writableFilePointer(), entryWriter, network, crypto)
                .thenApply(newBase -> {
                    setModified();
                    return newBase;
                });
    }

    public CompletableFuture<FileWrapper> appendToChild(String filename,
                                                        byte[] fileData,
                                                        boolean isHidden,
                                                        NetworkAccess network,
                                                        Crypto crypto,
                                                        ProgressConsumer<Long> monitor) {
        return getChild(filename, network)
                .thenCompose(child -> uploadFileSection(filename, AsyncReader.build(fileData), isHidden,
                        child.map(f -> f.getSize()).orElse(0L),
                        fileData.length + child.map(f -> f.getSize()).orElse(0L),
                        child.map(f -> f.getPointer().capability.rBaseKey), true, network, crypto,
                        monitor, generateChildLocationsFromSize(fileData.length, crypto.random)));
    }

    /**
     *
     * @param current
     * @param committer
     * @param parent
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
                                                            FileWrapper parent,
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

        Supplier<Location> locationSupplier = () -> new Location(getLocation().owner, getLocation().writer, crypto.random.randomBytes(32));

        WritableAbsoluteCapability childCap = existingChild.writableFilePointer();
        return current.withWriter(existingChild.owner(), existingChild.writer(), network)
                .thenCompose(state -> (existingChild.isDirty() ?
                                existingChild.clean(state, committer, network, crypto, parent)
                                        .thenCompose(pair -> pair.left.getChild(pair.right, filename, network)
                                                .thenApply(cleanedChild -> new Triple<>(pair.left, cleanedChild.get(), pair.right))) :
                        CompletableFuture.completedFuture(new Triple<>(this, existingChild, state)))
                ).thenCompose(updatedTriple -> {
                    FileWrapper us = updatedTriple.left;
                    FileWrapper child = updatedTriple.middle;
                    Snapshot base = updatedTriple.right;
                    FileProperties childProps = child.getFileProperties();
                    final AtomicLong filesSize = new AtomicLong(childProps.size);
                    FileRetriever retriever = child.getRetriever();
                    SymmetricKey baseKey = child.pointer.capability.rBaseKey;
                    CryptreeNode fileAccess = child.pointer.fileAccess;
                    SymmetricKey dataKey = fileAccess.getDataKey(baseKey);

                    List<Long> startIndexes = new ArrayList<>();

                    for (long startIndex = inputStartIndex; startIndex < endIndex; startIndex = startIndex + Chunk.MAX_SIZE - (startIndex % Chunk.MAX_SIZE))
                        startIndexes.add(startIndex);

                    BiFunction<Snapshot, Long, CompletableFuture<Snapshot>> composer = (version, startIndex) -> {
                        MaybeMultihash currentHash = child.pointer.fileAccess.committedHash();
                        return retriever.getChunk(version.get(child.writer()).props, network, crypto.random, startIndex, filesSize.get(), childCap, currentHash, monitor)
                                .thenCompose(currentLocation -> {
                                    CompletableFuture<Optional<Location>> locationAt = retriever
                                            .getMapLabelAt(version.get(child.writer()).props, childCap, startIndex + Chunk.MAX_SIZE, network)
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
                                            child.pointer.fileAccess.getWriterLink(child.pointer.capability.rBaseKey) :
                                            Optional.empty();

                                    return fileData.readIntoArray(raw, internalStart, internalEnd - internalStart).thenCompose(read -> {

                                        Chunk updated = new Chunk(raw, dataKey, currentOriginal.location.getMapKey(), dataKey.createNonce());
                                        LocatedChunk located = new LocatedChunk(currentOriginal.location, currentOriginal.existingHash, updated);
                                        long currentSize = filesSize.get();
                                        FileProperties newProps = new FileProperties(childProps.name, false, childProps.mimeType,
                                                endIndex > currentSize ? endIndex : currentSize,
                                                LocalDateTime.now(), childProps.isHidden, childProps.thumbnail);

                                        CompletableFuture<Snapshot> chunkUploaded = FileUploader.uploadChunk(version, committer, child.signingPair(),
                                                newProps, getLocation(), us.getParentKey(), baseKey, located,
                                                nextChunkLocation, writerLink, crypto.hasher, network, monitor);

                                        return chunkUploaded.thenCompose(updatedBase -> {
                                            //update indices to be relative to next chunk
                                            long updatedLength = startIndex + internalEnd - internalStart;
                                            if (updatedLength > filesSize.get()) {
                                                filesSize.set(updatedLength);

                                                if (updatedLength > Chunk.MAX_SIZE) {
                                                    // update file size in FileProperties of first chunk
                                                    return network.getFile(updatedBase, childCap, getChildsEntryWriter(), ownername)
                                                            .thenCompose(updatedChild -> {
                                                                FileProperties correctedSize = updatedChild.get()
                                                                        .getPointer().fileAccess.getProperties(childCap.rBaseKey)
                                                                        .withSize(endIndex);
                                                                return updatedChild.get()
                                                                        .getPointer().fileAccess.updateProperties(updatedBase,
                                                                                committer, childCap, entryWriter, correctedSize, network);
                                                            });
                                                }
                                            }
                                            return CompletableFuture.completedFuture(updatedBase);
                                        });
                                    });
                                });
                    };

                    return Futures.reduceAll(startIndexes, base, composer, (a, b) -> b)
                            .thenCompose(updatedBase -> {
                                // update file size
                                if (existingProps.size >= endIndex)
                                    return CompletableFuture.completedFuture(updatedBase);
                                WritableAbsoluteCapability cap = child.writableFilePointer();
                                FileProperties newProps = existingProps.withSize(endIndex);
                                return network.getFile(updatedBase, cap, getChildsEntryWriter(), ownername)
                                        .thenCompose(updatedChild -> updatedChild.get()
                                                .getPointer().fileAccess.updateProperties(updatedBase, committer, cap,
                                                        getChildsEntryWriter(), newProps, network));
                            });
                });
    }

    static boolean isLegalName(String name) {
        return !name.contains("/");
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

        CompletableFuture<FileWrapper> result = new CompletableFuture<>();
        if (!this.isDirectory()) {
            result.completeExceptionally(new IllegalStateException("Cannot mkdir in a file!"));
            return result;
        }
        if (!isLegalName(newFolderName)) {
            result.completeExceptionally(new IllegalStateException("Illegal directory name: " + newFolderName));
            return result;
        }
        return network.synchronizer.applyComplexUpdate(owner(), signingPair(),
                (state, committer) -> hasChildWithName(state, newFolderName, network).thenCompose(hasChild -> {
                    if (hasChild) {
                        CompletableFuture<Snapshot> error = new CompletableFuture<>();
                        error.completeExceptionally(new IllegalStateException("Child already exists with name: " + newFolderName));
                        return error;
                    }
                    return pointer.fileAccess.mkdir(state, committer, newFolderName, network, writableFilePointer(), getChildsEntryWriter(),
                            requestedBaseSymmetricKey, isSystemFolder, crypto).thenApply(x -> {
                        setModified();
                        return x;
                    });
                })).thenCompose(version -> getUpdated(version, network));
    }

    @JsMethod
    public CompletableFuture<FileWrapper> rename(String newFilename,
                                                 FileWrapper parent,
                                                 UserContext context) {
        return rename(newFilename, parent, false, context);
    }

    /**
     * @param newFilename
     * @param parent
     * @param overwrite
     * @param userContext
     * @return the updated parent
     */
    public CompletableFuture<FileWrapper> rename(String newFilename,
                                                 FileWrapper parent,
                                                 boolean overwrite,
                                                 UserContext userContext) {
        setModified();
        if (! isLegalName(newFilename))
            return CompletableFuture.completedFuture(parent);
        CompletableFuture<Optional<FileWrapper>> childExists = parent == null ?
                CompletableFuture.completedFuture(Optional.empty()) :
                parent.getDescendentByPath(newFilename, userContext.network);
        return childExists
                .thenCompose(existing -> {
                    if (existing.isPresent() && !overwrite)
                        throw new IllegalStateException("Cannot rename, child already exists with name: " + newFilename);

                    return ((overwrite && existing.isPresent()) ?
                            existing.get().remove(parent, userContext) :
                            CompletableFuture.completedFuture(parent)
                    ).thenCompose(res -> {

                        //get current props
                        AbsoluteCapability relativeCapability = pointer.capability;
                        SymmetricKey baseKey = relativeCapability.rBaseKey;
                        CryptreeNode fileAccess = pointer.fileAccess;

                        boolean isDir = this.isDirectory();
                        SymmetricKey key = isDir ? fileAccess.getParentKey(baseKey) : baseKey;
                        FileProperties currentProps = fileAccess.getProperties(key);

                        FileProperties newProps = new FileProperties(newFilename, isDir, currentProps.mimeType, currentProps.size,
                                currentProps.modified, currentProps.isHidden, currentProps.thumbnail);
                        SigningPrivateKeyAndPublicHash signer = signingPair();
                        return userContext.network.synchronizer.applyComplexUpdate(owner(), signer,
                                (s, committer) -> fileAccess.updateProperties(s, committer, writableFilePointer(),
                                        entryWriter, newProps, userContext.network))
                                .thenApply(newVersion -> res.withVersion(newVersion));
                    });
                });
    }

    public CompletableFuture<Boolean> setProperties(FileProperties updatedProperties,
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
                ).thenCompose(withParent -> parent.get().hasChildWithName(withParent, newName, network))
                        .thenCompose(hasChild -> ! hasChild ?
                                CompletableFuture.completedFuture(true) :
                                parent.get().getChildrenCapabilities(network)
                                        .thenApply(childCaps -> {
                                            if (! childCaps.stream()
                                                    .map(l -> new ByteArrayWrapper(l.getMapKey()))
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
        return pointer.fileAccess.getSigner(pointer.capability.rBaseKey, pointer.capability.wBaseKey.get(), entryWriter);
    }

    @JsMethod
    public CompletableFuture<Boolean> moveTo(FileWrapper target, FileWrapper parent, UserContext context) {
        return copyTo(target, context)
                .thenCompose(fw -> remove(parent, context))
                .thenApply(newAccess -> true);
    }

    @JsMethod
    public CompletableFuture<Boolean> copyTo(FileWrapper target, UserContext context) {
        ensureUnmodified();
        NetworkAccess network = context.network;
        Crypto crypto = context.crypto;
        SafeRandom random = crypto.random;
        if (! target.isDirectory()) {
            return Futures.errored(new IllegalStateException("CopyTo target " + target + " must be a directory"));
        }

        return context.network.synchronizer.applyComplexUpdate(target.owner(), target.signingPair(), (version, committer) -> {
            return target.hasChildWithName(version, getFileProperties().name, network).thenCompose(childExists -> {
                if (childExists) {
                    CompletableFuture<Snapshot> error = new CompletableFuture<>();
                    error.completeExceptionally(new IllegalStateException("CopyTo target " + target + " already has child with name " + getFileProperties().name));
                    return error;
                }
                if (isDirectory()) {
                    byte[] newMapKey = random.randomBytes(32);
                    SymmetricKey newBaseKey = SymmetricKey.random();
                    SymmetricKey newWriterBaseKey = SymmetricKey.random();
                    WritableAbsoluteCapability newCap = new WritableAbsoluteCapability(target.owner(), target.writer(),
                            newMapKey, newBaseKey, newWriterBaseKey);
                    SymmetricKey newParentParentKey = target.getParentKey();
                    return pointer.fileAccess.copyTo(version, committer, pointer.capability, newBaseKey,
                                    target.writableFilePointer(), target.entryWriter, newParentParentKey,
                                    newMapKey, network, crypto)
                            .thenCompose(updatedBase -> {
                                return target.addLinkTo(updatedBase, committer, getName(), newCap, network, crypto);
                            });
                } else {
                    return version.withWriter(owner(), writer(), network).thenCompose(snapshot ->
                            getInputStream(snapshot.get(writer()).props, network, random, x -> {})
                                    .thenCompose(stream -> target.uploadFileSection(snapshot, committer,
                                            getName(), stream, false, 0, getSize(),
                                            Optional.empty(), false, network, crypto, x -> {},
                                            target.generateChildLocations(props.getNumberOfChunks(), random))));
                }
            });
        }).thenApply(newAccess -> true);
    }


    @JsMethod
    public CompletableFuture<Boolean> hasChild(String fileName, NetworkAccess network) {
        if (!isLegalName(fileName)) {
            return Futures.errored(new IllegalArgumentException("Illegal file/directory name: " + fileName));
        }
        return this.hasChildWithName(version, fileName, network).thenApply(childExists -> childExists);
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
                        .thenCompose(newVersion -> copyAllChunks(false, cap, signer, network, newVersion, committer))
                        .thenCompose(copiedVersion -> copiedVersion.withWriter(owner, parent.writer(), network))
                        .thenCompose(withParent -> parent.getPointer().fileAccess
                                .updateChildLink(withParent, committer, parent.writableFilePointer(),
                                        parent.entryWriter,
                                        getPointer(),
                                        newRetrievedCapability, network, hasher))
                        .thenCompose(updatedParentVersion -> deleteAllChunks(cap, signingPair(), tid, network,
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
                                        byte[] nextChunkMapKey = chunk.getNextChunkLocation(currentCap.rBaseKey);
                                        return copyAllChunks(true, currentCap.withMapKey(nextChunkMapKey),
                                                targetSigner, network, updated, committer);
                                    })
                                    .thenCompose(updatedVersion -> {
                                        if (! mOpt.get().isDirectory())
                                            return CompletableFuture.completedFuture(updatedVersion);
                                        return mOpt.get().getDirectChildrenCapabilities(currentCap, network)
                                                .thenCompose(childCaps ->
                                                        Futures.reduceAll(childCaps,
                                                                updatedVersion,
                                                                (v, cap) -> copyAllChunks(true, cap,
                                                                        targetSigner, network, v, committer),
                                                                (x, y) -> y));
                                    });
                        }));
    }

    public static CompletableFuture<Snapshot> deleteAllChunks(WritableAbsoluteCapability currentCap,
                                                              SigningPrivateKeyAndPublicHash signer,
                                                              TransactionId tid,
                                                              NetworkAccess network,
                                                              Snapshot version,
                                                              Committer committer) {
        return network.getMetadata(version.get(currentCap.writer).props, currentCap)
                .thenCompose(mOpt -> {
                    if (! mOpt.isPresent()) {
                        return CompletableFuture.completedFuture(version);
                    }
                    SigningPrivateKeyAndPublicHash ourSigner = mOpt.get()
                            .getSigner(currentCap.rBaseKey, currentCap.wBaseKey.get(), Optional.of(signer));
                    return network.deleteChunk(version, committer, mOpt.get(), currentCap.owner,
                            currentCap.getMapKey(), ourSigner, tid)
                            .thenCompose(deletedVersion -> {
                                CryptreeNode chunk = mOpt.get();
                                byte[] nextChunkMapKey = chunk.getNextChunkLocation(currentCap.rBaseKey);
                                return deleteAllChunks(currentCap.withMapKey(nextChunkMapKey), signer, tid, network,
                                        deletedVersion, committer);
                            })
                            .thenCompose(updatedVersion -> {
                                if (! mOpt.get().isDirectory())
                                    return CompletableFuture.completedFuture(updatedVersion);
                                return mOpt.get().getDirectChildrenCapabilities(currentCap, network).thenCompose(childCaps ->
                                        Futures.reduceAll(childCaps,
                                                updatedVersion,
                                                (v, cap) -> deleteAllChunks((WritableAbsoluteCapability) cap, signer,
                                                        tid, network, v, committer),
                                                (x, y) -> y));
                            })
                            .thenCompose(s -> removeSigningKey(currentCap.writer, signer, currentCap.owner, network, s, committer));
                });
    }

    /**
     * @param parent
     * @param userContext
     * @return updated parent
     */
    @JsMethod
    public CompletableFuture<FileWrapper> remove(FileWrapper parent, UserContext userContext) {
        NetworkAccess network = userContext.network;
        Hasher hasher = userContext.crypto.hasher;
        ensureUnmodified();
        if (! pointer.capability.isWritable())
            return Futures.errored(new IllegalStateException("Cannot delete file without write access to it"));

        boolean writableParent = parent.isWritable();
        return (writableParent ? parent.removeChild(this, network, hasher) : CompletableFuture.completedFuture(parent))
                .thenCompose(updatedParent -> network.synchronizer.applyComplexUpdate(owner(), signingPair(),
                        (version, committer) -> IpfsTransaction.call(owner(),
                        tid -> FileWrapper.deleteAllChunks(writableFilePointer(), writableParent ?
                                parent.signingPair() :
                                signingPair(), tid, network, version, committer), network.dhtClient))
                        .thenApply(b -> {
                            userContext.sharedWithCache.clearSharedWith(pointer.capability);
                            return updatedParent;
                        }));
    }

    public static CompletableFuture<Snapshot> removeSigningKey(PublicKeyHash signerToRemove,
                                                               SigningPrivateKeyAndPublicHash parentSigner,
                                                               PublicKeyHash owner,
                                                               NetworkAccess network,
                                                               Snapshot current,
                                                               Committer committer) {
        if (parentSigner.publicKeyHash.equals(signerToRemove))
            return CompletableFuture.completedFuture(current);

        return network.synchronizer.applyUpdate(owner, parentSigner, (parentWriterData, tid) -> parentWriterData
                .removeOwnedKey(owner, parentSigner, signerToRemove, network.dhtClient));
    }

    public CompletableFuture<? extends AsyncReader> getInputStream(NetworkAccess network,
                                                                   SafeRandom random,
                                                                   ProgressConsumer<Long> monitor) {
        return network.synchronizer.getValue(owner(), writer())
                .thenCompose(state -> getInputStream(state.get(writer()).props, network, random, getFileProperties().size, monitor));
    }

    public CompletableFuture<? extends AsyncReader> getInputStream(WriterData version,
                                                                   NetworkAccess network,
                                                                   SafeRandom random,
                                                                   ProgressConsumer<Long> monitor) {
        return getInputStream(version, network, random, getFileProperties().size, monitor);
    }

    @JsMethod
    public CompletableFuture<? extends AsyncReader> getInputStream(NetworkAccess network,
                                                                   SafeRandom random,
                                                                   int fileSizeHi,
                                                                   int fileSizeLow,
                                                                   ProgressConsumer<Long> monitor) {
        long fileSize = (fileSizeLow & 0xFFFFFFFFL) + ((fileSizeHi & 0xFFFFFFFFL) << 32);
        return network.synchronizer.getValue(owner(), writer())
                .thenCompose(state -> getInputStream(state.get(writer()).props, network, random, fileSize, monitor));
    }

    public CompletableFuture<? extends AsyncReader> getInputStream(NetworkAccess network,
                                                                   SafeRandom random,
                                                                   long fileSize,
                                                                   ProgressConsumer<Long> monitor) {
        return network.synchronizer.getValue(owner(), writer())
                .thenCompose(state -> getInputStream(state.get(writer()).props, network, random, fileSize, monitor));
    }

    public CompletableFuture<? extends AsyncReader> getInputStream(WriterData version,
                                                                   NetworkAccess network,
                                                                   SafeRandom random,
                                                                   long fileSize,
                                                                   ProgressConsumer<Long> monitor) {
        ensureUnmodified();
        if (pointer.fileAccess.isDirectory())
            throw new IllegalStateException("Cannot get input stream for a directory!");
        CryptreeNode fileAccess = pointer.fileAccess;
        return fileAccess.retriever(pointer.capability.rBaseKey)
                        .getFile(version, network, random, pointer.capability, fileSize, fileAccess.committedHash(), monitor);
    }

    private FileRetriever getRetriever() {
        if (pointer.fileAccess.isDirectory())
            throw new IllegalStateException("Cannot get input stream for a directory!");
        return pointer.fileAccess.retriever(pointer.capability.rBaseKey);
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
        return new FileWrapper(Optional.of(root), null, Optional.empty(), null, new Snapshot(new HashMap<>()));
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

    private CompletableFuture<Optional<byte[]>> generateThumbnail(NetworkAccess network, AsyncReader fileData, int fileSize, String filename) {
        CompletableFuture<Optional<byte[]>> fut = new CompletableFuture<>();
        if (fileSize > MimeTypes.HEADER_BYTES_TO_IDENTIFY_MIME_TYPE) {
            getFileType(fileData, filename).thenAccept(mimeType -> {
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
                        } catch(Mp3CoverImage.NoSuchTagException |
                                Mp3CoverImage.UnsupportedTagException |
                                Mp3CoverImage.InvalidDataException e) {
                            fut.complete(Optional.empty());
                        }
                    });
                } else {
                    fut.complete(Optional.empty());
                }
            });
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
