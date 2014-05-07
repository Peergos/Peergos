package peergos.user.fs;

import peergos.crypto.*;
import peergos.util.Serialize;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

public class FileReadAccess
{
    private AsymmetricLink sharing2parent;
    private final SymmetricLink parent2meta;
    private final byte[] metadata;
    public static final int MAX_ELEMENT_SIZE = Integer.MAX_VALUE;

    public FileReadAccess(SymmetricKey metaKey, SymmetricKey parentKey, UserPublicKey sharingKey, byte[] rawMetadata)
    {
        this.parent2meta = new SymmetricLink(parentKey, metaKey);
        if (sharingKey != null)
            sharing2parent = new AsymmetricLink(sharingKey, parentKey);
        this.metadata = metaKey.encrypt(rawMetadata, parent2meta.initializationVector());
    }

    public FileReadAccess(byte[] m, byte[] p2m, byte[] s2p)
    {
        metadata = m;
        parent2meta = new SymmetricLink(p2m);
        if (s2p != null)
            sharing2parent = new AsymmetricLink(s2p);
    }

    public void serialize(DataOutput dout) throws IOException
    {
        Serialize.serialize(metadata, dout);
        Serialize.serialize(parent2meta.serialize(), dout);
        if (sharing2parent != null) {
            dout.writeInt(1);
            Serialize.serialize(sharing2parent.serialize(), dout);
        } else {
            dout.writeInt(0);
        }
    }

    public static FileReadAccess deserialize(DataInput din) throws IOException
    {
        byte[] meta = Serialize.deserializeByteArray(din, MAX_ELEMENT_SIZE);
        byte[] p2m = Serialize.deserializeByteArray(din, MAX_ELEMENT_SIZE);
        byte[] s2p = null;
        if (din.readInt() != 0)
            s2p = Serialize.deserializeByteArray(din, MAX_ELEMENT_SIZE);
        return new FileReadAccess(meta, p2m, s2p);
    }

    public SymmetricKey getMetaKey(SymmetricKey parentKey)
    {
        return parent2meta.target(parentKey);
    }

    public SymmetricKey getParentKey(User sharingKey)
    {
        return sharing2parent.target(sharingKey);
    }

    public byte[] getMetadata(SymmetricKey metaKey)
    {
        return metaKey.decrypt(metadata, parent2meta.initializationVector());
    }
}
