package peergos.user.fs;

import peergos.crypto.*;
import peergos.util.Serialize;

import java.io.*;

public class ChunkProperties
{
    private final byte[] nonce;
    private final byte[] auth;
    private final Location next;

    public ChunkProperties(byte[] nonce, byte[] auth, Location next) {
        this.nonce = nonce;
        this.auth = auth;
        this.next = next;
    }

    public ChunkProperties(DataInput din) throws IOException {
        nonce = Serialize.deserializeByteArray(din, SymmetricKey.NONCE_BYTES);
        auth = Serialize.deserializeByteArray(din, TweetNaCl.SECRETBOX_OVERHEAD_BYTES);
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
            serialise(dout);
        } catch (IOException e) {e.printStackTrace();}
        return bout.toByteArray();
    }

    public void serialise(DataOutputStream dout) throws IOException {
        Serialize.serialize(nonce, dout);
        Serialize.serialize(auth, dout);
        if (next == null)
            dout.writeBoolean(false);
        else {
            dout.writeBoolean(true);
            next.serialise(dout);
        }
        dout.flush();
    }

    public boolean isPrimary() {
        return false;
    }

    public byte[] getNonce() {
        return nonce;
    }

    public byte[] getAuth() {
        return auth;
    }

    public Location getNextChunkLocation() {
        return next;
    }
}
