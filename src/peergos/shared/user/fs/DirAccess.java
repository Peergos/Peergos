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

    private static final int CHILDREN_LINKS_PADDING_BLOCKSIZE = 1024;
    private static final int META_DATA_PADDING_BLOCKSIZE = 16;
    private static final int MAX_CHILD_LINKS_PER_BLOB = 500;

    private final MaybeMultihash lastCommittedHash;
    private final int version;
    private final SymmetricLink base2parent, parent2meta;
    private final EncryptedCapability parentLink;
    private final PaddedCipherText children;
    private final Optional<EncryptedCapability> moreFolderContents;
    private final PaddedCipherText properties;

    public DirAccess(MaybeMultihash lastCommittedHash,
                     int version,
                     SymmetricLink base2parent,
                     SymmetricLink parent2meta,
                     EncryptedCapability parentLink,
                     PaddedCipherText properties,
                     PaddedCipherText children,
                     Optional<EncryptedCapability> moreFolderContents) {
        this.lastCommittedHash = lastCommittedHash;
        this.version = version;
        this.base2parent = base2parent;
        this.parent2meta = parent2meta;
        this.parentLink = parentLink;
        this.properties = properties;
        this.children = children;
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
    public EncryptedCapability getParentLink() {
        return parentLink;
    }

    @Override
    public FileProperties getProperties(SymmetricKey baseKey) {
        return properties.decrypt(getMetaKey(baseKey), FileProperties::fromCbor);
    }

    @Override
    public boolean isDirty(SymmetricKey baseKey) {
        return false;
    }

    public DirAccess withNextBlob(Optional<EncryptedCapability> moreFolderContents) {
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
                properties.toCbor(),
                children.toCbor(),
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
        EncryptedCapability parentLink = parentLinkCbor instanceof CborObject.CborNull ?
                null :
                EncryptedCapability.fromCbor(parentLinkCbor);
        PaddedCipherText properties = PaddedCipherText.fromCbor(value.get(index++));
        PaddedCipherText children = PaddedCipherText.fromCbor(value.get(index++));

        Cborable linkToNext = value.get(index++);
        Optional<EncryptedCapability> moreFolderContents = linkToNext instanceof CborObject.CborNull ?
                Optional.empty() :
                Optional.of(EncryptedCapability.fromCbor(linkToNext));
        return new DirAccess(MaybeMultihash.of(hash), version, subfoldersToParent, parentToMeta, parentLink,
                properties, children, moreFolderContents);
    }

    public List<Capability> getChildren(SymmetricKey baseKey) {
        return children.decrypt(baseKey, DirAccess::parseChildLinks);
    }

    private static List<Capability> parseChildLinks(Cborable cbor) {
        if (! (cbor instanceof CborObject.CborList))
            throw new IllegalStateException("Incorrect cbor for DirAccess child links: " + cbor);
        return ((CborObject.CborList) cbor).value
                .stream()
                .map(Capability::fromCbor)
                .collect(Collectors.toList());
    }

    private static PaddedCipherText encryptChildren(SymmetricKey baseKey, List<Capability> children) {
        return PaddedCipherText.build(baseKey,
                new CborObject.CborList(children.stream()
                        .map(Capability::toCbor)
                        .collect(Collectors.toList())), CHILDREN_LINKS_PADDING_BLOCKSIZE);
    }

    public CompletableFuture<DirAccess> updateProperties(Capability writableCapability, FileProperties newProps, NetworkAccess network) {
        if (! writableCapability.isWritable())
            throw new IllegalStateException("Need a writable pointer!");
        SymmetricKey metaKey;
        SymmetricKey parentKey = base2parent.target(writableCapability.baseKey);
        metaKey = this.getMetaKey(parentKey);
        PaddedCipherText encryptedProperties = PaddedCipherText.build(metaKey, newProps, META_DATA_PADDING_BLOCKSIZE);
        DirAccess updated = new DirAccess(lastCommittedHash, version, base2parent,
                parent2meta, parentLink,
                encryptedProperties,
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

    public CompletableFuture<DirAccess> addChildrenAndCommit(List<Capability> targetCAPs, SymmetricKey ourBaseKey,
                                                          Capability ourPointer, SigningPrivateKeyAndPublicHash signer,
                                                          NetworkAccess network, SafeRandom random) {
        List<Capability> children = getChildren(ourBaseKey);
        if (children.size() + targetCAPs.size() > MAX_CHILD_LINKS_PER_BLOB) {
            return getNextMetablob(ourBaseKey, network).thenCompose(nextMetablob -> {
                if (nextMetablob.size() >= 1) {
                    Capability nextPointer = nextMetablob.get(0).capability;
                    DirAccess nextBlob = (DirAccess) nextMetablob.get(0).fileAccess;
                    return nextBlob.addChildrenAndCommit(targetCAPs, ourBaseKey, nextPointer, signer, network, random);
                } else {
                    // first fill this directory, then overflow into a new one
                    int freeSlots = MAX_CHILD_LINKS_PER_BLOB - children.size();
                    List<Capability> addToUs = targetCAPs.subList(0, freeSlots);
                    List<Capability> addToNext = targetCAPs.subList(freeSlots, targetCAPs.size());
                    return addChildrenAndCommit(addToUs, ourBaseKey, ourPointer, signer, network, random)
                            .thenCompose(newUs -> {
                                // create and upload new metadata blob
                                SymmetricKey nextSubfoldersKey = SymmetricKey.random();
                                SymmetricKey ourParentKey = base2parent.target(ourBaseKey);
                                Capability parentCap = parentLink.toCapability(ourParentKey);
                                DirAccess next = DirAccess.create(MaybeMultihash.empty(), nextSubfoldersKey, FileProperties.EMPTY,
                                        parentCap.location, parentCap.baseKey, ourParentKey);
                                byte[] nextMapKey = random.randomBytes(32);
                                Location nextLocation = ourPointer.getLocation().withMapKey(nextMapKey);
                                Capability nextPointer = new Capability(nextLocation, Optional.empty(), nextSubfoldersKey);
                                return next.addChildrenAndCommit(addToNext, nextSubfoldersKey, nextPointer, signer, network, random)
                                        .thenCompose(nextBlob -> {
                                            // re-upload us with the link to the next DirAccess
                                            DirAccess withNext = newUs.withNextBlob(Optional.of(
                                                    EncryptedCapability.create(ourBaseKey,
                                                            nextSubfoldersKey, nextPointer.getLocation())));
                                            return withNext.commit(ourPointer.getLocation(), signer, network);
                                        });
                            });
                }
            });
        } else {
            ArrayList<Capability> newFiles = new ArrayList<>(children);
            newFiles.addAll(targetCAPs);

            return withChildren(encryptChildren(ourBaseKey, newFiles))
                    .commit(ourPointer.getLocation(), signer, network);
        }
    }

    private CompletableFuture<List<RetrievedCapability>> getNextMetablob(SymmetricKey subfoldersKey, NetworkAccess network) {
        if (!moreFolderContents.isPresent())
            return CompletableFuture.completedFuture(Collections.emptyList());
        return network.retrieveAllMetadata(Arrays.asList(moreFolderContents.get().toCapability(subfoldersKey)));
    }

    public CompletableFuture<DirAccess> updateChildLink(Capability ourPointer, RetrievedCapability original,
                                                        RetrievedCapability modified, SigningPrivateKeyAndPublicHash signer,
                                                        NetworkAccess network, SafeRandom random) {
        return removeChild(original, ourPointer, signer, network)
                .thenCompose(res -> {
                    return res.addChildAndCommit(modified.capability, ourPointer.baseKey, ourPointer, signer, network, random);
                });
    }

    public CompletableFuture<DirAccess> removeChild(RetrievedCapability childRetrievedPointer, Capability ourPointer,
                                                    SigningPrivateKeyAndPublicHash signer, NetworkAccess network) {
        List<Capability> newSubfolders = getChildren(ourPointer.baseKey).stream().filter(e -> {
            Location target = e.location;
            boolean keep = true;
            if (Arrays.equals(target.getMapKey(), childRetrievedPointer.capability.location.getMapKey()))
                if (Arrays.equals(target.writer.serialize(), childRetrievedPointer.capability.location.writer.serialize()))
                    if (Arrays.equals(target.owner.serialize(), childRetrievedPointer.capability.location.owner.serialize()))
                        keep = false;
            return keep;
        }).collect(Collectors.toList());
        return this.withChildren(encryptChildren(ourPointer.baseKey, newSubfolders))
                .commit(ourPointer.getLocation(), signer, network);
    }

    // returns [RetrievedCapability]
    public CompletableFuture<Set<RetrievedCapability>> getChildren(NetworkAccess network, SymmetricKey baseKey) {
        CompletableFuture<List<RetrievedCapability>> childrenFuture = network.retrieveAllMetadata(getChildren(baseKey));

        CompletableFuture<List<RetrievedCapability>> moreChildrenFuture = moreFolderContents.isPresent() ?
                network.retrieveAllMetadata(Arrays.asList(moreFolderContents.get().toCapability(baseKey))) :
                CompletableFuture.completedFuture(Collections.emptyList());

        return childrenFuture.thenCompose(children -> moreChildrenFuture.thenCompose(moreChildrenSource -> {
                    // this only has one or zero elements
                    Optional<RetrievedCapability> any = moreChildrenSource.stream().findAny();
                    CompletableFuture<Set<RetrievedCapability>> moreChildren = any
                            .map(d -> ((DirAccess)d.fileAccess).getChildren(network, d.capability.baseKey))
                            .orElse(CompletableFuture.completedFuture(Collections.emptySet()));
                    return moreChildren.thenApply(moreRetrievedChildren -> {
                        Set<RetrievedCapability> results = Stream.concat(
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
        CompletableFuture<List<RetrievedCapability>> moreChildrenFuture = moreFolderContents.isPresent() ?
                network.retrieveAllMetadata(Arrays.asList(moreFolderContents.get().toCapability(baseKey))) :
                CompletableFuture.completedFuture(Collections.emptyList());

        return getChildren(network, baseKey)
                .thenCompose(reachable -> moreChildrenFuture
                        .thenCompose(moreChildrenSource -> {
                            // this only has one or zero elements
                            Optional<RetrievedCapability> any = moreChildrenSource.stream().findAny();
                            CompletableFuture<DirAccess> moreChildren = any
                                    .map(d -> ((DirAccess)d.fileAccess)
                                            .cleanUnreachableChildren(network, d.capability.baseKey, d.capability, signer))
                                    .orElse(CompletableFuture.completedFuture(this));
                            return moreChildren.thenCompose(moreRetrievedChildren -> {
                                List<Capability> reachableChildLinks = getChildren(baseKey)
                                        .stream()
                                        .filter(sym -> reachable.stream()
                                                .anyMatch(rfp -> rfp.capability.equals(sym)))
                                        .collect(Collectors.toList());

                                return withChildren(encryptChildren(baseKey, reachableChildLinks))
                                        .commit(ourPointer.getLocation(), signer, network);
                            });
                        })
                );
    }

    public Set<Location> getChildrenLocations(SymmetricKey baseKey) {
        return getChildren(baseKey).stream()
                .map(cap -> cap.location)
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

    private DirAccess withChildren(PaddedCipherText newChildren) {
        return new DirAccess(lastCommittedHash, version, base2parent, parent2meta, parentLink, properties,
                newChildren, moreFolderContents);
    }

    public static DirAccess create(MaybeMultihash lastCommittedHash, SymmetricKey baseKey, FileProperties props,
                                   Location parentLocation, SymmetricKey parentParentKey, SymmetricKey parentKey) {
        SymmetricKey metaKey = SymmetricKey.random();
        if (parentKey == null)
            parentKey = SymmetricKey.random();
        EncryptedCapability parentLink = parentLocation == null ?
                null :
                EncryptedCapability.create(parentKey, parentParentKey, parentLocation);
        return new DirAccess(
                lastCommittedHash,
                CryptreeNode.CURRENT_DIR_VERSION,
                SymmetricLink.fromPair(baseKey, parentKey),
                SymmetricLink.fromPair(parentKey, metaKey),
                parentLink,
                PaddedCipherText.build(metaKey, props, META_DATA_PADDING_BLOCKSIZE),
                encryptChildren(baseKey, Collections.emptyList()),
                Optional.empty()
        );
    }
}
