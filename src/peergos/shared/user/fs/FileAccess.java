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
 * It contains the following distinct keys {base, data}
 * The serialized encrypted form stores links from the base key to the other keys. With the base key one can decrypt
 * all the remaining keys. The base key is also the parent key and the metadata key. The parent key encrypts the link to
 * the parent's parent key. The metadata key encrypts the name, size, thumbnail, modification times and any other
 * properties of the file.
 *
 * The file retriever contains the merkle links to the encrypted file fragments of this file section, an optional
 * erasure coding scheme, nonce and auth for this section as well as an encrypted link to the next section.
 */
public class FileAccess extends CryptreeNode {
    private static final int META_DATA_PADDING_BLOCKSIZE = 16;

    protected final MaybeMultihash lastCommittedHash;
    protected final int version;
    protected final SymmetricLink parent2data;
    protected final PaddedCipherText properties;
    protected final FileRetriever fileRetriever;
    protected final EncryptedCapability parentLink;
    protected final Optional<SymmetricLinkToSigner> writerLink;

    public FileAccess(MaybeMultihash lastCommittedHash,
                      int version,
                      SymmetricLink parent2Data,
                      PaddedCipherText properties,
                      FileRetriever fileRetriever,
                      EncryptedCapability parentLink,
                      Optional<SymmetricLinkToSigner> writerLink) {
        this.lastCommittedHash = lastCommittedHash;
        this.version = version;
        this.parent2data = parent2Data;
        this.properties = properties;
        this.fileRetriever = fileRetriever;
        this.parentLink = parentLink;
        this.writerLink = writerLink;
    }

    @Override
    public FileAccess withHash(Multihash hash) {
        return new FileAccess(MaybeMultihash.of(hash), version, parent2data, properties, fileRetriever, parentLink, writerLink);
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
        return baseKey;
    }

    @Override
    public FileProperties getProperties(SymmetricKey baseKey) {
        return properties.decrypt(getMetaKey(baseKey), FileProperties::fromCbor);
    }

    public SymmetricKey getDataKey(SymmetricKey baseKey) {
        return parent2data.target(baseKey);
    }

    @Override
    public Optional<byte[]> getNextChunkLocation(SymmetricKey rBaseKey) {
        return fileRetriever.getNextMapLabel(getDataKey(rBaseKey));
    }

    public FileRetriever retriever() {
        return fileRetriever;
    }

    @Override
    public FileAccess withWriterLink(SymmetricLinkToSigner newWriterLink) {
        return new FileAccess(lastCommittedHash, version, parent2data,
                properties, fileRetriever, parentLink, Optional.of(newWriterLink));
    }

    public FileAccess withWriterLink(Optional<SymmetricLinkToSigner> newWriterLink) {
        return new FileAccess(lastCommittedHash, version, parent2data,
                properties, fileRetriever, parentLink, newWriterLink);
    }

    @Override
    public FileAccess withParentLink(EncryptedCapability newParentLink) {
        return new FileAccess(lastCommittedHash, version, parent2data, properties, fileRetriever,
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

        PaddedCipherText encryptedProperties = PaddedCipherText.build(metaKey, newProps, META_DATA_PADDING_BLOCKSIZE);
        FileAccess fa = new FileAccess(lastCommittedHash, version, this.parent2data, encryptedProperties,
                this.fileRetriever, this.parentLink, writerLink);
        return IpfsTransaction.call(us.owner, tid ->
                network.uploadChunk(fa, us.owner, us.getMapKey(), getSigner(us.wBaseKey.get(), entryWriter), tid)
                        .thenApply(b -> fa),
                network.dhtClient);
    }

    public CompletableFuture<FileAccess> rotateBaseReadKey(WritableAbsoluteCapability us,
                                                           Optional<SigningPrivateKeyAndPublicHash> entryWriter,
                                                           RelativeCapability toParent,
                                                           SymmetricKey newBaseKey,
                                                           NetworkAccess network) {
        // keep the same data key, just marked as dirty
        SymmetricKey dataKey = this.getDataKey(us.rBaseKey).makeDirty();
        SymmetricLink newParentToData = SymmetricLink.fromPair(newBaseKey, dataKey);

        EncryptedCapability newParentLink = EncryptedCapability.create(newBaseKey, toParent);
        PaddedCipherText newProperties = PaddedCipherText.build(newBaseKey, getProperties(us.rBaseKey), META_DATA_PADDING_BLOCKSIZE);
        FileAccess fa = new FileAccess(committedHash(), version, newParentToData, newProperties,
                this.fileRetriever, newParentLink, writerLink);
        return IpfsTransaction.call(us.owner, tid ->
                network.uploadChunk(fa, us.owner, us.getMapKey(), getSigner(us.wBaseKey.get(), entryWriter), tid)
                        .thenApply(x -> fa),
                network.dhtClient);
    }

    public CompletableFuture<FileAccess> rotateBaseWriteKey(WritableAbsoluteCapability us,
                                                            Optional<SigningPrivateKeyAndPublicHash> entryWriter,
                                                            SymmetricKey newBaseWriteKey,
                                                            NetworkAccess network) {
        if (! writerLink.isPresent())
            return CompletableFuture.completedFuture(this);

        SigningPrivateKeyAndPublicHash signer = writerLink.get().target(us.wBaseKey.get());
        FileAccess fa = this.withWriterLink(SymmetricLinkToSigner.fromPair(newBaseWriteKey, signer));
        return IpfsTransaction.call(us.owner, tid ->
                network.uploadChunk(fa, us.owner, us.getMapKey(), getSigner(us.wBaseKey.get(), entryWriter), tid)
                        .thenApply(x -> fa),
                network.dhtClient);
    }

    @Override
    public boolean isDirty(SymmetricKey baseKey) {
        return getDataKey(baseKey).isDirty();
    }

    public CompletableFuture<FileAccess> cleanAndCommit(WritableAbsoluteCapability cap,
                                                        SigningPrivateKeyAndPublicHash writer,
                                                        Location parentLocation,
                                                        SymmetricKey parentParentKey,
                                                        NetworkAccess network,
                                                        SafeRandom random,
                                                        Fragmenter fragmenter) {
        FileProperties props = getProperties(cap.rBaseKey);
        AbsoluteCapability nextCap = cap.withMapKey(getNextChunkLocation(cap.rBaseKey).get());
        return retriever().getFile(network, random, getDataKey(cap.rBaseKey), props.size, cap.getLocation(), committedHash(), x -> {})
                .thenCompose(data -> {
                    int chunkSize = (int) Math.min(props.size, Chunk.MAX_SIZE);
                    byte[] chunkData = new byte[chunkSize];
                    return data.readIntoArray(chunkData, 0, chunkSize)
                            .thenCompose(read -> {
                                byte[] nonce = cap.rBaseKey.createNonce();
                                byte[] mapKey = cap.getMapKey();
                                Chunk chunk = new Chunk(chunkData, cap.rBaseKey, mapKey, nonce);
                                LocatedChunk locatedChunk = new LocatedChunk(cap.getLocation(), lastCommittedHash, chunk);
                                return FileUploader.uploadChunk(writer, props, parentLocation, parentParentKey, cap.rBaseKey, locatedChunk,
                                        fragmenter, nextCap.getLocation(), writerLink, network, x -> {});
                            });
                }).thenCompose(h -> network.getMetadata(nextCap)
                        .thenCompose(mOpt -> {
                            if (! mOpt.isPresent())
                                return CompletableFuture.completedFuture(null);
                            return ((FileAccess)mOpt.get()).cleanAndCommit(cap.withMapKey(nextCap.getMapKey()),
                                    writer, parentLocation, parentParentKey, network, random, fragmenter);
                        }).thenCompose(x -> network.getMetadata(cap)).thenApply(opt -> (FileAccess) opt.get())
                );
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
        FileAccess fa = FileAccess.create(MaybeMultihash.empty(), newBaseKey,
                isDirectory ? SymmetricKey.random() : getDataKey(us.rBaseKey),
                props, this.fileRetriever, newParentCap.getLocation(), parentparentKey);
        return IpfsTransaction.call(newParentCap.owner,
                tid -> network.uploadChunk(fa, newParentCap.owner, newMapKey, fa.getSigner(newParentCap.wBaseKey.get(), newEntryWriter), tid)
                        .thenApply(b -> fa),
                network.dhtClient);
    }

    @Override
    public CborObject toCbor() {
        SortedMap<String, Cborable> state = new TreeMap<>();
        state.put("v", new CborObject.CborLong(getVersionAndType()));
        state.put("d", parent2data);
        state.put("p", parentLink);
        state.put("s", properties);
        state.put("r", fileRetriever);
        writerLink.ifPresent(w -> state.put("w", w));
        return CborObject.CborMap.build(state);
    }

    public static FileAccess fromCbor(CborObject cbor, Multihash hash) {
        if (! (cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Incorrect cbor for FileAccess: " + cbor);

        CborObject.CborMap m = (CborObject.CborMap) cbor;

        int versionAndType = (int) m.getLong("v");
        SymmetricLink parentToData = SymmetricLink.fromCbor(m.get("d"));
        EncryptedCapability parentLink = EncryptedCapability.fromCbor(m.get("p"));
        PaddedCipherText properties = PaddedCipherText.fromCbor(m.get("s"));
        FileRetriever retriever = FileRetriever.fromCbor(m.get("r"));

        Optional<SymmetricLinkToSigner> writeLink = m.getOptional("w").map(SymmetricLinkToSigner::fromCbor);

        return new FileAccess(MaybeMultihash.of(hash), versionAndType >> 1, parentToData,
                properties, retriever, parentLink, writeLink);
    }

    public static FileAccess create(MaybeMultihash existingHash,
                                    SymmetricKey parentKey,
                                    SymmetricKey dataKey,
                                    FileProperties props,
                                    FileRetriever retriever,
                                    Location parentLocation,
                                    SymmetricKey parentparentKey) {
        return new FileAccess(
                existingHash,
                CryptreeNode.CURRENT_FILE_VERSION,
                SymmetricLink.fromPair(parentKey, dataKey),
                PaddedCipherText.build(parentKey, props, META_DATA_PADDING_BLOCKSIZE),
                retriever,
                EncryptedCapability.create(parentKey, parentparentKey, Optional.empty(), parentLocation.getMapKey()),
                Optional.empty());
    }
}
