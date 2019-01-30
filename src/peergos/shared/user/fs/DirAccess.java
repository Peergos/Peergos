package peergos.shared.user.fs;

import peergos.shared.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.random.*;
import peergos.shared.crypto.symmetric.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.storage.*;
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
    private final Optional<SymmetricLinkToSigner> writerLink;

    public DirAccess(MaybeMultihash lastCommittedHash,
                     int version,
                     SymmetricLink base2parent,
                     SymmetricLink parent2meta,
                     EncryptedCapability parentLink,
                     PaddedCipherText properties,
                     PaddedCipherText children,
                     Optional<EncryptedCapability> moreFolderContents,
                     Optional<SymmetricLinkToSigner> writerLink) {
        this.lastCommittedHash = lastCommittedHash;
        this.version = version;
        this.base2parent = base2parent;
        this.parent2meta = parent2meta;
        this.parentLink = parentLink;
        this.properties = properties;
        this.children = children;
        this.moreFolderContents = moreFolderContents;
        this.writerLink = writerLink;
    }

    public DirAccess withHash(Multihash hash) {
        return new DirAccess(MaybeMultihash.of(hash), version, base2parent, parent2meta, parentLink, properties,
                children, moreFolderContents, writerLink);
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

    @Override
    public DirAccess withWriterLink(SymmetricLinkToSigner newWriterLink) {
        return new DirAccess(MaybeMultihash.empty(), version, base2parent, parent2meta, parentLink,
                properties, children, moreFolderContents, Optional.of(newWriterLink));
    }

    @Override
    public DirAccess withParentLink(EncryptedCapability newParentLink) {
        return new DirAccess(MaybeMultihash.empty(), version, base2parent, parent2meta, newParentLink,
                properties, children, moreFolderContents, writerLink);
    }

    public DirAccess withNextBlob(Optional<EncryptedCapability> moreFolderContents) {
        return new DirAccess(MaybeMultihash.empty(), version, base2parent, parent2meta, parentLink, properties,
                children, moreFolderContents, writerLink);
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
                moreFolderContents.isPresent() ? moreFolderContents.get().toCbor() : new CborObject.CborNull(),
                writerLink.isPresent() ? writerLink.get().toCbor() : new CborObject.CborNull()
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

        Cborable writerLinkCbor = value.get(index++);
        Optional<SymmetricLinkToSigner> writerLink = writerLinkCbor instanceof CborObject.CborNull ?
                Optional.empty() :
                Optional.of(SymmetricLinkToSigner.fromCbor(writerLinkCbor));
        return new DirAccess(MaybeMultihash.of(hash), version, subfoldersToParent, parentToMeta, parentLink,
                properties, children, moreFolderContents, writerLink);
    }

    public List<RelativeCapability> getChildren(SymmetricKey baseKey) {
        return children.decrypt(baseKey, DirAccess::parseChildLinks);
    }

    private static List<RelativeCapability> parseChildLinks(Cborable cbor) {
        if (! (cbor instanceof CborObject.CborList))
            throw new IllegalStateException("Incorrect cbor for DirAccess child links: " + cbor);
        return ((CborObject.CborList) cbor).value
                .stream()
                .map(RelativeCapability::fromCbor)
                .collect(Collectors.toList());
    }

    private static PaddedCipherText encryptChildren(SymmetricKey baseKey, List<RelativeCapability> children) {
        return PaddedCipherText.build(baseKey,
                new CborObject.CborList(children.stream()
                        .map(RelativeCapability::toCbor)
                        .collect(Collectors.toList())), CHILDREN_LINKS_PADDING_BLOCKSIZE);
    }

    @Override
    public Optional<SymmetricLinkToSigner> getWriterLink() {
        return writerLink;
    }

    @Override
    public CompletableFuture<DirAccess> updateProperties(WritableAbsoluteCapability us,
                                                         Optional<SigningPrivateKeyAndPublicHash> entryWriter,
                                                         FileProperties newProps,
                                                         NetworkAccess network) {
        SymmetricKey metaKey;
        SymmetricKey parentKey = base2parent.target(us.rBaseKey);
        metaKey = this.getMetaKey(parentKey);
        PaddedCipherText encryptedProperties = PaddedCipherText.build(metaKey, newProps, META_DATA_PADDING_BLOCKSIZE);
        DirAccess updated = new DirAccess(lastCommittedHash, version, base2parent,
                parent2meta, parentLink,
                encryptedProperties,
                children, moreFolderContents, writerLink
        );
        return Transaction.call(us.owner,
                tid -> network.uploadChunk(updated, us.owner, us.getMapKey(), getSigner(us.wBaseKey.get(), entryWriter), tid)
                        .thenApply(b -> updated),
                network.dhtClient);
    }

    public CompletableFuture<DirAccess> addChildAndCommit(RelativeCapability targetCAP,
                                                          WritableAbsoluteCapability us,
                                                          Optional<SigningPrivateKeyAndPublicHash> entryWriter,
                                                          NetworkAccess network,
                                                          SafeRandom random) {
        return addChildrenAndCommit(Arrays.asList(targetCAP), us, entryWriter, network, random);
    }

    public CompletableFuture<DirAccess> addChildrenAndCommit(List<RelativeCapability> targetCAPs,
                                                             WritableAbsoluteCapability us,
                                                             Optional<SigningPrivateKeyAndPublicHash> entryWriter,
                                                             NetworkAccess network,
                                                             SafeRandom random) {
        // Make sure subsequent blobs use a different transaction to obscure linkage of different parts of this dir
        List<RelativeCapability> children = getChildren(us.rBaseKey);
        if (children.size() + targetCAPs.size() > MAX_CHILD_LINKS_PER_BLOB) {
            return getNextMetablob(us, network).thenCompose(nextMetablob -> {
                if (nextMetablob.size() >= 1) {
                    AbsoluteCapability nextPointer = nextMetablob.get(0).capability;
                    DirAccess nextBlob = (DirAccess) nextMetablob.get(0).fileAccess;
                    return nextBlob.addChildrenAndCommit(targetCAPs, nextPointer.toWritable(us.wBaseKey.get()), entryWriter, network, random);
                } else {
                    // first fill this directory, then overflow into a new one
                    int freeSlots = MAX_CHILD_LINKS_PER_BLOB - children.size();
                    List<RelativeCapability> addToUs = targetCAPs.subList(0, freeSlots);
                    List<RelativeCapability> addToNext = targetCAPs.subList(freeSlots, targetCAPs.size());
                    return addChildrenAndCommit(addToUs, us, entryWriter, network, random)
                            .thenCompose(newUs -> {
                                // create and upload new metadata blob
                                SymmetricKey nextSubfoldersKey = SymmetricKey.random();
                                SymmetricKey ourParentKey = base2parent.target(us.rBaseKey);
                                RelativeCapability parentCap = parentLink.toCapability(ourParentKey);
                                DirAccess next = DirAccess.create(MaybeMultihash.empty(), nextSubfoldersKey, null, Optional.empty(), FileProperties.EMPTY,
                                        parentCap, ourParentKey);
                                byte[] nextMapKey = random.randomBytes(32);
                                WritableAbsoluteCapability nextPointer = new WritableAbsoluteCapability(us.owner,
                                        us.writer, nextMapKey, nextSubfoldersKey, us.wBaseKey.get());
                                return next.addChildrenAndCommit(addToNext, nextPointer, entryWriter, network, random)
                                        .thenCompose(nextBlob -> {
                                            // re-upload us with the link to the next DirAccess
                                            DirAccess withNext = newUs.withNextBlob(Optional.of(
                                                    EncryptedCapability.create(us.rBaseKey, us.relativise(nextPointer))));
                                            return Transaction.call(us.owner,
                                                    tid -> withNext.commit(us, entryWriter, network, tid),
                                                    network.dhtClient);
                                        });
                            });
                }
            });
        } else {
            ArrayList<RelativeCapability> newFiles = new ArrayList<>(children);
            newFiles.addAll(targetCAPs);

            return Transaction.call(us.owner,
                    tid -> withChildren(encryptChildren(us.rBaseKey, newFiles))
                            .commit(us, entryWriter, network, tid),
                    network.dhtClient);
        }
    }

    @Override
    public Optional<byte[]> getNextChunkLocation(SymmetricKey rBaseKey) {
        return moreFolderContents.map(c -> c.toCapability(rBaseKey).getMapKey());
    }

    private CompletableFuture<List<RetrievedCapability>> getNextMetablob(AbsoluteCapability us,
                                                                         NetworkAccess network) {
        if (! moreFolderContents.isPresent())
            return CompletableFuture.completedFuture(Collections.emptyList());
        RelativeCapability cap = moreFolderContents.get().toCapability(us.rBaseKey);
        return network.retrieveAllMetadata(Arrays.asList(cap.toAbsolute(us)));
    }

    public CompletableFuture<DirAccess> updateChildLink(WritableAbsoluteCapability ourPointer,
                                                        Optional<SigningPrivateKeyAndPublicHash> entryWriter,
                                                        RetrievedCapability original,
                                                        RetrievedCapability modified,
                                                        NetworkAccess network,
                                                        SafeRandom random) {
        return removeChild(original, ourPointer, entryWriter, network)
                .thenCompose(res ->
                        res.addChildAndCommit(ourPointer.relativise((WritableAbsoluteCapability) modified.capability),
                                ourPointer, entryWriter, network, random));
    }

    public CompletableFuture<DirAccess> removeChild(RetrievedCapability childRetrievedPointer,
                                                    WritableAbsoluteCapability ourPointer,
                                                    Optional<SigningPrivateKeyAndPublicHash> entryWriter,
                                                    NetworkAccess network) {
        List<RelativeCapability> newSubfolders = getChildren(ourPointer.rBaseKey).stream().filter(e -> {
            boolean keep = true;
            if (Arrays.equals(e.getMapKey(), childRetrievedPointer.capability.getMapKey()))
                if (Objects.equals(e.writer.orElse(ourPointer.writer), childRetrievedPointer.capability.writer))
                        keep = false;
            return keep;
        }).collect(Collectors.toList());
        return Transaction.call(ourPointer.owner,
                tid -> withChildren(encryptChildren(ourPointer.rBaseKey, newSubfolders))
                        .commit(ourPointer, entryWriter, network, tid),
                network.dhtClient);
    }

    // returns [RetrievedCapability]
    public CompletableFuture<Set<RetrievedCapability>> getChildren(NetworkAccess network,
                                                                   AbsoluteCapability us) {
        CompletableFuture<List<RetrievedCapability>> childrenFuture =
                network.retrieveAllMetadata(getChildren(us.rBaseKey).stream()
                        .map(c -> c.toAbsolute(us))
                        .collect(Collectors.toList()));

        CompletableFuture<List<RetrievedCapability>> moreChildrenFuture = moreFolderContents
                .map(moreCap -> {
                    RelativeCapability cap = moreCap.toCapability(us.rBaseKey);
                    return network.retrieveAllMetadata(Arrays.asList(cap.toAbsolute(us)));
                })
                .orElse(CompletableFuture.completedFuture(Collections.emptyList()));

        return childrenFuture.thenCompose(children -> moreChildrenFuture.thenCompose(moreChildrenSource -> {
                    // this only has one or zero elements
                    Optional<RetrievedCapability> any = moreChildrenSource.stream().findAny();
                    CompletableFuture<Set<RetrievedCapability>> moreChildren = any
                            .map(d -> ((DirAccess)d.fileAccess).getChildren(network, d.capability))
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

    public Set<Location> getChildrenLocations(AbsoluteCapability us) {
        return getChildren(us.rBaseKey).stream()
                .map(cap -> cap.getLocation(us.owner, us.writer))
                .collect(Collectors.toSet());
    }

    @Override
    public Set<AbsoluteCapability> getChildrenCapabilities(AbsoluteCapability us) {
        return getChildren(us.rBaseKey).stream()
                .map(cap -> cap.toAbsolute(us))
                .collect(Collectors.toSet());
    }

    public SymmetricKey getParentKey(SymmetricKey subfoldersKey) {
        return this.base2parent.target(subfoldersKey);
    }

    // returns pointer to new child directory
    public CompletableFuture<RelativeCapability> mkdir(String name,
                                                       NetworkAccess network,
                                                       WritableAbsoluteCapability us,
                                                       Optional<SigningPrivateKeyAndPublicHash> entryWriter,
                                                       SymmetricKey optionalBaseKey,
                                                       boolean isSystemFolder,
                                                       SafeRandom random) {
        SymmetricKey dirReadKey = optionalBaseKey != null ? optionalBaseKey : SymmetricKey.random();
        SymmetricKey dirWriteKey = SymmetricKey.random();
        byte[] dirMapKey = random.randomBytes(32); // root will be stored under this in the tree
        SymmetricKey ourParentKey = this.getParentKey(us.rBaseKey);
        RelativeCapability ourCap = new RelativeCapability(us.getMapKey(), ourParentKey, null);
        DirAccess child = DirAccess.create(MaybeMultihash.empty(), dirReadKey, dirWriteKey, Optional.empty(),
                new FileProperties(name, "", 0, LocalDateTime.now(), isSystemFolder,
                        Optional.empty()), ourCap, null);

        SymmetricLink toChildWriteKey = SymmetricLink.fromPair(us.wBaseKey.get(), dirWriteKey);
        // Use two transactions to not expose the child linkage
        return Transaction.call(us.owner,
                tid -> network.uploadChunk(child, us.owner, dirMapKey, getSigner(us.wBaseKey.get(), entryWriter), tid), network.dhtClient)
                .thenCompose(resultHash -> {
                    RelativeCapability subdirPointer = new RelativeCapability(dirMapKey, dirReadKey, toChildWriteKey);
                    return addChildAndCommit(subdirPointer, us, entryWriter, network, random)
                            .thenApply(modified -> new RelativeCapability(dirMapKey, dirReadKey, toChildWriteKey));
                });
    }

    public CompletableFuture<DirAccess> commit(WritableAbsoluteCapability us,
                                               Optional<SigningPrivateKeyAndPublicHash> entryWriter,
                                               NetworkAccess network,
                                               TransactionId tid) {
        return network.uploadChunk(this, us.owner, us.getMapKey(), getSigner(us.wBaseKey.get(), entryWriter), tid)
                .thenApply(this::withHash);
    }

    @Override
    public CompletableFuture<DirAccess> copyTo(AbsoluteCapability us,
                                               SymmetricKey newReadBaseKey,
                                               WritableAbsoluteCapability newParentCap,
                                               Optional<SigningPrivateKeyAndPublicHash> newEntryWriter,
                                               SymmetricKey parentparentKey,
                                               byte[] newMapKey,
                                               NetworkAccess network,
                                               SafeRandom random) {
        SymmetricKey newWriteBaseKey = SymmetricKey.random();
        SymmetricKey parentKey = getParentKey(us.rBaseKey);
        FileProperties props = getProperties(parentKey);
        Optional<SigningPrivateKeyAndPublicHash> newSigner = Optional.empty();
        DirAccess da = DirAccess.create(MaybeMultihash.empty(), newReadBaseKey, newWriteBaseKey, newSigner, props,
                new RelativeCapability(Optional.empty(), newParentCap.getMapKey(), parentparentKey, Optional.empty()), parentKey);
        SymmetricKey ourNewParentKey = da.getParentKey(newReadBaseKey);
        WritableAbsoluteCapability ourNewCap = new WritableAbsoluteCapability(newParentCap.owner, newParentCap.writer, newMapKey, newReadBaseKey, newWriteBaseKey);

        return this.getChildren(network, us).thenCompose(RFPs -> {
            // upload new metadata blob for each child and re-add child
            CompletableFuture<DirAccess> reduce = RFPs.stream().reduce(CompletableFuture.completedFuture(da), (dirFuture, rfp) -> {
                SymmetricKey newChildReadKey = rfp.fileAccess.isDirectory() ? SymmetricKey.random() : rfp.capability.rBaseKey;
                SymmetricKey newChildWriteKey = SymmetricKey.random();
                byte[] newChildMapKey = random.randomBytes(32);
                WritableAbsoluteCapability newChildCap = new WritableAbsoluteCapability(ourNewCap.owner,
                        ourNewCap.writer, newChildMapKey, newChildReadKey, newChildWriteKey);
                return rfp.fileAccess.copyTo(rfp.capability, newChildReadKey,
                        ourNewCap, newEntryWriter, ourNewParentKey, newChildMapKey, network, random)
                        .thenCompose(newChildFileAccess -> {
                            return dirFuture.thenCompose(dirAccess ->
                                    dirAccess.addChildAndCommit(ourNewCap.relativise(newChildCap), ourNewCap, newEntryWriter, network, random));
                        });
            }, (a, b) -> a.thenCompose(x -> b)); // TODO Think about this combiner function
            return reduce;
        }).thenCompose(finalDir -> Transaction.call(newParentCap.owner,
                tid -> finalDir.commit(new WritableAbsoluteCapability(newParentCap.owner, newParentCap.writer, newMapKey,
                        newReadBaseKey, newWriteBaseKey), newEntryWriter, network, tid),
                network.dhtClient));
    }

    private DirAccess withChildren(PaddedCipherText newChildren) {
        return new DirAccess(lastCommittedHash, version, base2parent, parent2meta, parentLink, properties,
                newChildren, moreFolderContents, writerLink);
    }

    public static DirAccess create(MaybeMultihash lastCommittedHash,
                                   SymmetricKey rBaseKey,
                                   SymmetricKey wBaseKey,
                                   Optional<SigningPrivateKeyAndPublicHash> signingPair,
                                   FileProperties props,
                                   RelativeCapability parentCap,
                                   SymmetricKey parentKey) {
        SymmetricKey metaKey = SymmetricKey.random();
        if (parentKey == null)
            parentKey = SymmetricKey.random();
        EncryptedCapability parentLink = parentCap == null ?
                null :
                EncryptedCapability.create(parentKey, parentCap);
        Optional<SymmetricLinkToSigner> writerLink = signingPair.map(pair -> SymmetricLinkToSigner.fromPair(wBaseKey, pair));
        return new DirAccess(
                lastCommittedHash,
                CryptreeNode.CURRENT_DIR_VERSION,
                SymmetricLink.fromPair(rBaseKey, parentKey),
                SymmetricLink.fromPair(parentKey, metaKey),
                parentLink,
                PaddedCipherText.build(metaKey, props, META_DATA_PADDING_BLOCKSIZE),
                encryptChildren(rBaseKey, Collections.emptyList()),
                Optional.empty(),
                writerLink
        );
    }
}
