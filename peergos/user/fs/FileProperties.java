package peergos.user.fs;

import peergos.crypto.SymmetricKey;
import peergos.user.UserContext;
import peergos.util.Serialize;

import java.io.*;

public class FileProperties extends ChunkProperties
{
    public final String name;
    private final long size;

    public FileProperties(String name, byte[] iv, long size, Location next) {
        super(iv, next);
        this.name = name;
        this.size = size;
    }

    public boolean isPrimary() {
        return true;
    }

    public long getSize() {
        return size;
    }

    public byte[] serialize(){
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        DataOutputStream dout = new DataOutputStream(bout);
        try {
            super.serialise(dout);
            Serialize.serialize(name, dout);
            dout.writeLong(size);
            dout.flush();
        } catch (IOException e) {e.printStackTrace();}
        return bout.toByteArray();
    }

    public static FileProperties deserialize(byte[] data) {
        DataInputStream din = new DataInputStream(new ByteArrayInputStream(data));
        try {
            return new FileProperties(Serialize.deserializeString(din, UserContext.MAX_USERNAME_SIZE), Serialize.deserializeByteArray(din, SymmetricKey.IV_SIZE), din.readLong(), Location.deserialise(din));
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }
}
