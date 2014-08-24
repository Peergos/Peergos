package peergos.user.fs;

import peergos.crypto.SymmetricKey;
import peergos.user.UserContext;
import peergos.util.Serialize;

import java.io.*;

public class FileProperties
{
    public final String name;
    private final byte[] iv;
    private final long size;

    public FileProperties(String name, byte[] iv, long size) {
        this.name = name;
        this.size = size;
        this.iv = iv;
    }

    public byte[] getIV() {
        return iv;
    }

    public long getSize() {
        return size;
    }

    public byte[] serialize(){
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        DataOutputStream dout = new DataOutputStream(bout);
        try {
            Serialize.serialize(name, dout);
            Serialize.serialize(iv, dout);
            dout.writeLong(size);
            dout.flush();
        } catch (IOException e) {e.printStackTrace();}
        return bout.toByteArray();
    }

    public static FileProperties deserialize(byte[] data) {
        DataInputStream din = new DataInputStream(new ByteArrayInputStream(data));
        try {
            return new FileProperties(Serialize.deserializeString(din, UserContext.MAX_USERNAME_SIZE), Serialize.deserializeByteArray(din, SymmetricKey.IV_SIZE), din.readLong());
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }
}
