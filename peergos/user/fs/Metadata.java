package peergos.user.fs;

import peergos.crypto.SymmetricKey;
import peergos.util.ByteArrayWrapper;
import peergos.util.Serialize;

import java.io.*;
import java.util.ArrayList;
import java.util.List;

// The user side version of a metadatablob on the core node

public class Metadata
{
    public static final int MAX_ELEMENT_SIZE = Chunk.MAX_SIZE;
    public static enum TYPE {DIR, FILE, FOLLOWER}

    private final TYPE type;
    protected final byte[] encryptedMetadata;

    public Metadata(TYPE t, byte[] encryptedMetadata)
    {
        this.type = t;
        this.encryptedMetadata = encryptedMetadata;
    }

    public Location getNextChunkLocation(SymmetricKey baseKey, byte[] iv) {
        if (iv == null)
            return getProps(baseKey).getNextChunkLocation();
        return getProps(baseKey, iv).getNextChunkLocation();
    }

    public ChunkProperties getProps(SymmetricKey baseKey) {
        throw new IllegalStateException("Follower metadata requires an initialiation vector to decrypt!");
    }

    public ChunkProperties getProps(SymmetricKey baseKey, byte[] iv) {
        return FileProperties.deserialize(baseKey.decrypt(encryptedMetadata, iv));
    }

    public List<ByteArrayWrapper> getFragmentHashes() {
        return new ArrayList();
    }

    public static Metadata deserialize(DataInput din, SymmetricKey ourKey) throws IOException {
        int index = din.readByte() & 0xff;
        if (index > TYPE.values().length)
            throw new IllegalStateException("Unknown metadata blob type! " + (index));
        TYPE t = TYPE.values()[index];
        byte[] meta = Serialize.deserializeByteArray(din, MAX_ELEMENT_SIZE);
        switch (t) {
            case DIR:
                return DirAccess.deserialize(din, ourKey, meta);
            case FILE:
                return FileAccess.deserialize(din, meta);
            case FOLLOWER:
                return new Metadata(t, Serialize.deserializeByteArray(din, Chunk.MAX_SIZE));
            default:
                return null;
        }
    }

    public void serialize(DataOutput dout) throws IOException
    {
        dout.writeByte(type.ordinal());
        Serialize.serialize(encryptedMetadata, dout);
    }
}
