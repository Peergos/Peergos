package peergos.shared.user.fs;

import peergos.shared.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.random.*;
import peergos.shared.crypto.symmetric.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.storage.*;
import peergos.shared.user.fs.cryptree.*;

import java.util.*;
import java.util.concurrent.*;

/** A FileAccess cryptree node controls read access to a section of a file, up to 5 MiB in size.
 *
 * It contains the following distinct keys {base, metadata, data}
 * The serialized encrypted form stores links from the base key to the other keys. With the base key one can decrypt
 * all the remaining keys. The base key is also the parent key. The parent key encrypts the link to the parent's parent
 * key. The metadata key encrypts the name, size, thumbnail, modification times and any other properties of the file.
 *
 * The file retriever contains the merkle links to the encrypted file fragments of this file section, an optional
 * erasure coding scheme, nonce and auth for this section as well as an encrypted link to the next section.
 */
public class FileAccess implements CryptreeNode {
    private static final int META_DATA_PADDING_BLOCKSIZE = 16;

    protected final MaybeMultihash lastCommittedHash;
    protected final int version;
    protected final SymmetricLink parent2meta;
    protected final SymmetricLink parent2data;
    protected final PaddedCipherText properties;
    protected final FileRetriever retriever;
    protected final EncryptedCapability parentLink;
    protected final Optional<SymmetricLinkToSigner> writerLink;

    public FileAccess(MaybeMultihash lastCommittedHash,
                      int version,
                      SymmetricLink parent2Meta,
                      SymmetricLink parent2Data,
                      PaddedCipherText fileProperties,
                      FileRetriever fileRetriever,
                      EncryptedCapability parentLink,
                      Optional<SymmetricLinkToSigner> writerLink) {
        this.lastCommittedHash = lastCommittedHash;
        this.version = version;
        this.parent2meta = parent2Meta;
        this.parent2data = parent2Data;
        this.properties = fileProperties;
        this.retriever = fileRetriever;
        this.parentLink = parentLink;
        this.writerLink = writerLink;
    }

    @Override
    public MaybeMultihash committedHash() {
        return lastCommittedHash;
    }

    @Override
    public boolean isDirectory() {
        return false;
    }

    @Override
    public int getVersion() {
        return version;
    }

    @Override
    public SymmetricKey getParentKey(SymmetricKey baseKey) {
        return baseKey;
    }

    @Override
    public EncryptedCapability getParentLink() {
        return parentLink;
    }

    @Override
    public SymmetricKey getMetaKey(SymmetricKey baseKey) {
        return parent2meta.target(baseKey);
    }

    @Override
    public FileProperties getProperties(SymmetricKey baseKey) {
        return properties.decrypt(getMetaKey(baseKey), FileProperties::fromCbor);
    }

    public SymmetricKey getDataKey(SymmetricKey baseKey) {
        return parent2data.target(baseKey);
    }

    public FileRetriever retriever() {
        return retriever;
    }

    public FileAccess withWriterLink(SymmetricLinkToSigner newWriterLink) {
        return new FileAccess(lastCommittedHash, version, parent2meta, parent2data,
                properties, retriever, parentLink, Optional.of(newWriterLink));
    }

    public FileAccess withParentLink(EncryptedCapability newParentLink) {
        return new FileAccess(MaybeMultihash.empty(), version, parent2meta, parent2data, properties, retriever,
                newParentLink, writerLink);
    }

    @Override
    public Optional<SymmetricLinkToSigner> getWriterLink() {
        return writerLink;
    }

    @Override
    public CompletableFuture<FileAccess> updateProperties(WritableAbsoluteCapability us,
                                                          Optional<SigningPrivateKeyAndPublicHash> entryWriter,
                                                          FileProperties newProps,
                                                          NetworkAccess network) {
        SymmetricKey metaKey = this.getMetaKey(us.rBaseKey);
        boolean isDirty = metaKey.isDirty();
        // if the meta key is dirty then we need to generate a new one to not expose the new metadata
        if (isDirty)
            metaKey = SymmetricKey.random();
        SymmetricLink toMeta = isDirty ?
                SymmetricLink.fromPair(us.rBaseKey, metaKey) :
                this.parent2meta;

        PaddedCipherText encryptedProperties = PaddedCipherText.build(metaKey, newProps, META_DATA_PADDING_BLOCKSIZE);
        FileAccess fa = new FileAccess(lastCommittedHash, version, toMeta, this.parent2data, encryptedProperties,
                this.retriever, this.parentLink, writerLink);
        return Transaction.call(us.owner, tid ->
                network.uploadChunk(fa, us.owner, us.getMapKey(), getSigner(us.wBaseKey.get(), entryWriter), tid)
                        .thenApply(b -> fa),
                network.dhtClient);
    }

    public CompletableFuture<FileAccess> markDirty(WritableAbsoluteCapability us,
                                                   Optional<SigningPrivateKeyAndPublicHash> entryWriter,
                                                   SymmetricKey newBaseKey,
                                                   NetworkAccess network) {
        // keep the same metakey and data key, just marked as dirty
        SymmetricKey metaKey = this.getMetaKey(us.rBaseKey).makeDirty();
        SymmetricLink newParentToMeta = SymmetricLink.fromPair(newBaseKey, metaKey);

        SymmetricKey dataKey = this.getDataKey(us.rBaseKey).makeDirty();
        SymmetricLink newParentToData = SymmetricLink.fromPair(newBaseKey, dataKey);

        EncryptedCapability newParentLink = EncryptedCapability.create(newBaseKey,
                parentLink.toCapability(us.rBaseKey));
        FileAccess fa = new FileAccess(committedHash(), version, newParentToMeta, newParentToData, properties,
                this.retriever, newParentLink, writerLink);
        return Transaction.call(us.owner, tid ->
                network.uploadChunk(fa, us.owner, us.getMapKey(), getSigner(us.wBaseKey.get(), entryWriter), tid)
                        .thenApply(x -> fa),
                network.dhtClient);
    }

    @Override
    public boolean isDirty(SymmetricKey baseKey) {
        return getMetaKey(baseKey).isDirty() || getDataKey(baseKey).isDirty();
    }

    @Override
    public CompletableFuture<? extends FileAccess> copyTo(AbsoluteCapability us,
                                                          SymmetricKey newBaseKey,
                                                          WritableAbsoluteCapability newParentCap,
                                                          Optional<SigningPrivateKeyAndPublicHash> newEntryWriter,
                                                          SymmetricKey parentparentKey,
                                                          byte[] newMapKey,
                                                          NetworkAccess network,
                                                          SafeRandom random) {
        FileProperties props = getProperties(us.rBaseKey);
        boolean isDirectory = isDirectory();
        FileAccess fa = FileAccess.create(MaybeMultihash.empty(), newBaseKey, SymmetricKey.random(),
                isDirectory ? SymmetricKey.random() : getDataKey(us.rBaseKey),
                props, this.retriever, newParentCap.getLocation(), parentparentKey);
        return Transaction.call(newParentCap.owner,
                tid -> network.uploadChunk(fa, newParentCap.owner, newMapKey, fa.getSigner(newParentCap.wBaseKey.get(), newEntryWriter), tid)
                        .thenApply(b -> fa),
                network.dhtClient);
    }

    @Override
    public CborObject toCbor() {
        return new CborObject.CborList(
                Arrays.asList(
                        new CborObject.CborLong(getVersionAndType()),
                        parent2meta.toCbor(),
                        parent2data.toCbor(),
                        parentLink == null ? new CborObject.CborNull() : parentLink.toCbor(),
                        properties.toCbor(),
                        retriever == null ? new CborObject.CborNull() : retriever.toCbor(),
                        writerLink.isPresent() ? writerLink.get().toCbor() : new CborObject.CborNull()
                ));
    }

    public static FileAccess fromCbor(CborObject cbor, Multihash hash) {
        if (! (cbor instanceof CborObject.CborList))
            throw new IllegalStateException("Incorrect cbor for FileAccess: " + cbor);

        List<? extends Cborable> value = ((CborObject.CborList) cbor).value;

        int index = 0;
        int versionAndType = (int) ((CborObject.CborLong) value.get(index++)).value;
        SymmetricLink parentToMeta = SymmetricLink.fromCbor(value.get(index++));
        SymmetricLink parentToData = SymmetricLink.fromCbor(value.get(index++));

        Cborable parentLinkCbor = value.get(index++);
        EncryptedCapability parentLink = parentLinkCbor instanceof CborObject.CborNull ?
                null :
                EncryptedCapability.fromCbor(parentLinkCbor);

        PaddedCipherText properties = PaddedCipherText.fromCbor(value.get(index++));
        Cborable retrieverCbor = value.get(index++);
        FileRetriever retriever = retrieverCbor instanceof CborObject.CborNull ?
                null :
                FileRetriever.fromCbor(retrieverCbor);

        Cborable writeLinkCbor = value.get(index++);
        Optional<SymmetricLinkToSigner> writeLink = writeLinkCbor instanceof CborObject.CborNull ?
                Optional.empty() :
                Optional.of(writeLinkCbor).map(SymmetricLinkToSigner::fromCbor);

        return new FileAccess(MaybeMultihash.of(hash), versionAndType >> 1, parentToMeta, parentToData,
                properties, retriever, parentLink, writeLink);
    }

    public static FileAccess create(MaybeMultihash existingHash,
                                    SymmetricKey parentKey,
                                    SymmetricKey metaKey,
                                    SymmetricKey dataKey,
                                    FileProperties props,
                                    FileRetriever retriever,
                                    Location parentLocation,
                                    SymmetricKey parentparentKey) {
        return new FileAccess(
                existingHash,
                CryptreeNode.CURRENT_FILE_VERSION,
                SymmetricLink.fromPair(parentKey, metaKey),
                SymmetricLink.fromPair(parentKey, dataKey),
                PaddedCipherText.build(metaKey, props, META_DATA_PADDING_BLOCKSIZE),
                retriever,
                EncryptedCapability.create(parentKey, parentparentKey, Optional.empty(), parentLocation.getMapKey()),
                Optional.empty());
    }
}
