package peergos.user.fs;

import peergos.crypto.SymmetricKey;
import peergos.util.*;

import java.io.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

// The user side version of a metadatablob on the core node

public class Metadata
{
    public static final int MAX_ELEMENT_SIZE = Chunk.MAX_SIZE; //TODO restrict this
    public static enum TYPE {DIR, FILE, FOLLOWER}

    private final TYPE type;
    private final byte[] metaNonce;
    protected final byte[] encryptedMetadata;

    // public data
    private List<ByteArrayWrapper> fragments;

    public Metadata(TYPE t, byte[] encryptedMetadata, byte[] metaNonce)
    {
        this.type = t;
        this.encryptedMetadata = encryptedMetadata;
        this.metaNonce = metaNonce;
    }

    public Metadata(ChunkProperties props, SymmetricKey baseKey, byte[] metaNonce) {
        type = TYPE.FOLLOWER;
        encryptedMetadata = baseKey.encrypt(props.serialize(), metaNonce);
        this.metaNonce = metaNonce;
    }

    public void setFragments(List<ByteArrayWrapper> fragments) {
        this.fragments = fragments;
    }

    public List<ByteArrayWrapper> getFragmentHashes() {
        if (fragments == null)
            return Collections.EMPTY_LIST;
        return Collections.unmodifiableList(fragments);
    }

    public Location getNextChunkLocation(SymmetricKey baseKey, byte[] iv) {
        if (iv == null)
            return getProps(baseKey).getNextChunkLocation();
        return getProps(baseKey, iv).getNextChunkLocation();
    }

    public ChunkProperties getProps(SymmetricKey baseKey) {
        throw new IllegalStateException("Follower metadata requires an initialization vector to decrypt!");
    }

    public ChunkProperties getProps(SymmetricKey baseKey, byte[] iv) {
        try {
            return new ChunkProperties(new DataInputStream(new ByteArrayInputStream(baseKey.decrypt(encryptedMetadata, iv))));
        } catch (IOException e) {e.printStackTrace();return null;}
    }

    public static Metadata deserialize(DataInput din, SymmetricKey ourKey) throws IOException {
        int index = din.readByte() & 0xff;
        if (index > TYPE.values().length)
            throw new IllegalStateException("Unknown metadata blob type! " + (index));
        TYPE t = TYPE.values()[index];
        byte[] metaNonce = Serialize.deserializeByteArray(din, MAX_ELEMENT_SIZE);
        byte[] meta = Serialize.deserializeByteArray(din, MAX_ELEMENT_SIZE);
        switch (t) {
            case DIR:
                return DirAccess.deserialize(din, ourKey, meta);
            case FILE:
                return FileAccess.deserialize(din, meta);
            case FOLLOWER:
                return new Metadata(t, meta, metaNonce);
            default:
                return null;
        }
    }

    public void serialize(DataOutput dout) throws IOException
    {
        dout.writeByte(type.ordinal());
        Serialize.serialize(metaNonce, dout);
        Serialize.serialize(encryptedMetadata, dout);
    }
}
