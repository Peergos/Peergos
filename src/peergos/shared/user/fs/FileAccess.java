package peergos.shared.user.fs;

import peergos.shared.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.symmetric.*;
import peergos.shared.user.*;
import peergos.shared.util.*;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;

public class FileAccess implements Cborable {
    protected final SymmetricLink parent2meta;
    protected final byte[] properties;
    protected final FileRetriever retriever;
    protected final SymmetricLocationLink parentLink;

    public FileAccess(SymmetricLink parent2Meta, byte[] fileProperties, FileRetriever fileRetriever,
                      SymmetricLocationLink parentLink) {
        this.parent2meta = parent2Meta;
        this.properties = fileProperties;
        this.retriever = fileRetriever;
        this.parentLink = parentLink;
    }

    @Override
    public CborObject toCbor() {
        return new CborObject.CborList(
                Arrays.asList(
                        parent2meta.toCbor(),
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

    public CompletableFuture<Boolean> rename(FilePointer writableFilePointer, FileProperties newProps, NetworkAccess network) {
        if (!writableFilePointer.isWritable())
            throw new IllegalStateException("Need a writable pointer!");
        SymmetricKey metaKey = this.getMetaKey(writableFilePointer.baseKey);
        byte[] nonce = metaKey.createNonce();
        FileAccess fa = new FileAccess(this.parent2meta, ArrayOps.concat(nonce, metaKey.encrypt(newProps.serialize(), nonce)),
                this.retriever, this.parentLink);
        return network.uploadChunk(fa, writableFilePointer.location, writableFilePointer.signer());
    }

    public CompletableFuture<FileAccess> markDirty(FilePointer writableFilePointer, SymmetricKey newParentKey, NetworkAccess network) {
        // keep the same metakey, just marked as dirty
        SymmetricKey metaKey = this.getMetaKey(writableFilePointer.baseKey).makeDirty();
        SymmetricLink newParentToMeta = SymmetricLink.fromPair(newParentKey, metaKey);
        SymmetricLocationLink newParentLink = SymmetricLocationLink.create(newParentKey,
                parentLink.target(writableFilePointer.baseKey),
                parentLink.targetLocation(writableFilePointer.baseKey));
        FileAccess fa = new FileAccess(newParentToMeta, properties, this.retriever, newParentLink);
        return network.uploadChunk(fa, writableFilePointer.location,
                writableFilePointer.signer())
                .thenApply(x -> fa);
    }

    public boolean isDirty(SymmetricKey baseKey) {
        return getMetaKey(baseKey).isDirty();
    }

    public CompletableFuture<? extends FileAccess> copyTo(SymmetricKey baseKey, SymmetricKey newBaseKey,
                                                          Location newParentLocation, SymmetricKey parentparentKey,
                                                          SigningPrivateKeyAndPublicHash entryWriterKey, byte[] newMapKey,
                                                          NetworkAccess network) {
        if (!Arrays.equals(baseKey.serialize(), newBaseKey.serialize()))
            throw new IllegalStateException("FileAccess clone must have same base key as original!");
        FileProperties props = getFileProperties(baseKey);
        FileAccess fa = FileAccess.create(newBaseKey, isDirectory() ? SymmetricKey.random() : getMetaKey(baseKey), props,
                this.retriever, newParentLocation, parentparentKey);
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

        SymmetricLink parentToMeta = SymmetricLink.fromCbor(value.get(0));
        byte[] properties = ((CborObject.CborByteArray)value.get(1)).value;
        FileRetriever retriever = value.get(2) instanceof CborObject.CborNull ? null : FileRetriever.fromCbor(value.get(2));
        SymmetricLocationLink parentLink = value.get(3) instanceof CborObject.CborNull ? null : SymmetricLocationLink.fromCbor(value.get(3));

        return new FileAccess(parentToMeta, properties, retriever, parentLink);
    }

    public static FileAccess create(SymmetricKey parentKey, SymmetricKey metaKey, FileProperties props,
                                    FileRetriever retriever, Location parentLocation, SymmetricKey parentparentKey) {
        byte[] nonce = metaKey.createNonce();
        return new FileAccess(SymmetricLink.fromPair(parentKey, metaKey),
                ArrayOps.concat(nonce, metaKey.encrypt(props.serialize(), nonce)),
                retriever,
                SymmetricLocationLink.create(parentKey, parentparentKey, parentLocation));
    }
}
