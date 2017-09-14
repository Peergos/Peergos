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
 * It contains the following distinct keys {base, parent, files, metadata}
 * The serialized encrypted form stores links from the base key to the other keys. With the base key one can decrypt
 * all the remaining keys. The base key is also known as the sub folders key as it encrypts the links to child
 * directories. The files key encrypts the links to all the child files. The parent key encrypts the link to the
 * parent's parent key. The metadata key encrypts the name of the directory.
 *
 */
public class DirAccess implements CryptreeNode {

    public static final int MAX_CHILD_LINKS_PER_BLOB = 500;

    private final MaybeMultihash lastCommittedHash;
    private final int version;
    private final SymmetricLink subfolders2files, subfolders2parent, parent2meta;
    private final SymmetricLocationLink parentLink;
    private final byte[] properties;
    private final List<SymmetricLocationLink> subfolders, files;
    private final Optional<SymmetricLocationLink> moreFolderContents;

    public DirAccess(MaybeMultihash lastCommittedHash,
                     int version,
                     SymmetricLink subfolders2files,
                     SymmetricLink subfolders2parent,
                     SymmetricLink parent2meta,
                     SymmetricLocationLink parentLink,
                     byte[] properties,
                     List<SymmetricLocationLink> subfolders,
                     List<SymmetricLocationLink> files,
                     Optional<SymmetricLocationLink> moreFolderContents) {
        this.lastCommittedHash = lastCommittedHash;
        this.version = version;
        this.subfolders2files = subfolders2files;
        this.subfolders2parent = subfolders2parent;
        this.parent2meta = parent2meta;
        this.parentLink = parentLink;
        this.properties = properties;
        this.subfolders = Collections.unmodifiableList(subfolders);
        this.files = Collections.unmodifiableList(files);
        this.moreFolderContents = moreFolderContents;
    }

    public DirAccess withHash(Multihash hash) {
        return new DirAccess(MaybeMultihash.of(hash), version, subfolders2files, subfolders2parent, parent2meta, parentLink, properties,
                subfolders, files, moreFolderContents);
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
        return new DirAccess(MaybeMultihash.empty(), version, subfolders2files, subfolders2parent, parent2meta, parentLink, properties,
                subfolders, files, moreFolderContents);
    }

    @Override
    public CborObject toCbor() {
        return new CborObject.CborList(Arrays.asList(
                new CborObject.CborLong(getVersionAndType()),
                subfolders2parent.toCbor(),
                subfolders2files.toCbor(),
                parent2meta.toCbor(),
                parentLink == null ? new CborObject.CborNull() : parentLink.toCbor(),
                new CborObject.CborByteArray(properties),
                new CborObject.CborList(subfolders
                        .stream()
                        .map(locLink -> locLink.toCbor())
                        .collect(Collectors.toList())),
                new CborObject.CborList(files
                        .stream()
                        .map(locLink -> locLink.toCbor())
                        .collect(Collectors.toList())),
                moreFolderContents.isPresent() ? moreFolderContents.get().toCbor() : new CborObject.CborNull()
        ));
    }

    public static DirAccess fromCbor(CborObject cbor, Multihash hash) {
        if (! (cbor instanceof CborObject.CborList))
            throw new IllegalStateException("Incorrect cbor for DirAccess: " + cbor);

        List<CborObject> value = ((CborObject.CborList) cbor).value;

        int index = 0;
        int version = (int) ((CborObject.CborLong) value.get(index++)).value >> 1;
        SymmetricLink subfoldersToParent = SymmetricLink.fromCbor(value.get(index++));
        SymmetricLink subfoldersToFiles = SymmetricLink.fromCbor(value.get(index++));
        SymmetricLink parentToMeta = SymmetricLink.fromCbor(value.get(index++));
        CborObject parentLinkCbor = value.get(index++);
        SymmetricLocationLink parentLink = parentLinkCbor instanceof CborObject.CborNull ?
                null :
                SymmetricLocationLink.fromCbor(parentLinkCbor);
        byte[] properties = ((CborObject.CborByteArray)value.get(index++)).value;
        List<SymmetricLocationLink> subfolders = ((CborObject.CborList)value.get(index++)).value
                .stream()
                .map(SymmetricLocationLink::fromCbor)
                .collect(Collectors.toList());
        List<SymmetricLocationLink> files = ((CborObject.CborList)value.get(index++)).value
                .stream()
                .map(SymmetricLocationLink::fromCbor)
                .collect(Collectors.toList());
        CborObject linkToNext = value.get(index++);
        Optional<SymmetricLocationLink> moreFolderContents = linkToNext instanceof CborObject.CborNull ?
                Optional.empty() :
                Optional.of(SymmetricLocationLink.fromCbor(linkToNext));
        return new DirAccess(MaybeMultihash.of(hash), version, subfoldersToFiles, subfoldersToParent, parentToMeta, parentLink,
                properties, subfolders, files, moreFolderContents);
    }

    public List<SymmetricLocationLink> getSubfolders() {
        return Collections.unmodifiableList(subfolders);
    }

    public List<SymmetricLocationLink> getFiles() {
        return Collections.unmodifiableList(files);
    }

    public CompletableFuture<DirAccess> updateProperties(FilePointer writableFilePointer, FileProperties newProps, NetworkAccess network) {
        if (!writableFilePointer.isWritable())
            throw new IllegalStateException("Need a writable pointer!");
        SymmetricKey metaKey;
        SymmetricKey parentKey = subfolders2parent.target(writableFilePointer.baseKey);
        metaKey = this.getMetaKey(parentKey);
        byte[] metaNonce = metaKey.createNonce();
        DirAccess updated = new DirAccess(MaybeMultihash.empty(), version, subfolders2files, subfolders2parent,
                parent2meta, parentLink,
                ArrayOps.concat(metaNonce, metaKey.encrypt(newProps.serialize(), metaNonce)),
                subfolders, files, moreFolderContents
        );
        return network.uploadChunk(updated, writableFilePointer.location, writableFilePointer.signer())
                .thenApply(b -> updated);
    }

    public CompletableFuture<DirAccess> addFileAndCommit(FilePointer targetCAP, SymmetricKey ourSubfolders,
                                                         FilePointer ourPointer, SigningPrivateKeyAndPublicHash signer,
                                                         NetworkAccess network, SafeRandom random) {
        return addFilesAndCommit(Arrays.asList(targetCAP), ourSubfolders, ourPointer, signer, network, random);
    }

    public CompletableFuture<DirAccess> addFilesAndCommit(List<FilePointer> targetCAPs, SymmetricKey ourSubfolders,
                                                          FilePointer ourPointer, SigningPrivateKeyAndPublicHash signer,
                                                          NetworkAccess network, SafeRandom random) {
        if (subfolders.size() + files.size() + targetCAPs.size() > MAX_CHILD_LINKS_PER_BLOB) {
            return getNextMetablob(ourSubfolders, network).thenCompose(nextMetablob -> {
                if (nextMetablob.size() >= 1) {
                    FilePointer nextPointer = nextMetablob.get(0).filePointer;
                    DirAccess nextBlob = (DirAccess) nextMetablob.get(0).fileAccess;
                    return nextBlob.addFilesAndCommit(targetCAPs, ourSubfolders, nextPointer, signer, network, random);
                } else {
                    // first fill this directory, then overflow into a new one
                    int freeSlots = MAX_CHILD_LINKS_PER_BLOB - subfolders.size() - files.size();
                    List<FilePointer> addToUs = targetCAPs.subList(0, freeSlots);
                    List<FilePointer> addToNext = targetCAPs.subList(freeSlots, targetCAPs.size());
                    return addFilesAndCommit(addToUs, ourSubfolders, ourPointer, signer, network, random)
                            .thenCompose(newUs -> {
                                // create and upload new metadata blob
                                SymmetricKey nextSubfoldersKey = SymmetricKey.random();
                                SymmetricKey ourParentKey = subfolders2parent.target(ourSubfolders);
                                DirAccess next = DirAccess.create(nextSubfoldersKey, FileProperties.EMPTY,
                                        parentLink.targetLocation(ourParentKey), parentLink.target(ourParentKey), ourParentKey);
                                byte[] nextMapKey = random.randomBytes(32);
                                Location nextLocation = ourPointer.getLocation().withMapKey(nextMapKey);
                                FilePointer nextPointer = new FilePointer(nextLocation, Optional.empty(), nextSubfoldersKey);
                                return next.addFilesAndCommit(addToNext, nextSubfoldersKey, nextPointer, signer, network, random)
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
            SymmetricKey filesKey = this.subfolders2files.target(ourSubfolders);
            ArrayList<SymmetricLocationLink> newFiles = new ArrayList<>(files);
            for (FilePointer targetCAP : targetCAPs)
                newFiles.add(SymmetricLocationLink.create(filesKey, targetCAP.baseKey, targetCAP.getLocation()));

            return withFiles(newFiles)
                    .commit(ourPointer.getLocation(), signer, network);
        }
    }

    public CompletableFuture<DirAccess> addSubdirAndCommit(FilePointer targetCAP, SymmetricKey ourSubfolders,
                                                           FilePointer ourPointer, SigningPrivateKeyAndPublicHash signer,
                                                           NetworkAccess network, SafeRandom random) {
        return addSubdirsAndCommit(Arrays.asList(targetCAP), ourSubfolders, ourPointer, signer, network, random);
    }
    // returns new version of this directory
    public CompletableFuture<DirAccess> addSubdirsAndCommit(List<FilePointer> targetCAPs, SymmetricKey ourSubfolders,
                                                            FilePointer ourPointer, SigningPrivateKeyAndPublicHash signer,
                                                            NetworkAccess network, SafeRandom random) {
        if (subfolders.size() + files.size() + targetCAPs.size() > MAX_CHILD_LINKS_PER_BLOB) {
            return getNextMetablob(ourSubfolders, network).thenCompose(nextMetablob -> {
                if (nextMetablob.size() >= 1) {
                    FilePointer nextPointer = nextMetablob.get(0).filePointer;
                    DirAccess nextBlob = (DirAccess) nextMetablob.get(0).fileAccess;
                    return nextBlob.addSubdirsAndCommit(targetCAPs, nextPointer.baseKey,
                            nextPointer.withWritingKey(ourPointer.location.writer), signer, network, random);
                } else {
                    // first fill this directory, then overflow into a new one
                    int freeSlots = MAX_CHILD_LINKS_PER_BLOB - subfolders.size() - files.size();
                    List<FilePointer> addToUs = targetCAPs.subList(0, freeSlots);
                    List<FilePointer> addToNext = targetCAPs.subList(freeSlots, targetCAPs.size());
                    return addSubdirsAndCommit(addToUs, ourSubfolders, ourPointer, signer, network, random).thenCompose(newUs -> {
                        // create and upload new metadata blob
                        SymmetricKey nextSubfoldersKey = SymmetricKey.random();
                        SymmetricKey ourParentKey = subfolders2parent.target(ourSubfolders);
                        DirAccess next = DirAccess.create(nextSubfoldersKey, FileProperties.EMPTY,
                                parentLink != null ? parentLink.targetLocation(ourParentKey) : null,
                                parentLink != null ? parentLink.target(ourParentKey) : null, ourParentKey);
                        byte[] nextMapKey = random.randomBytes(32);
                        FilePointer nextPointer = new FilePointer(ourPointer.location.withMapKey(nextMapKey), Optional.empty(), nextSubfoldersKey);
                        return next.addSubdirsAndCommit(addToNext, nextSubfoldersKey, nextPointer, signer, network, random)
                                .thenCompose(x -> {
                                    // re-upload us with the link to the next DirAccess
                                    DirAccess withNextBlob = newUs.withNextBlob(Optional.of(
                                            SymmetricLocationLink.create(ourSubfolders,
                                                    nextSubfoldersKey, nextPointer.getLocation())));
                                    return withNextBlob.commit(ourPointer.getLocation(), signer, network);
                                });
                    });
                }
            });
        } else {
            ArrayList<SymmetricLocationLink> newSubfolders = new ArrayList<>(subfolders);
            for (FilePointer targetCAP : targetCAPs)
                newSubfolders.add(SymmetricLocationLink.create(ourSubfolders, targetCAP.baseKey, targetCAP.getLocation()));

            return new DirAccess(lastCommittedHash, version, subfolders2files, subfolders2parent, parent2meta, parentLink, properties,
                    newSubfolders, files, moreFolderContents)
                    .commit(ourPointer.getLocation(), signer, network);
        }
    }

    private CompletableFuture<List<RetrievedFilePointer>> getNextMetablob(SymmetricKey subfoldersKey, NetworkAccess network) {
        if (!moreFolderContents.isPresent())
            return CompletableFuture.completedFuture(Collections.emptyList());
        return network.retrieveAllMetadata(Arrays.asList(moreFolderContents.get()), subfoldersKey);
    }

    public CompletableFuture<DirAccess> updateChildLink(FilePointer ourPointer, RetrievedFilePointer original,
                                                      RetrievedFilePointer modified, SigningPrivateKeyAndPublicHash signer,
                                                      NetworkAccess network, SafeRandom random) {
        return removeChild(original, ourPointer, signer, network)
                .thenCompose(res -> {
                    if (modified.fileAccess.isDirectory())
                        return addSubdirAndCommit(modified.filePointer, ourPointer.baseKey, ourPointer, signer, network, random);
                    else
                        return addFileAndCommit(modified.filePointer, ourPointer.baseKey, ourPointer, signer, network, random);
                });
    }

    public CompletableFuture<DirAccess> removeChild(RetrievedFilePointer childRetrievedPointer, FilePointer ourPointer,
                                                  SigningPrivateKeyAndPublicHash signer, NetworkAccess network) {
        DirAccess updated;
        if (childRetrievedPointer.fileAccess.isDirectory()) {
            List<SymmetricLocationLink> newSubfolders = subfolders.stream().filter(e -> {
                try {
                    Location target = e.targetLocation(ourPointer.baseKey);
                    boolean keep = true;
                    if (Arrays.equals(target.getMapKey(), childRetrievedPointer.filePointer.location.getMapKey()))
                        if (Arrays.equals(target.writer.serialize(), childRetrievedPointer.filePointer.location.writer.serialize()))
                            if (Arrays.equals(target.owner.serialize(), childRetrievedPointer.filePointer.location.owner.serialize()))
                                keep = false;
                    return keep;
                } catch (TweetNaCl.InvalidCipherTextException ex) {
                    ex.printStackTrace();
                    return false;
                } catch (Exception f) {
                    return false;
                }
            }).collect(Collectors.toList());
            updated = this.withSubfolders(newSubfolders);
        } else {
            List<SymmetricLocationLink> newFiles = files.stream().filter(e -> {
                SymmetricKey filesKey = subfolders2files.target(ourPointer.baseKey);
                try {
                    Location target = e.targetLocation(filesKey);
                    boolean keep = true;
                    if (Arrays.equals(target.getMapKey(), childRetrievedPointer.filePointer.location.getMapKey()))
                        if (Arrays.equals(target.writer.serialize(), childRetrievedPointer.filePointer.location.writer.serialize()))
                            if (Arrays.equals(target.owner.serialize(), childRetrievedPointer.filePointer.location.owner.serialize()))
                                keep = false;
                    return keep;
                } catch (TweetNaCl.InvalidCipherTextException ex) {
                    ex.printStackTrace();
                    return false;
                } catch (Exception f) {
                    return false;
                }
            }).collect(Collectors.toList());
            updated = this.withFiles(newFiles);
        }
        return updated.commit(ourPointer.getLocation(), signer, network);
    }

    // returns [RetrievedFilePointer]
    public CompletableFuture<Set<RetrievedFilePointer>> getChildren(NetworkAccess network, SymmetricKey baseKey) {
        CompletableFuture<List<RetrievedFilePointer>> subdirsFuture = network.retrieveAllMetadata(this.subfolders, baseKey);
        CompletableFuture<List<RetrievedFilePointer>> filesFuture = network.retrieveAllMetadata(this.files, this.subfolders2files.target(baseKey));

        CompletableFuture<List<RetrievedFilePointer>> moreChildrenFuture = moreFolderContents.isPresent() ?
                network.retrieveAllMetadata(Arrays.asList(moreFolderContents.get()), baseKey) :
                CompletableFuture.completedFuture(Collections.emptyList());

        return subdirsFuture.thenCompose(subdirs -> filesFuture.thenCompose(files -> moreChildrenFuture.thenCompose(moreChildrenSource -> {
            // this only has one or zero elements
            Optional<RetrievedFilePointer> any = moreChildrenSource.stream().findAny();
            CompletableFuture<Set<RetrievedFilePointer>> moreChildren = any.map(d -> ((DirAccess)d.fileAccess).getChildren(network, d.filePointer.baseKey))
                    .orElse(CompletableFuture.completedFuture(Collections.emptySet()));
            return moreChildren.thenApply(moreRetrievedChildren -> {
                Set<RetrievedFilePointer> results = Stream.concat(
                        Stream.concat(
                                subdirs.stream(),
                                files.stream()),
                        moreRetrievedChildren.stream())
                        .collect(Collectors.toSet());
                return results;
            });
        })
        )
        );
    }

    public CompletableFuture<DirAccess> cleanUnreachableChildren(NetworkAccess network,
                                                               SymmetricKey baseKey,
                                                               FilePointer ourPointer,
                                                               SigningPrivateKeyAndPublicHash signer) {
        CompletableFuture<List<RetrievedFilePointer>> subdirsFuture = network.retrieveAllMetadata(this.subfolders, baseKey);
        CompletableFuture<List<RetrievedFilePointer>> filesFuture = network.retrieveAllMetadata(this.files, this.subfolders2files.target(baseKey));

        CompletableFuture<List<RetrievedFilePointer>> moreChildrenFuture = moreFolderContents.isPresent() ?
                network.retrieveAllMetadata(Arrays.asList(moreFolderContents.get()), baseKey) :
                CompletableFuture.completedFuture(Collections.emptyList());

        return getChildren(network, baseKey)
                .thenCompose(reachable -> subdirsFuture
                        .thenCompose(subdirs -> filesFuture
                                .thenCompose(files -> moreChildrenFuture
                                        .thenCompose(moreChildrenSource -> {
                                            // this only has one or zero elements
                                            Optional<RetrievedFilePointer> any = moreChildrenSource.stream().findAny();
                                            CompletableFuture<DirAccess> moreChildren = any
                                                    .map(d -> ((DirAccess)d.fileAccess)
                                                            .cleanUnreachableChildren(network, d.filePointer.baseKey, d.filePointer, signer))
                                                    .orElse(CompletableFuture.completedFuture(this));
                                            return moreChildren.thenCompose(moreRetrievedChildren -> {
                                                List<SymmetricLocationLink> reachableDirLinks = subfolders
                                                        .stream()
                                                        .filter(sym -> reachable.stream()
                                                                .anyMatch(rfp -> rfp.filePointer.equals(sym.toReadableFilePointer(baseKey))))
                                                        .collect(Collectors.toList());

                                                List<SymmetricLocationLink> reachableFileLinks = this.files
                                                        .stream()
                                                        .filter(sym -> reachable.stream()
                                                                .anyMatch(rfp -> rfp.filePointer.equals(sym.toReadableFilePointer(subfolders2files.target(baseKey)))))
                                                        .collect(Collectors.toList());

                                                return withSubfolders(reachableDirLinks)
                                                        .withFiles(reachableFileLinks)
                                                        .commit(ourPointer.getLocation(), signer, network);
                                            });
                                        })
                                )
                        ));
    }

    public Set<Location> getChildrenLocations(SymmetricKey baseKey) {
        SymmetricKey filesKey = this.subfolders2files.target(baseKey);
        return Stream.concat(subfolders.stream().map(d -> d.targetLocation(baseKey)),
                files.stream().map(f -> f.targetLocation(filesKey)))
                .collect(Collectors.toSet());
    }

    public SymmetricKey getParentKey(SymmetricKey subfoldersKey) {
        return this.subfolders2parent.target(subfoldersKey);
    }

    public SymmetricKey getFilesKey(SymmetricKey subfoldersKey) {
        return this.subfolders2files.target(subfoldersKey);
    }

    // returns pointer to new child directory
    public CompletableFuture<FilePointer> mkdir(String name, NetworkAccess network,
                                                PublicKeyHash ownerPublic,
                                                SigningPrivateKeyAndPublicHash writer,
                                                byte[] ourMapKey,
                                                SymmetricKey baseKey, SymmetricKey optionalBaseKey,
                                                boolean isSystemFolder, SafeRandom random) {
        SymmetricKey dirReadKey = optionalBaseKey != null ? optionalBaseKey : SymmetricKey.random();
        byte[] dirMapKey = new byte[32]; // root will be stored under this in the btree
        random.randombytes(dirMapKey, 0, 32);
        SymmetricKey ourParentKey = this.getParentKey(baseKey);
        Location ourLocation = new Location(ownerPublic, writer.publicKeyHash, ourMapKey);
        DirAccess dir = DirAccess.create(dirReadKey, new FileProperties(name, 0, LocalDateTime.now(),
                isSystemFolder, Optional.empty()), ourLocation, ourParentKey, null);
        Location chunkLocation = new Location(ownerPublic, writer.publicKeyHash, dirMapKey);
        return network.uploadChunk(dir, chunkLocation, writer).thenCompose(resultHash -> {
            FilePointer ourPointer = new FilePointer(ownerPublic, writer.publicKeyHash, ourMapKey, baseKey);
            FilePointer subdirPointer = new FilePointer(chunkLocation, Optional.empty(), dirReadKey);
            return addSubdirAndCommit(subdirPointer, baseKey, ourPointer, writer, network, random)
                    .thenApply(modified -> new FilePointer(ownerPublic, writer.publicKeyHash, dirMapKey, dirReadKey));
        });
    }

    public CompletableFuture<DirAccess> commit(Location ourLocation, SigningPrivateKeyAndPublicHash signer, NetworkAccess network) {
        return network.uploadChunk(this, ourLocation, signer)
                .thenApply(x -> this);
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
        DirAccess da = DirAccess.create(newBaseKey, props, newParentLocation, parentparentKey, parentKey);
        SymmetricKey ourNewParentKey = da.getParentKey(newBaseKey);
        Location ourNewLocation = new Location(newOwner, entryWriterKey.publicKeyHash, newMapKey);

        return this.getChildren(network, baseKey).thenCompose(RFPs -> {
            // upload new metadata blob for each child and re-add child
            CompletableFuture<DirAccess> reduce = RFPs.stream().reduce(CompletableFuture.completedFuture(da), (dirFuture, rfp) -> {
                SymmetricKey newChildBaseKey = rfp.fileAccess.isDirectory() ? SymmetricKey.random() : rfp.filePointer.baseKey;
                byte[] newChildMapKey = new byte[32];
                random.randombytes(newChildMapKey, 0, 32);
                Location newChildLocation = new Location(newOwner, entryWriterKey.publicKeyHash, newChildMapKey);
                return rfp.fileAccess.copyTo(rfp.filePointer.baseKey, newChildBaseKey,
                        ourNewLocation, ourNewParentKey, newOwner, entryWriterKey, newChildMapKey, network, random)
                        .thenCompose(newChildFileAccess -> {
                            FilePointer ourNewPointer = new FilePointer(ourNewLocation.owner, entryWriterKey.publicKeyHash, newMapKey, newBaseKey);
                            FilePointer newChildPointer = new FilePointer(newChildLocation, Optional.empty(), newChildBaseKey);
                            if (newChildFileAccess.isDirectory())
                                return dirFuture.thenCompose(dirAccess ->
                                        dirAccess.addSubdirAndCommit(newChildPointer, newBaseKey, ourNewPointer, entryWriterKey, network, random));
                            else
                                return dirFuture.thenCompose(dirAccess ->
                                        dirAccess.addFileAndCommit(newChildPointer, newBaseKey, ourNewPointer, entryWriterKey, network, random));
                        });
            }, (a, b) -> a.thenCompose(x -> b)); // TODO Think about this combiner function
            return reduce;
        }).thenCompose(finalDir -> finalDir.commit(new Location(newParentLocation.owner, entryWriterKey.publicKeyHash, newMapKey), entryWriterKey, network));
    }

    private DirAccess withSubfolders(List<SymmetricLocationLink> newSubfolders) {
        return new DirAccess(MaybeMultihash.empty(), version, subfolders2files, subfolders2parent, parent2meta, parentLink, properties,
                newSubfolders, files, moreFolderContents);
    }

    private DirAccess withFiles(List<SymmetricLocationLink> newFiles) {
        return new DirAccess(lastCommittedHash, version, subfolders2files, subfolders2parent, parent2meta, parentLink, properties,
                subfolders, newFiles, moreFolderContents);
    }

    public static DirAccess create(SymmetricKey subfoldersKey, FileProperties metadata, Location parentLocation, SymmetricKey parentParentKey, SymmetricKey parentKey) {
        SymmetricKey metaKey = SymmetricKey.random();
        if (parentKey == null)
            parentKey = SymmetricKey.random();
        SymmetricKey filesKey = SymmetricKey.random();
        byte[] metaNonce = metaKey.createNonce();
        SymmetricLocationLink parentLink = parentLocation == null ? null : SymmetricLocationLink.create(parentKey, parentParentKey, parentLocation);
        return new DirAccess(
                MaybeMultihash.empty(),
                CryptreeNode.CURRENT_DIR_VERSION,
                SymmetricLink.fromPair(subfoldersKey, filesKey),
                SymmetricLink.fromPair(subfoldersKey, parentKey),
                SymmetricLink.fromPair(parentKey, metaKey),
                parentLink,
                ArrayOps.concat(metaNonce, metaKey.encrypt(metadata.serialize(), metaNonce)),
                new ArrayList<>(), new ArrayList<>(),
                Optional.empty()
        );
    }
}
