package peergos.user.fs;

import peergos.util.Serialize;

import java.io.DataOutputStream;
import java.io.IOException;

public class ChunkProperties
{
    private final byte[] iv;
    private final Location next;

    public ChunkProperties(byte[] iv, Location next) {
        this.iv = iv;
        this.next = next;
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
