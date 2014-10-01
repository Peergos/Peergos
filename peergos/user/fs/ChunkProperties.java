package peergos.user.fs;

import peergos.crypto.SymmetricKey;
import peergos.util.Serialize;

import java.io.*;

public class ChunkProperties
{
    private final byte[] iv;
    private final Location next;

    public ChunkProperties(byte[] iv, Location next) {
        this.iv = iv;
        this.next = next;
    }

    public ChunkProperties(DataInput din) throws IOException {
        iv = Serialize.deserializeByteArray(din, SymmetricKey.IV_SIZE);
        boolean hasNext = din.readBoolean();
        if (hasNext)
            next = Location.deserialise(din);
        else
            next = null;
    }

    public byte[] serialize(){
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        DataOutputStream dout = new DataOutputStream(bout);
        try {
            if (next == null)
                dout.writeBoolean(false);
            else {
                dout.writeBoolean(true);
                next.serialise(dout);
            }
            dout.flush();
        } catch (IOException e) {e.printStackTrace();}
        return bout.toByteArray();
    }

    public void serialise(DataOutputStream dout) throws IOException {
        Serialize.serialize(iv, dout);
        next.serialise(dout);
    }

    public boolean isPrimary() {
        return false;
    }

    public byte[] getIV() {
        return iv;
    }

    public Location getNextChunkLocation() {
        return next;
    }
}
