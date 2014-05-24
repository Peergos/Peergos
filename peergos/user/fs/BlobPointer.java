package peergos.user.fs;

import peergos.crypto.UserPublicKey;
import peergos.util.ByteArrayWrapper;
import peergos.util.Serialize;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public class BlobPointer
{
    private final String owner;
    private final UserPublicKey writingRoot;
    private final ByteArrayWrapper mapkey;

    public BlobPointer(String owner, UserPublicKey writingRoot, ByteArrayWrapper mapkey)
    {
        this.owner = owner;
        this.writingRoot = writingRoot;
        this.mapkey = mapkey;
    }

    public byte[] serialize()
    {
        try {
            ByteArrayOutputStream bout = new ByteArrayOutputStream();
            DataOutputStream dout = new DataOutputStream(bout);
            Serialize.serialize(owner, dout);
            Serialize.serialize(writingRoot.getPublicKey(), dout);
            Serialize.serialize(mapkey.data, dout);
            dout.flush();
            return bout.toByteArray();
        } catch (IOException e) {e.printStackTrace(); throw new IllegalStateException("Shouldn't get here!");}
    }
}
