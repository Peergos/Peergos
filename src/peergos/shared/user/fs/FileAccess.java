package peergos.shared.user.fs;

import peergos.shared.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.crypto.random.*;
import peergos.shared.crypto.symmetric.*;
import peergos.shared.user.fs.cryptree.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;

public class FileAccess implements CryptreeNode {

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
                        new CborObject.CborLong(getVersionAndType()),
                        parent2meta.toCbor(),
                        parent2data.toCbor(),
                        parentLink == null ? new CborObject.CborNull() : parentLink.toCbor(),
                        new CborObject.CborByteArray(properties),
                        retriever == null ? new CborObject.CborNull() : retriever.toCbor()
                ));
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
    public SymmetricLocationLink getParentLink() {
        return parentLink;
    }

    @Override
    public SymmetricKey getMetaKey(SymmetricKey baseKey) {
        return parent2meta.target(baseKey);
    }

    @Override
    public FileProperties getProperties(SymmetricKey baseKey) {
        return FileProperties.decrypt(properties, getMetaKey(baseKey));
    }

    public SymmetricKey getDataKey(SymmetricKey baseKey) {
        return parent2data.target(baseKey);
    }

    public FileRetriever retriever() {
        return retriever;
    }

    public CompletableFuture<FileAccess> updateProperties(FilePointer writableFilePointer,
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
        return network.uploadChunk(fa, writableFilePointer.location, writableFilePointer.signer())
                .thenApply(b -> fa);
    }

    public CompletableFuture<FileAccess> markDirty(FilePointer writableFilePointer, SymmetricKey newBaseKey, NetworkAccess network) {
        // keep the same metakey and data key, just marked as dirty
        SymmetricKey metaKey = this.getMetaKey(writableFilePointer.baseKey).makeDirty();
        SymmetricLink newParentToMeta = SymmetricLink.fromPair(newBaseKey, metaKey);

        SymmetricKey dataKey = this.getDataKey(writableFilePointer.baseKey).makeDirty();
        SymmetricLink newParentToData = SymmetricLink.fromPair(newBaseKey, dataKey);

        SymmetricLocationLink newParentLink = SymmetricLocationLink.create(newBaseKey,
                parentLink.target(writableFilePointer.baseKey),
                parentLink.targetLocation(writableFilePointer.baseKey));
        FileAccess fa = new FileAccess(version, newParentToMeta, newParentToData, properties, this.retriever, newParentLink);
        return network.uploadChunk(fa, writableFilePointer.location,
                writableFilePointer.signer())
                .thenApply(x -> fa);
    }

    @Override
    public boolean isDirty(SymmetricKey baseKey) {
        return getMetaKey(baseKey).isDirty() || getDataKey(baseKey).isDirty();
    }

    @Override
    public CompletableFuture<? extends FileAccess> copyTo(SymmetricKey baseKey,
                                                          SymmetricKey newBaseKey,
                                                          Location newParentLocation,
                                                          SymmetricKey parentparentKey,
                                                          PublicKeyHash newOwner,
                                                          SigningPrivateKeyAndPublicHash entryWriterKey,
                                                          byte[] newMapKey,
                                                          NetworkAccess network,
                                                          SafeRandom random) {
        FileProperties props = getProperties(baseKey);
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

        int index = 0;
        int versionAndType = (int) ((CborObject.CborLong) value.get(index++)).value;
        SymmetricLink parentToMeta = SymmetricLink.fromCbor(value.get(index++));
        SymmetricLink parentToData = SymmetricLink.fromCbor(value.get(index++));

        CborObject parentLinkCbor = value.get(index++);
        SymmetricLocationLink parentLink = parentLinkCbor instanceof CborObject.CborNull ?
                null :
                SymmetricLocationLink.fromCbor(parentLinkCbor);

        byte[] properties = ((CborObject.CborByteArray)value.get(index++)).value;
        CborObject retrieverCbor = value.get(index++);
        FileRetriever retriever = retrieverCbor instanceof CborObject.CborNull ?
                null :
                FileRetriever.fromCbor(retrieverCbor);


        return new FileAccess(versionAndType >> 1, parentToMeta, parentToData, properties, retriever, parentLink);
    }

    public static FileAccess create(SymmetricKey parentKey, SymmetricKey metaKey, SymmetricKey dataKey, FileProperties props,
                                    FileRetriever retriever, Location parentLocation, SymmetricKey parentparentKey) {
        byte[] nonce = metaKey.createNonce();
        return new FileAccess(CryptreeNode.CURRENT_FILE_VERSION,
                SymmetricLink.fromPair(parentKey, metaKey),
                SymmetricLink.fromPair(parentKey, dataKey),
                ArrayOps.concat(nonce, metaKey.encrypt(props.serialize(), nonce)),
                retriever,
                SymmetricLocationLink.create(parentKey, parentparentKey, parentLocation));
    }
}
