package peergos.shared.user.fs;

import peergos.shared.cbor.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.symmetric.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.user.*;
import peergos.shared.util.*;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

public class FileAccess implements Cborable {
    protected final SymmetricLink parent2meta;
    protected final byte[] properties;
    protected final FileRetriever retriever;
    protected final SymmetricLocationLink parentLink;
    protected final List<Multihash> fragmentHashes;

    public FileAccess(SymmetricLink parent2Meta, byte[] fileProperties, FileRetriever fileRetriever,
                      SymmetricLocationLink parentLink, List<Multihash> fragmentHashes) {
        this.parent2meta = parent2Meta;
        this.properties = fileProperties;
        this.retriever = fileRetriever;
        this.parentLink = parentLink;
        this.fragmentHashes = fragmentHashes;
    }

    @Override
    public CborObject toCbor() {
        Map<String, CborObject> cbor = new TreeMap<>();
        CborObject.CborByteArray cryptree = new CborObject.CborByteArray(serializeCryptTree().toByteArray());
        cbor.put("c", cryptree);
        List<CborObject> linksToFragments = fragmentHashes.stream()
                .map(CborObject.CborMerkleLink::new)
                .collect(Collectors.toList());
        cbor.put("f", new CborObject.CborList(linksToFragments));

        return CborObject.CborMap.build(cbor);
    }

    protected DataSink serializeCryptTree() {
        DataSink bout = new DataSink();
        bout.writeArray(parent2meta.serialize());
        bout.writeArray(properties);
        bout.writeByte(retriever != null ? 1 : 0);
        if (retriever != null)
            retriever.serialize(bout);
        bout.writeByte(parentLink != null ? 1: 0);
        if (parentLink != null)
            bout.writeArray(this.parentLink.serialize());
        bout.writeByte(this.getType());
        return bout;
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

    public FileProperties getFileProperties(SymmetricKey parentKey) {
        try {
            byte[] nonce = Arrays.copyOfRange(properties, 0, TweetNaCl.SECRETBOX_NONCE_BYTES);
            byte[] cipher = Arrays.copyOfRange(properties, TweetNaCl.SECRETBOX_NONCE_BYTES, this.properties.length);
            return FileProperties.deserialize(getMetaKey(parentKey).decrypt(cipher, nonce));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public CompletableFuture<RetrievedFilePointer> getParent(SymmetricKey parentKey, UserContext context) {
        if (this.parentLink == null)
            return CompletableFuture.completedFuture(null);

        return context.retrieveAllMetadata(Arrays.asList(parentLink), parentKey).thenApply(res -> {
            RetrievedFilePointer retrievedFilePointer = res.stream().findAny().get();
            return retrievedFilePointer;
        });
    }

    public FileRetriever retriever() {
        return retriever;
    }

    public CompletableFuture<Boolean> rename(FilePointer writableFilePointer, FileProperties newProps, UserContext context) {
        if (!writableFilePointer.isWritable())
            throw new IllegalStateException("Need a writable pointer!");
        SymmetricKey metaKey = this.getMetaKey(writableFilePointer.baseKey);
        byte[] nonce = metaKey.createNonce();
        FileAccess fa = new FileAccess(this.parent2meta, ArrayOps.concat(nonce, metaKey.encrypt(newProps.serialize(), nonce)), this.retriever, this.parentLink, this.fragmentHashes);
        return context.uploadChunk(fa, writableFilePointer.location, writableFilePointer.signer());
    }

    public CompletableFuture<FileAccess> markDirty(FilePointer writableFilePointer, SymmetricKey newParentKey, UserContext context) {
        // keep the same metakey, just marked as dirty
        SymmetricKey metaKey = this.getMetaKey(writableFilePointer.baseKey).makeDirty();
        SymmetricLink newParentToMeta = SymmetricLink.fromPair(newParentKey, metaKey);
        SymmetricLocationLink newParentLink = SymmetricLocationLink.create(newParentKey,
                parentLink.target(writableFilePointer.baseKey),
                parentLink.targetLocation(writableFilePointer.baseKey));
        FileAccess fa = new FileAccess(newParentToMeta, properties, this.retriever, newParentLink, fragmentHashes);
        return context.uploadChunk(fa, writableFilePointer.location,
                writableFilePointer.signer())
                .thenApply(x -> fa);
    }

    public boolean isDirty(SymmetricKey baseKey) {
        return getMetaKey(baseKey).isDirty();
    }

    public CompletableFuture<? extends FileAccess> copyTo(SymmetricKey baseKey, SymmetricKey newBaseKey, Location parentLocation, SymmetricKey parentparentKey,
                                                          SigningKeyPair entryWriterKey, byte[] newMapKey, UserContext context) {
        if (!Arrays.equals(baseKey.serialize(), newBaseKey.serialize()))
            throw new IllegalStateException("FileAccess clone must have same base key as original!");
        FileProperties props = getFileProperties(baseKey);
        FileAccess fa = FileAccess.create(newBaseKey, isDirectory() ? SymmetricKey.random() : getMetaKey(baseKey), props,
                this.retriever, parentLocation, parentparentKey, fragmentHashes);
        return context.uploadChunk(fa, new Location(context.signer.publicSigningKey, entryWriterKey.publicSigningKey, newMapKey), entryWriterKey)
                .thenApply(b -> fa);
    }

    public static FileAccess fromCbor(CborObject cbor) {
        if (! (cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Incorrect cbor for FileAccess: " + cbor);

        SortedMap<CborObject, CborObject> values = ((CborObject.CborMap) cbor).values;

        List<Multihash> fragmentHashes = ((CborObject.CborList) values.get(new CborObject.CborString("f"))).value
                .stream()
                .map(c -> ((CborObject.CborMerkleLink) c).target)
                .collect(Collectors.toList());

        byte[] cryptree = ((CborObject.CborByteArray) values.get(new CborObject.CborString("c"))).value;
        return deserialize(cryptree, fragmentHashes);
    }

    private static FileAccess deserialize(byte[] raw, List<Multihash> fragmentHashes) {
        try {
            DataSource buf = new DataSource(raw);
            byte[] p2m = buf.readArray();
            byte[] properties = buf.readArray();
            boolean hasRetreiver = buf.readBoolean();
            FileRetriever retriever = hasRetreiver ? FileRetriever.deserialize(buf) : null;
            boolean hasParent = buf.readBoolean();
            SymmetricLocationLink parentLink = hasParent ? SymmetricLocationLink.deserialize(buf.readArray()) : null;
            byte type = buf.readByte();
            FileAccess fileAccess = new FileAccess(new SymmetricLink(p2m), properties, retriever, parentLink, fragmentHashes);
            switch (type) {
                case 0:
                    return fileAccess;
                case 1:
                    return DirAccess.deserialize(fileAccess, buf);
                default:
                    throw new Error("Unknown Metadata type: " + type);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static FileAccess create(SymmetricKey parentKey, SymmetricKey metaKey, FileProperties props,
                                    FileRetriever retriever, Location parentLocation, SymmetricKey parentparentKey, List<Multihash> fragmentHashes) {
        byte[] nonce = metaKey.createNonce();
        return new FileAccess(SymmetricLink.fromPair(parentKey, metaKey),
                ArrayOps.concat(nonce, metaKey.encrypt(props.serialize(), nonce)),
                retriever,
                SymmetricLocationLink.create(parentKey, parentparentKey, parentLocation),
                fragmentHashes);
    }
}
