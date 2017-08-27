package peergos.shared.user.fs;

import peergos.shared.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.random.*;
import peergos.shared.crypto.symmetric.*;
import peergos.shared.user.*;
import peergos.shared.util.*;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;

public class FileAccess implements Cborable {
    public static final int CURRENT_VERSION = 1;
    protected final int version;
    protected final SymmetricLink parent2meta;
    protected final SymmetricLink parent2data;
    protected final byte[] properties;
    protected final FileRetriever retriever;
    protected final SymmetricLocationLink parentLink;

    public FileAccess(int version,
                      SymmetricLink parent2Meta,
                      SymmetricLink parent2Data,
                      byte[] fileProperties,
                      FileRetriever fileRetriever,
                      SymmetricLocationLink parentLink) {
        this.version = version;
        this.parent2meta = parent2Meta;
        this.parent2data = parent2Data;
        this.properties = fileProperties;
        this.retriever = fileRetriever;
        this.parentLink = parentLink;
    }

    @Override
    public CborObject toCbor() {
        return new CborObject.CborList(
                Arrays.asList(
                        new CborObject.CborLong(version),
                        parent2meta.toCbor(),
                        parent2data.toCbor(),
                        new CborObject.CborByteArray(properties),
                        retriever == null ? new CborObject.CborNull() : retriever.toCbor(),
                        parentLink == null ? new CborObject.CborNull() : parentLink.toCbor()
                ));
    }

    // 0=FILE, 1=DIR
    public byte getType() {
        return 0;
    }

    public boolean isDirectory() {
        return this.getType() == 1;
    }

    public SymmetricKey getParentKey(SymmetricKey parentKey) {
        return parentKey;
    }

    public SymmetricKey getMetaKey(SymmetricKey parentKey) {
        return parent2meta.target(parentKey);
    }

    public SymmetricKey getDataKey(SymmetricKey parentKey) {
        return parent2data.target(parentKey);
    }

    public FileProperties getFileProperties(SymmetricKey parentKey) {
        try {
            byte[] nonce = Arrays.copyOfRange(properties, 0, TweetNaCl.SECRETBOX_NONCE_BYTES);
            byte[] cipher = Arrays.copyOfRange(properties, TweetNaCl.SECRETBOX_NONCE_BYTES, this.properties.length);
            return FileProperties.deserialize(getMetaKey(parentKey).decrypt(cipher, nonce));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public CompletableFuture<RetrievedFilePointer> getParent(SymmetricKey parentKey, NetworkAccess network) {
        if (this.parentLink == null)
            return CompletableFuture.completedFuture(null);

        return network.retrieveAllMetadata(Arrays.asList(parentLink), parentKey).thenApply(res -> {
            RetrievedFilePointer retrievedFilePointer = res.stream().findAny().get();
            return retrievedFilePointer;
        });
    }

    public FileRetriever retriever() {
        return retriever;
    }

    public CompletableFuture<Boolean> rename(FilePointer writableFilePointer,
                                             FileProperties newProps,
                                             NetworkAccess network) {
        if (!writableFilePointer.isWritable())
            throw new IllegalStateException("Need a writable pointer!");
        SymmetricKey metaKey = this.getMetaKey(writableFilePointer.baseKey);
        boolean isDirty = metaKey.isDirty();
        // if the meta key is dirty then we need to generate a new one to not expose the new metadata
        if (isDirty)
            metaKey = SymmetricKey.random();
        SymmetricLink toMeta = isDirty ?
                SymmetricLink.fromPair(writableFilePointer.baseKey, metaKey) :
                this.parent2meta;

        byte[] nonce = metaKey.createNonce();
        FileAccess fa = new FileAccess(version, toMeta, this.parent2data, ArrayOps.concat(nonce, metaKey.encrypt(newProps.serialize(), nonce)),
                this.retriever, this.parentLink);
        return network.uploadChunk(fa, writableFilePointer.location, writableFilePointer.signer());
    }

    public CompletableFuture<FileAccess> markDirty(FilePointer writableFilePointer, SymmetricKey newParentKey, NetworkAccess network) {
        // keep the same metakey and data key, just marked as dirty
        SymmetricKey metaKey = this.getMetaKey(writableFilePointer.baseKey).makeDirty();
        SymmetricLink newParentToMeta = SymmetricLink.fromPair(newParentKey, metaKey);

        SymmetricKey dataKey = this.getDataKey(writableFilePointer.baseKey).makeDirty();
        SymmetricLink newParentToData = SymmetricLink.fromPair(newParentKey, dataKey);

        SymmetricLocationLink newParentLink = SymmetricLocationLink.create(newParentKey,
                parentLink.target(writableFilePointer.baseKey),
                parentLink.targetLocation(writableFilePointer.baseKey));
        FileAccess fa = new FileAccess(version, newParentToMeta, newParentToData, properties, this.retriever, newParentLink);
        return network.uploadChunk(fa, writableFilePointer.location,
                writableFilePointer.signer())
                .thenApply(x -> fa);
    }

    public boolean isDirty(SymmetricKey baseKey) {
        return getMetaKey(baseKey).isDirty() || getDataKey(baseKey).isDirty();
    }

    public CompletableFuture<? extends FileAccess> copyTo(SymmetricKey baseKey, SymmetricKey newBaseKey,
                                                          Location newParentLocation, SymmetricKey parentparentKey,
                                                          SigningPrivateKeyAndPublicHash entryWriterKey, byte[] newMapKey,
                                                          NetworkAccess network) {
        FileProperties props = getFileProperties(baseKey);
        boolean isDirectory = isDirectory();
        FileAccess fa = FileAccess.create(newBaseKey,
                SymmetricKey.random(),
                isDirectory ? SymmetricKey.random() : getDataKey(baseKey),
                props, this.retriever, newParentLocation, parentparentKey);
        return network.uploadChunk(fa, new Location(newParentLocation.owner, entryWriterKey.publicKeyHash, newMapKey), entryWriterKey)
                .thenApply(b -> fa);
    }

    public static FileAccess fromCbor(CborObject cbor) {
        if (! (cbor instanceof CborObject.CborList))
            throw new IllegalStateException("Incorrect cbor for FileAccess: " + cbor);

        List<CborObject> value = ((CborObject.CborList) cbor).value;
        if (value.size() == 2) {// this is dir
            FileAccess fileBase = fromCbor(value.get(0));
            return DirAccess.fromCbor(value.get(1), fileBase);
        }

        int index = 0;
        int version = (int) ((CborObject.CborLong) value.get(index++)).value;
        SymmetricLink parentToMeta = SymmetricLink.fromCbor(value.get(index++));
        SymmetricLink parentToData = SymmetricLink.fromCbor(value.get(index++));
        byte[] properties = ((CborObject.CborByteArray)value.get(index++)).value;
        CborObject retrieverCbor = value.get(index++);
        FileRetriever retriever = retrieverCbor instanceof CborObject.CborNull ?
                null :
                FileRetriever.fromCbor(retrieverCbor);
        CborObject parentLinkCbor = value.get(index++);
        SymmetricLocationLink parentLink = parentLinkCbor instanceof CborObject.CborNull ?
                null :
                SymmetricLocationLink.fromCbor(parentLinkCbor);

        return new FileAccess(version, parentToMeta, parentToData, properties, retriever, parentLink);
    }

    public static FileAccess create(SymmetricKey parentKey, SymmetricKey metaKey, SymmetricKey dataKey, FileProperties props,
                                    FileRetriever retriever, Location parentLocation, SymmetricKey parentparentKey) {
        byte[] nonce = metaKey.createNonce();
        return new FileAccess(CURRENT_VERSION,
                SymmetricLink.fromPair(parentKey, metaKey),
                SymmetricLink.fromPair(parentKey, dataKey),
                ArrayOps.concat(nonce, metaKey.encrypt(props.serialize(), nonce)),
                retriever,
                SymmetricLocationLink.create(parentKey, parentparentKey, parentLocation));
    }
}
