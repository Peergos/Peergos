package peergos.user.fs;

import peergos.crypto.SymmetricKey;
import peergos.util.ByteArrayWrapper;

import java.io.*;
import java.util.ArrayList;
import java.util.List;

// The user side version of a metadatablob on the core node

public class Metadata
{
    public static enum TYPE {DIR, FILE, FOLLOWER}
    private final TYPE type;

    public Metadata(TYPE t)
    {
        this.type = t;
    }

    public List<ByteArrayWrapper> getFragmentHashes() {
        return new ArrayList();
    }

    public static Metadata deserialize(DataInput din, SymmetricKey ourKey, List<ByteArrayWrapper> fragments) throws IOException {
        int index = din.readByte() & 0xff;
        if (index > TYPE.values().length)
            throw new IllegalStateException("Unknown metadata blob type! " + (index));
        TYPE t = TYPE.values()[index];
        switch (t) {
            case DIR:
                return DirAccess.deserialize(din, ourKey);
            case FILE:
                return FileAccess.deserialize(din, fragments);
            case FOLLOWER:
                return null;
            default:
                return null;
        }
    }

    public void serialize(DataOutput dout) throws IOException
    {
        dout.writeByte(type.ordinal());
    }
}
