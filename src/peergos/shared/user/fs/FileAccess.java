package peergos.shared.user.fs;

import peergos.shared.crypto.*;
import peergos.shared.crypto.symmetric.*;
import peergos.shared.user.*;
import peergos.shared.util.*;

import java.io.*;
import java.util.*;

public class FileAccess {
    protected final SymmetricLink parent2meta;
    protected final byte[] properties;
    protected final FileRetriever retriever;
    protected final SymmetricLocationLink parentLink;

    public FileAccess(SymmetricLink parent2Meta, byte[] fileProperties, FileRetriever fileRetriever, SymmetricLocationLink parentLink) {
        this.parent2meta = parent2Meta;
        this.properties = fileProperties;
        this.retriever = fileRetriever;
        this.parentLink = parentLink;
    }

    public void serialize(DataSink bout) throws IOException {
        bout.writeArray(parent2meta.serialize());
        bout.writeArray(properties);
        bout.writeByte(retriever != null ? 1 : 0);
        if (retriever != null)
            retriever.serialize(bout);
        bout.writeByte(parentLink != null ? 1: 0);
        if (parentLink != null)
            bout.writeArray(this.parentLink.serialize());
        bout.writeByte(this.getType());
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

    public boolean removeFragments(UserContext context) {
        return true;
    }

    public FileProperties getFileProperties(SymmetricKey parentKey) throws IOException {
        byte[] nonce = Arrays.copyOfRange(properties, 0, TweetNaCl.SECRETBOX_NONCE_BYTES);
        byte[] cipher = Arrays.copyOfRange(properties, TweetNaCl.SECRETBOX_NONCE_BYTES, this.properties.length);
        return FileProperties.deserialize(getMetaKey(parentKey).decrypt(cipher, nonce));
    }

    public RetrievedFilePointer getParent(SymmetricKey parentKey, UserContext context) throws IOException {
        if (this.parentLink == null)
            return null;
        Map<ReadableFilePointer, FileAccess> res = context.retrieveAllMetadata(Arrays.asList(parentLink), parentKey);
        RetrievedFilePointer retrievedFilePointer = res.entrySet().stream()
                .map(entry -> new RetrievedFilePointer(entry.getKey(),  entry.getValue())).findAny().get();
        return retrievedFilePointer;
    }

    public FileRetriever retriever() {
        return retriever;
    }

    public boolean rename(ReadableFilePointer writableFilePointer, FileProperties newProps, UserContext context) throws IOException {
        if (!writableFilePointer.isWritable())
            throw new IllegalStateException("Need a writable pointer!");
        SymmetricKey metaKey = this.getMetaKey(writableFilePointer.baseKey);
        byte[] nonce = metaKey.createNonce();
        FileAccess fa = new FileAccess(this.parent2meta, ArrayOps.concat(nonce, metaKey.encrypt(newProps.serialize(), nonce)), this.retriever, this.parentLink);
        return context.uploadChunk(fa, writableFilePointer.owner, (User) writableFilePointer.writer,
                writableFilePointer.mapKey, Collections.emptyList()); //TODO get fragment hashes from retriever
    }

    public FileAccess markDirty(ReadableFilePointer writableFilePointer, SymmetricKey newParentKey, UserContext context) throws IOException {
        // keep the same metakey, just marked as dirty
        SymmetricKey metaKey = this.getMetaKey(writableFilePointer.baseKey).makeDirty();
        SymmetricLink newParentToMeta = SymmetricLink.fromPair(newParentKey, metaKey);
        SymmetricLocationLink newParentLink = SymmetricLocationLink.create(newParentKey,
                parentLink.target(writableFilePointer.baseKey),
                parentLink.targetLocation(writableFilePointer.baseKey));
        FileAccess fa = new FileAccess(newParentToMeta, properties, this.retriever, newParentLink);
        context.uploadChunk(fa, writableFilePointer.owner, (User) writableFilePointer.writer,
                writableFilePointer.mapKey, Collections.emptyList()); //TODO get fragment hashes from retriever
        return fa;
    }

    public boolean isDirty(SymmetricKey baseKey) {
        return getMetaKey(baseKey).isDirty();
    }

    public FileAccess copyTo(SymmetricKey baseKey, SymmetricKey newBaseKey, Location parentLocation, SymmetricKey parentparentKey,
                  User entryWriterKey, byte[] newMapKey, UserContext context) throws IOException {
        if (!Arrays.equals(baseKey.serialize(), newBaseKey.serialize()))
            throw new IllegalStateException("FileAccess clone must have same base key as original!");
        FileProperties props = getFileProperties(baseKey);
        FileAccess fa = FileAccess.create(newBaseKey, isDirectory() ? SymmetricKey.random() : getMetaKey(baseKey), props, this.retriever, parentLocation, parentparentKey);
        context.uploadChunk(fa, context.user, entryWriterKey, newMapKey, Collections.emptyList()); //TODO get fragment hashes from retriever
        return fa;
    }

    public static FileAccess deserialize(byte[] raw) throws IOException {
        DataSource buf = new DataSource(raw);
        byte[] p2m = buf.readArray();
        byte[] properties = buf.readArray();
        boolean hasRetreiver = buf.readBoolean();
        FileRetriever retriever =  hasRetreiver ? FileRetriever.deserialize(buf) : null;
        boolean hasParent = buf.readBoolean();
        SymmetricLocationLink parentLink =  hasParent ? SymmetricLocationLink.deserialize(buf.readArray()) : null;
        byte type = buf.readByte();
        FileAccess fileAccess = new FileAccess(new SymmetricLink(p2m), properties, retriever, parentLink);
        switch(type) {
            case 0:
                return fileAccess;
            case 1:
                return DirAccess.deserialize(fileAccess, buf);
            default: throw new Error("Unknown Metadata type: "+type);
        }
    }

    public static FileAccess create(SymmetricKey parentKey, SymmetricKey metaKey, FileProperties props,
                                    FileRetriever retriever, Location parentLocation, SymmetricKey parentparentKey) {
        byte[] nonce = metaKey.createNonce();
        return new FileAccess(SymmetricLink.fromPair(parentKey, metaKey),
                ArrayOps.concat(nonce, metaKey.encrypt(props.serialize(), nonce)), retriever, SymmetricLocationLink.create(parentKey, parentparentKey, parentLocation));
    }
}
