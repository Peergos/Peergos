package peergos.user.fs;

import peergos.crypto.UserPublicKey;
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

    public byte[] serialiseFragmentHashes() {
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        try {
            List<ByteArrayWrapper> hashes = getFragmentHashes();
            for (ByteArrayWrapper bw : hashes)
                bout.write(bw.data);
        } catch (IOException e) {e.printStackTrace();}
        return bout.toByteArray();
    }

    public static Metadata deserialize(DataInput din) throws IOException {
        int index = din.readByte() & 0xff;
        if (index > TYPE.values().length)
            throw new IllegalStateException("Unknown metadata blob type! " + (index));
        TYPE t = TYPE.values()[index];
        switch (t) {
            case DIR:
                return DirAccess.deserialize(din);
            case FILE:
                return FileAccess.deserialize(din);
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
