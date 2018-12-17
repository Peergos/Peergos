package peergos.shared.user.fs;

import peergos.shared.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.crypto.random.*;
import peergos.shared.crypto.symmetric.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.merklebtree.*;
import peergos.shared.user.fs.cryptree.*;
import peergos.shared.util.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

/** A DirAccess cryptree node controls read access to a directory.
 *
 * It contains the following distinct keys {base, parent, metadata}
 *
 * The serialized encrypted form stores links from the base key to the other keys. With the base key one can decrypt
 * all the remaining keys. The base key encrypts the links to child directories and files. The parent key encrypts the
 * link to the parent's parent key. The metadata key encrypts the name of the directory.
 *
 */
public class DirAccess implements CryptreeNode {

    public static final int MAX_CHILD_LINKS_PER_BLOB = 500;

    private final MaybeMultihash lastCommittedHash;
    private final int version;
    private final SymmetricLink base2parent, parent2meta;
    private final SymmetricLocationLink parentLink;
    private final List<SymmetricLocationLink> children;
    private final Optional<SymmetricLocationLink> moreFolderContents;
    private final byte[] properties;

    public DirAccess(MaybeMultihash lastCommittedHash,
                     int version,
                     SymmetricLink base2parent,
                     SymmetricLink parent2meta,
                     SymmetricLocationLink parentLink,
                     byte[] properties,
                     List<SymmetricLocationLink> children,
                     Optional<SymmetricLocationLink> moreFolderContents) {
        this.lastCommittedHash = lastCommittedHash;
        this.version = version;
        this.base2parent = base2parent;
        this.parent2meta = parent2meta;
        this.parentLink = parentLink;
        this.properties = properties;
        this.children = Collections.unmodifiableList(children);
        this.moreFolderContents = moreFolderContents;
    }

    public DirAccess withHash(Multihash hash) {
        return new DirAccess(MaybeMultihash.of(hash), version, base2parent, parent2meta, parentLink, properties,
                children, moreFolderContents);
    }

    @Override
    public MaybeMultihash committedHash() {
        return lastCommittedHash;
    }

    @Override
    public boolean isDirectory() {
        return true;
    }

    @Override
    public int getVersion() {
        return version;
    }

    @Override
    public SymmetricKey getMetaKey(SymmetricKey baseKey) {
        return parent2meta.target(baseKey);
    }

    @Override
    public SymmetricLocationLink getParentLink() {
        return parentLink;
    }

    @Override
    public FileProperties getProperties(SymmetricKey baseKey) {
        return FileProperties.decrypt(properties, getMetaKey(baseKey));
    }

    @Override
    public boolean isDirty(SymmetricKey baseKey) {
        return false;
    }

    public DirAccess withNextBlob(Optional<SymmetricLocationLink> moreFolderContents) {
        return new DirAccess(MaybeMultihash.empty(), version, base2parent, parent2meta, parentLink, properties,
                children, moreFolderContents);
    }

    @Override
    public CborObject toCbor() {
        return new CborObject.CborList(Arrays.asList(
                new CborObject.CborLong(getVersionAndType()),
                base2parent.toCbor(),
                parent2meta.toCbor(),
                parentLink == null ? new CborObject.CborNull() : parentLink.toCbor(),
                new CborObject.CborByteArray(properties),
                new CborObject.CborList(children
                        .stream()
                        .map(locLink -> locLink.toCbor())
                        .collect(Collectors.toList())),
                moreFolderContents.isPresent() ? moreFolderContents.get().toCbor() : new CborObject.CborNull()
        ));
    }

    public static DirAccess fromCbor(CborObject cbor, Multihash hash) {
        if (! (cbor instanceof CborObject.CborList))
            throw new IllegalStateException("Incorrect cbor for DirAccess: " + cbor);

        List<? extends Cborable> value = ((CborObject.CborList) cbor).value;

        int index = 0;
        int version = (int) ((CborObject.CborLong) value.get(index++)).value >> 1;
        SymmetricLink subfoldersToParent = SymmetricLink.fromCbor(value.get(index++));
        SymmetricLink parentToMeta = SymmetricLink.fromCbor(value.get(index++));
        Cborable parentLinkCbor = value.get(index++);
        SymmetricLocationLink parentLink = parentLinkCbor instanceof CborObject.CborNull ?
                null :
                SymmetricLocationLink.fromCbor(parentLinkCbor);
        byte[] properties = ((CborObject.CborByteArray)value.get(index++)).value;
        List<SymmetricLocationLink> children = ((CborObject.CborList)value.get(index++)).value
                .stream()
                .map(SymmetricLocationLink::fromCbor)
                .collect(Collectors.toList());

        Cborable linkToNext = value.get(index++);
        Optional<SymmetricLocationLink> moreFolderContents = linkToNext instanceof CborObject.CborNull ?
                Optional.empty() :
                Optional.of(SymmetricLocationLink.fromCbor(linkToNext));
        return new DirAccess(MaybeMultihash.of(hash), version, subfoldersToParent, parentToMeta, parentLink,
                properties, children, moreFolderContents);
    }

    public List<SymmetricLocationLink> getChildren() {
        return Collections.unmodifiableList(children);
    }

    public CompletableFuture<DirAccess> updateProperties(Capability writableCapability, FileProperties newProps, NetworkAccess network) {
        if (! writableCapability.isWritable())
            throw new IllegalStateException("Need a writable pointer!");
        SymmetricKey metaKey;
        SymmetricKey parentKey = base2parent.target(writableCapability.baseKey);
        metaKey = this.getMetaKey(parentKey);
        byte[] metaNonce = metaKey.createNonce();
        DirAccess updated = new DirAccess(lastCommittedHash, version, base2parent,
                parent2meta, parentLink,
                ArrayOps.concat(metaNonce, metaKey.encrypt(newProps.serialize(), metaNonce)),
                children, moreFolderContents
        );
        return network.uploadChunk(updated, writableCapability.location, writableCapability.signer())
                .thenApply(b -> updated);
    }

    public CompletableFuture<DirAccess> addChildAndCommit(Capability targetCAP, SymmetricKey ourSubfolders,
                                                         Capability ourPointer, SigningPrivateKeyAndPublicHash signer,
                                                         NetworkAccess network, SafeRandom random) {
        return addChildrenAndCommit(Arrays.asList(targetCAP), ourSubfolders, ourPointer, signer, network, random);
    }

    public CompletableFuture<DirAccess> addChildrenAndCommit(List<Capability> targetCAPs, SymmetricKey ourSubfolders,
                                                          Capability ourPointer, SigningPrivateKeyAndPublicHash signer,
                                                          NetworkAccess network, SafeRandom random) {
        if (children.size() + targetCAPs.size() > MAX_CHILD_LINKS_PER_BLOB) {
            return getNextMetablob(ourSubfolders, network).thenCompose(nextMetablob -> {
                if (nextMetablob.size() >= 1) {
                    Capability nextPointer = nextMetablob.get(0).capability;
                    DirAccess nextBlob = (DirAccess) nextMetablob.get(0).fileAccess;
                    return nextBlob.addChildrenAndCommit(targetCAPs, ourSubfolders, nextPointer, signer, network, random);
                } else {
                    // first fill this directory, then overflow into a new one
                    int freeSlots = MAX_CHILD_LINKS_PER_BLOB - children.size();
                    List<Capability> addToUs = targetCAPs.subList(0, freeSlots);
                    List<Capability> addToNext = targetCAPs.subList(freeSlots, targetCAPs.size());
                    return addChildrenAndCommit(addToUs, ourSubfolders, ourPointer, signer, network, random)
                            .thenCompose(newUs -> {
                                // create and upload new metadata blob
                                SymmetricKey nextSubfoldersKey = SymmetricKey.random();
                                SymmetricKey ourParentKey = base2parent.target(ourSubfolders);
                                DirAccess next = DirAccess.create(MaybeMultihash.empty(), nextSubfoldersKey, FileProperties.EMPTY,
                                        parentLink.targetLocation(ourParentKey), parentLink.target(ourParentKey), ourParentKey);
                                byte[] nextMapKey = random.randomBytes(32);
                                Location nextLocation = ourPointer.getLocation().withMapKey(nextMapKey);
                                Capability nextPointer = new Capability(nextLocation, Optional.empty(), nextSubfoldersKey);
                                return next.addChildrenAndCommit(addToNext, nextSubfoldersKey, nextPointer, signer, network, random)
                                        .thenCompose(nextBlob -> {
                                            // re-upload us with the link to the next DirAccess
                                            DirAccess withNext = newUs.withNextBlob(Optional.of(
                                                    SymmetricLocationLink.create(ourSubfolders,
                                                            nextSubfoldersKey, nextPointer.getLocation())));
                                            return withNext.commit(ourPointer.getLocation(), signer, network);
                                        });
                            });
                }
            });
        } else {
            ArrayList<SymmetricLocationLink> newFiles = new ArrayList<>(children);
            for (Capability targetCAP : targetCAPs)
                newFiles.add(SymmetricLocationLink.create(ourSubfolders, targetCAP.baseKey, targetCAP.getLocation()));

            return withChildren(newFiles)
                    .commit(ourPointer.getLocation(), signer, network);
        }
    }

    private CompletableFuture<List<RetrievedFilePointer>> getNextMetablob(SymmetricKey subfoldersKey, NetworkAccess network) {
        if (!moreFolderContents.isPresent())
            return CompletableFuture.completedFuture(Collections.emptyList());
        return network.retrieveAllMetadata(Arrays.asList(moreFolderContents.get()), subfoldersKey);
    }

    public CompletableFuture<DirAccess> updateChildLink(Capability ourPointer, RetrievedFilePointer original,
                                                        RetrievedFilePointer modified, SigningPrivateKeyAndPublicHash signer,
                                                        NetworkAccess network, SafeRandom random) {
        return removeChild(original, ourPointer, signer, network)
                .thenCompose(res -> {
                    return res.addChildAndCommit(modified.capability, ourPointer.baseKey, ourPointer, signer, network, random);
                });
    }

    public CompletableFuture<DirAccess> removeChild(RetrievedFilePointer childRetrievedPointer, Capability ourPointer,
                                                  SigningPrivateKeyAndPublicHash signer, NetworkAccess network) {
        List<SymmetricLocationLink> newSubfolders = children.stream().filter(e -> {
            try {
                Location target = e.targetLocation(ourPointer.baseKey);
                boolean keep = true;
                if (Arrays.equals(target.getMapKey(), childRetrievedPointer.capability.location.getMapKey()))
                    if (Arrays.equals(target.writer.serialize(), childRetrievedPointer.capability.location.writer.serialize()))
                        if (Arrays.equals(target.owner.serialize(), childRetrievedPointer.capability.location.owner.serialize()))
                            keep = false;
                return keep;
            } catch (TweetNaCl.InvalidCipherTextException ex) {
                ex.printStackTrace();
                return false;
            } catch (Exception f) {
                return false;
            }
        }).collect(Collectors.toList());
        return this.withChildren(newSubfolders)
                .commit(ourPointer.getLocation(), signer, network);
    }

    // returns [RetrievedFilePointer]
    public CompletableFuture<Set<RetrievedFilePointer>> getChildren(NetworkAccess network, SymmetricKey baseKey) {
        CompletableFuture<List<RetrievedFilePointer>> childrenFuture = network.retrieveAllMetadata(this.children, baseKey);

        CompletableFuture<List<RetrievedFilePointer>> moreChildrenFuture = moreFolderContents.isPresent() ?
                network.retrieveAllMetadata(Arrays.asList(moreFolderContents.get()), baseKey) :
                CompletableFuture.completedFuture(Collections.emptyList());

        return childrenFuture.thenCompose(children -> moreChildrenFuture.thenCompose(moreChildrenSource -> {
                    // this only has one or zero elements
                    Optional<RetrievedFilePointer> any = moreChildrenSource.stream().findAny();
                    CompletableFuture<Set<RetrievedFilePointer>> moreChildren = any
                            .map(d -> ((DirAccess)d.fileAccess).getChildren(network, d.capability.baseKey))
                            .orElse(CompletableFuture.completedFuture(Collections.emptySet()));
                    return moreChildren.thenApply(moreRetrievedChildren -> {
                        Set<RetrievedFilePointer> results = Stream.concat(
                                children.stream(),
                                moreRetrievedChildren.stream())
                                .collect(Collectors.toSet());
                        return results;
                    });
                })
        );
    }

    public CompletableFuture<DirAccess> cleanUnreachableChildren(NetworkAccess network,
                                                               SymmetricKey baseKey,
                                                               Capability ourPointer,
                                                               SigningPrivateKeyAndPublicHash signer) {
        CompletableFuture<List<RetrievedFilePointer>> moreChildrenFuture = moreFolderContents.isPresent() ?
                network.retrieveAllMetadata(Arrays.asList(moreFolderContents.get()), baseKey) :
                CompletableFuture.completedFuture(Collections.emptyList());

        return getChildren(network, baseKey)
                .thenCompose(reachable -> moreChildrenFuture
                                .thenCompose(moreChildrenSource -> {
                                    // this only has one or zero elements
                                    Optional<RetrievedFilePointer> any = moreChildrenSource.stream().findAny();
                                    CompletableFuture<DirAccess> moreChildren = any
                                            .map(d -> ((DirAccess)d.fileAccess)
                                                    .cleanUnreachableChildren(network, d.capability.baseKey, d.capability, signer))
                                            .orElse(CompletableFuture.completedFuture(this));
                                    return moreChildren.thenCompose(moreRetrievedChildren -> {
                                        List<SymmetricLocationLink> reachableChildLinks = children
                                                .stream()
                                                .filter(sym -> reachable.stream()
                                                        .anyMatch(rfp -> rfp.capability.equals(sym.toReadableFilePointer(baseKey))))
                                                .collect(Collectors.toList());

                                        return withChildren(reachableChildLinks)
                                                .commit(ourPointer.getLocation(), signer, network);
                                    });
                                })
                );
    }

    public Set<Location> getChildrenLocations(SymmetricKey baseKey) {
        return children.stream().map(d -> d.targetLocation(baseKey))
                .collect(Collectors.toSet());
    }

    public SymmetricKey getParentKey(SymmetricKey subfoldersKey) {
        return this.base2parent.target(subfoldersKey);
    }

    // returns pointer to new child directory
    public CompletableFuture<Capability> mkdir(String name, NetworkAccess network,
                                               PublicKeyHash ownerPublic,
                                               SigningPrivateKeyAndPublicHash writer,
                                               byte[] ourMapKey,
                                               SymmetricKey baseKey, SymmetricKey optionalBaseKey,
                                               boolean isSystemFolder, SafeRandom random) {
        SymmetricKey dirReadKey = optionalBaseKey != null ? optionalBaseKey : SymmetricKey.random();
        byte[] dirMapKey = new byte[32]; // root will be stored under this in the tree
        random.randombytes(dirMapKey, 0, 32);
        SymmetricKey ourParentKey = this.getParentKey(baseKey);
        Location ourLocation = new Location(ownerPublic, writer.publicKeyHash, ourMapKey);
        DirAccess dir = DirAccess.create(MaybeMultihash.empty(), dirReadKey, new FileProperties(name, "", 0, LocalDateTime.now(),
                isSystemFolder, Optional.empty()), ourLocation, ourParentKey, null);
        Location chunkLocation = new Location(ownerPublic, writer.publicKeyHash, dirMapKey);
        return network.uploadChunk(dir, chunkLocation, writer).thenCompose(resultHash -> {
            Capability ourPointer = new Capability(ownerPublic, writer.publicKeyHash, ourMapKey, baseKey);
            Capability subdirPointer = new Capability(chunkLocation, Optional.empty(), dirReadKey);
            return addChildAndCommit(subdirPointer, baseKey, ourPointer, writer, network, random)
                    .thenApply(modified -> new Capability(ownerPublic, writer.publicKeyHash, dirMapKey, dirReadKey));
        });
    }

    public CompletableFuture<DirAccess> commit(Location ourLocation, SigningPrivateKeyAndPublicHash signer, NetworkAccess network) {
        return network.uploadChunk(this, ourLocation, signer)
                .thenApply(hash -> this.withHash(hash));
    }

    @Override
    public CompletableFuture<DirAccess> copyTo(SymmetricKey baseKey,
                                               SymmetricKey newBaseKey,
                                               Location newParentLocation,
                                               SymmetricKey parentparentKey,
                                               PublicKeyHash newOwner,
                                               SigningPrivateKeyAndPublicHash entryWriterKey,
                                               byte[] newMapKey,
                                               NetworkAccess network,
                                               SafeRandom random) {
        SymmetricKey parentKey = getParentKey(baseKey);
        FileProperties props = getProperties(parentKey);
        DirAccess da = DirAccess.create(MaybeMultihash.empty(), newBaseKey, props, newParentLocation, parentparentKey, parentKey);
        SymmetricKey ourNewParentKey = da.getParentKey(newBaseKey);
        Location ourNewLocation = new Location(newOwner, entryWriterKey.publicKeyHash, newMapKey);

        return this.getChildren(network, baseKey).thenCompose(RFPs -> {
            // upload new metadata blob for each child and re-add child
            CompletableFuture<DirAccess> reduce = RFPs.stream().reduce(CompletableFuture.completedFuture(da), (dirFuture, rfp) -> {
                SymmetricKey newChildBaseKey = rfp.fileAccess.isDirectory() ? SymmetricKey.random() : rfp.capability.baseKey;
                byte[] newChildMapKey = new byte[32];
                random.randombytes(newChildMapKey, 0, 32);
                Location newChildLocation = new Location(newOwner, entryWriterKey.publicKeyHash, newChildMapKey);
                return rfp.fileAccess.copyTo(rfp.capability.baseKey, newChildBaseKey,
                        ourNewLocation, ourNewParentKey, newOwner, entryWriterKey, newChildMapKey, network, random)
                        .thenCompose(newChildFileAccess -> {
                            Capability ourNewPointer = new Capability(ourNewLocation.owner, entryWriterKey.publicKeyHash, newMapKey, newBaseKey);
                            Capability newChildPointer = new Capability(newChildLocation, Optional.empty(), newChildBaseKey);
                            return dirFuture.thenCompose(dirAccess ->
                                    dirAccess.addChildAndCommit(newChildPointer, newBaseKey, ourNewPointer, entryWriterKey, network, random));
                        });
            }, (a, b) -> a.thenCompose(x -> b)); // TODO Think about this combiner function
            return reduce;
        }).thenCompose(finalDir -> finalDir.commit(new Location(newParentLocation.owner, entryWriterKey.publicKeyHash, newMapKey), entryWriterKey, network));
    }

    private DirAccess withChildren(List<SymmetricLocationLink> newChildren) {
        return new DirAccess(lastCommittedHash, version, base2parent, parent2meta, parentLink, properties,
                newChildren, moreFolderContents);
    }

    public static DirAccess create(MaybeMultihash lastCommittedHash, SymmetricKey subfoldersKey, FileProperties metadata, Location parentLocation, SymmetricKey parentParentKey, SymmetricKey parentKey) {
        SymmetricKey metaKey = SymmetricKey.random();
        if (parentKey == null)
            parentKey = SymmetricKey.random();
        byte[] metaNonce = metaKey.createNonce();
        SymmetricLocationLink parentLink = parentLocation == null ? null : SymmetricLocationLink.create(parentKey, parentParentKey, parentLocation);
        return new DirAccess(
                lastCommittedHash,
                CryptreeNode.CURRENT_DIR_VERSION,
                SymmetricLink.fromPair(subfoldersKey, parentKey),
                SymmetricLink.fromPair(parentKey, metaKey),
                parentLink,
                ArrayOps.concat(metaNonce, metaKey.encrypt(metadata.serialize(), metaNonce)),
                new ArrayList<>(),
                Optional.empty()
        );
    }
}
