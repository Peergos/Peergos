package peergos.shared.user.fs;

import peergos.shared.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.crypto.random.*;
import peergos.shared.crypto.symmetric.*;
import peergos.shared.user.*;
import peergos.shared.util.*;

import java.io.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

public class DirAccess extends FileAccess {

    public static final int MAX_CHILD_LINKS_PER_BLOB = 500;

    private final SymmetricLink subfolders2files, subfolders2parent;
    private List<SymmetricLocationLink> subfolders, files;
    private final Optional<SymmetricLocationLink> moreFolderContents;

    public DirAccess(SymmetricLink subfolders2files, SymmetricLink subfolders2parent, List<SymmetricLocationLink> subfolders,
                     List<SymmetricLocationLink> files,
                     SymmetricLink parent2meta,
                     SymmetricLink parent2data,
                     byte[] properties,
                     FileRetriever retriever, SymmetricLocationLink parentLink, Optional<SymmetricLocationLink> moreFolderContents) {
        super(parent2meta, parent2data, properties, retriever, parentLink);
        this.subfolders2files = subfolders2files;
        this.subfolders2parent = subfolders2parent;
        this.subfolders = subfolders;
        this.files = files;
        this.moreFolderContents = moreFolderContents;
    }

    public DirAccess withNextBlob(Optional<SymmetricLocationLink> moreFolderContents) {
        return new DirAccess(subfolders2files, subfolders2parent, subfolders, files,
                parent2meta, parent2data, properties, retriever, parentLink, moreFolderContents);
    }

    @Override
    public CborObject toCbor() {
        CborObject file = super.toCbor();
        CborObject dirPart = new CborObject.CborList(Arrays.asList(
                subfolders2parent.toCbor(),
                subfolders2files.toCbor(),
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
        return new CborObject.CborList(Arrays.asList(file, dirPart));
    }

    public static DirAccess fromCbor(CborObject cbor, FileAccess base) {
        if (! (cbor instanceof CborObject.CborList))
            throw new IllegalStateException("Incorrect cbor for DirAccess: " + cbor);

        List<CborObject> value = ((CborObject.CborList) cbor).value;
        SymmetricLink subfoldersToParent = SymmetricLink.fromCbor(value.get(0));
        SymmetricLink subfoldersToFiles = SymmetricLink.fromCbor(value.get(1));
        List<SymmetricLocationLink> subfolders = ((CborObject.CborList)value.get(2)).value
                .stream()
                .map(SymmetricLocationLink::fromCbor)
                .collect(Collectors.toList());
        List<SymmetricLocationLink> files = ((CborObject.CborList)value.get(3)).value
                .stream()
                .map(SymmetricLocationLink::fromCbor)
                .collect(Collectors.toList());
        Optional<SymmetricLocationLink> moreFolderContents = value.get(4) instanceof CborObject.CborNull ?
                Optional.empty() : Optional.of(SymmetricLocationLink.fromCbor(value.get(4)));
        return new DirAccess(subfoldersToFiles, subfoldersToParent, subfolders, files,
                base.parent2meta, base.parent2data, base.properties, base.retriever, base.parentLink, moreFolderContents);
    }

    public List<SymmetricLocationLink> getSubfolders() {
        return Collections.unmodifiableList(subfolders);
    }

    public List<SymmetricLocationLink> getFiles() {
        return Collections.unmodifiableList(files);
    }

    public boolean isDirty(SymmetricKey baseKey) {
        throw new IllegalStateException("Unimplemented!");
    }

    public CompletableFuture<Boolean> rename(FilePointer writableFilePointer, FileProperties newProps, NetworkAccess network) {
        if (!writableFilePointer.isWritable())
            throw new IllegalStateException("Need a writable pointer!");
        SymmetricKey metaKey;
        SymmetricKey parentKey = subfolders2parent.target(writableFilePointer.baseKey);
        metaKey = this.getMetaKey(parentKey);
        byte[] metaNonce = metaKey.createNonce();
        DirAccess dira = new DirAccess(this.subfolders2files, this.subfolders2parent,
                this.subfolders, this.files, this.parent2meta, this.parent2data,
                ArrayOps.concat(metaNonce, metaKey.encrypt(newProps.serialize(), metaNonce)),
                null,
                parentLink,
                moreFolderContents
        );
        return network.uploadChunk(dira, writableFilePointer.location, writableFilePointer.signer());
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
            for (FilePointer targetCAP : targetCAPs)
                this.files.add(SymmetricLocationLink.create(filesKey, targetCAP.baseKey, targetCAP.getLocation()));

            return commit(ourPointer.getLocation(), signer, network)
                    .thenApply(x -> this);
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
            for (FilePointer targetCAP : targetCAPs)
                this.subfolders.add(SymmetricLocationLink.create(ourSubfolders, targetCAP.baseKey, targetCAP.getLocation()));

            return commit(ourPointer.getLocation(), signer, network);
        }
    }

    private CompletableFuture<List<RetrievedFilePointer>> getNextMetablob(SymmetricKey subfoldersKey, NetworkAccess network) {
        if (!moreFolderContents.isPresent())
            return CompletableFuture.completedFuture(Collections.emptyList());
        return network.retrieveAllMetadata(Arrays.asList(moreFolderContents.get()), subfoldersKey);
    }

    public CompletableFuture<Boolean> updateChildLink(FilePointer ourPointer, RetrievedFilePointer original,
                                                      RetrievedFilePointer modified, SigningPrivateKeyAndPublicHash signer,
                                                      NetworkAccess network, SafeRandom random) {
        return removeChild(original, ourPointer, signer, network)
                .thenCompose(res -> {
                    if (modified.fileAccess.isDirectory())
                        return addSubdirAndCommit(modified.filePointer, ourPointer.baseKey, ourPointer, signer, network, random);
                    else
                        return addFileAndCommit(modified.filePointer, ourPointer.baseKey, ourPointer, signer, network, random);
                }).thenApply(x -> true);
    }

    public CompletableFuture<Boolean> removeChild(RetrievedFilePointer childRetrievedPointer, FilePointer ourPointer,
                                                  SigningPrivateKeyAndPublicHash signer, NetworkAccess network) {
        if (childRetrievedPointer.fileAccess.isDirectory()) {
            this.subfolders = subfolders.stream().filter(e -> {
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
        } else {
            files = files.stream().filter(e -> {
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
        }
        return network.uploadChunk(this, ourPointer.getLocation(), signer);
    }

    // 0=FILE, 1=DIR
    public byte getType() {
        return 1;
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

    public CompletableFuture<Boolean> cleanUnreachableChildren(NetworkAccess network,
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
                                            CompletableFuture<Boolean> moreChildren = any
                                                    .map(d -> ((DirAccess)d.fileAccess)
                                                            .cleanUnreachableChildren(network, d.filePointer.baseKey, d.filePointer, signer))
                                                    .orElse(CompletableFuture.completedFuture(true));
                                            return moreChildren.thenApply(moreRetrievedChildren -> {
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

                                                this.subfolders = reachableDirLinks;
                                                this.files = reachableFileLinks;

                                                return network.uploadChunk(this, ourPointer.getLocation(), signer);
                                            });
                                        })
                                )
                        )).thenApply(x -> true);
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
        CompletableFuture<FilePointer> result = new CompletableFuture<>();
        Location chunkLocation = new Location(ownerPublic, writer.publicKeyHash, dirMapKey);
        network.uploadChunk(dir, chunkLocation, writer).thenAccept(success -> {
            if (success) {
                FilePointer ourPointer = new FilePointer(ownerPublic, writer.publicKeyHash, ourMapKey, baseKey);
                FilePointer subdirPointer = new FilePointer(chunkLocation, Optional.empty(), dirReadKey);
                addSubdirAndCommit(subdirPointer, baseKey, ourPointer, writer, network, random)
                        .thenAccept(modified -> result.complete(new FilePointer(ownerPublic, writer.publicKeyHash, dirMapKey, dirReadKey)));
            } else
                result.completeExceptionally(new IllegalStateException("Couldn't upload directory metadata!"));
        });
        return result;
    }

    public CompletableFuture<DirAccess> commit(Location ourLocation, SigningPrivateKeyAndPublicHash signer, NetworkAccess network) {
        return network.uploadChunk(this, ourLocation, signer)
                .thenApply(x -> this);
    }

    public CompletableFuture<DirAccess> copyTo(SymmetricKey baseKey, SymmetricKey newBaseKey, Location parentLocation,
                                               SymmetricKey parentparentKey,
                                               PublicKeyHash owner, SigningPrivateKeyAndPublicHash entryWriterKey, byte[] newMapKey,
                                               NetworkAccess network, SafeRandom random) {
        SymmetricKey parentKey = getParentKey(baseKey);
        FileProperties props = getFileProperties(parentKey);
        DirAccess da = DirAccess.create(newBaseKey, props, parentLocation, parentparentKey, parentKey);
        SymmetricKey ourNewParentKey = da.getParentKey(newBaseKey);
        Location ourNewLocation = new Location(owner, entryWriterKey.publicKeyHash, newMapKey);

        return this.getChildren(network, baseKey).thenCompose(RFPs -> {
            // upload new metadata blob for each child and re-add child
            CompletableFuture<DirAccess> reduce = RFPs.stream().reduce(CompletableFuture.completedFuture(da), (dirFuture, rfp) -> {
                SymmetricKey newChildBaseKey = rfp.fileAccess.isDirectory() ? SymmetricKey.random() : rfp.filePointer.baseKey;
                byte[] newChildMapKey = new byte[32];
                random.randombytes(newChildMapKey, 0, 32);
                Location newChildLocation = new Location(owner, entryWriterKey.publicKeyHash, newChildMapKey);
                return rfp.fileAccess.copyTo(rfp.filePointer.baseKey, newChildBaseKey,
                        ourNewLocation, ourNewParentKey, entryWriterKey, newChildMapKey, network)
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
        }).thenCompose(finalDir -> finalDir.commit(new Location(parentLocation.owner, entryWriterKey.publicKeyHash, newMapKey), entryWriterKey, network));
    }

    public static DirAccess create(SymmetricKey subfoldersKey, FileProperties metadata, Location parentLocation, SymmetricKey parentParentKey, SymmetricKey parentKey) {
        SymmetricKey metaKey = SymmetricKey.random();
        if (parentKey == null)
            parentKey = SymmetricKey.random();
        SymmetricKey filesKey = SymmetricKey.random();
        byte[] metaNonce = metaKey.createNonce();
        SymmetricLocationLink parentLink = parentLocation == null ? null : SymmetricLocationLink.create(parentKey, parentParentKey, parentLocation);
        return new DirAccess(SymmetricLink.fromPair(subfoldersKey, filesKey),
                SymmetricLink.fromPair(subfoldersKey, parentKey),
                new ArrayList<>(), new ArrayList<>(),
                SymmetricLink.fromPair(parentKey, metaKey),
                SymmetricLink.fromPair(parentKey, SymmetricKey.createNull()),
                ArrayOps.concat(metaNonce, metaKey.encrypt(metadata.serialize(), metaNonce)),
                null,
                parentLink,
                Optional.empty()
        );
    }
}
