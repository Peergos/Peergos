package peergos.user.fs;

import peergos.user.UserContext;
import peergos.util.Serialize;

import java.io.*;

public class FileProperties
{
    public final String name;
    private final long size;

    public FileProperties(String name, long size) {
        this.name = name;
        this.size = size;
    }

    public long getSize() {
        return size;
    }

    public byte[] serialize(){
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        DataOutputStream dout = new DataOutputStream(bout);
        try {
            Serialize.serialize(name, dout);
            dout.writeLong(size);
            dout.flush();
        } catch (IOException e) {e.printStackTrace();}
        return bout.toByteArray();
    }

    public static FileProperties deserialize(byte[] data) throws IOException {
        DataInputStream din = new DataInputStream(new ByteArrayInputStream(data));
        String name = Serialize.deserializeString(din, UserContext.MAX_USERNAME_SIZE);
        long size = din.readLong();
        return new FileProperties(name, size);
    }
}
