package peergos.user.fs;

import peergos.crypto.*;
import peergos.util.Serialize;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.*;

public class FileAccess
{
    // read permissions
    private SortedMap<UserPublicKey, AsymmetricLink> sharingR2parent = new TreeMap();
    private final SymmetricLink parent2meta;
    // write permissions
    private SortedMap<UserPublicKey, AsymmetricLink> sharingW2parent = new TreeMap();

    private final byte[] metadata;
    public static final int MAX_ELEMENT_SIZE = Integer.MAX_VALUE;

    public FileAccess(Set<UserPublicKey> sharingR, SymmetricKey metaKey, SymmetricKey parentKey,
                      Set<UserPublicKey> sharingW, byte[] rawMetadata)
    {
        this.parent2meta = new SymmetricLink(parentKey, metaKey);
        if (sharingR != null) {
            for (UserPublicKey key: sharingR)
                sharingR2parent.put(key, new AsymmetricLink(key, parentKey));
        }
        this.metadata = metaKey.encrypt(rawMetadata, parent2meta.initializationVector());
        if (sharingW != null) {
            for (UserPublicKey key: sharingW)
                sharingW2parent.put(key, new AsymmetricLink(key, parentKey));
        }
    }

    public FileAccess(SymmetricKey parentKey, byte[] rawMetadata)
    {
        this(null, SymmetricKey.random(), parentKey, null, rawMetadata);
    }

    public FileAccess(byte[] m, byte[] p2m, Map<UserPublicKey, AsymmetricLink> sharingR)
    {
        metadata = m;
        parent2meta = new SymmetricLink(p2m);
        sharingR2parent.putAll(sharingR);
    }

    public void serialize(DataOutput dout) throws IOException
    {
        Serialize.serialize(metadata, dout);
        Serialize.serialize(parent2meta.serialize(), dout);
        dout.writeInt(sharingR2parent.size());
        for (UserPublicKey key: sharingR2parent.keySet()) {
            Serialize.serialize(key.getPublicKey(), dout);
            Serialize.serialize(sharingR2parent.get(key).serialize(), dout);
        }
    }

    public static FileAccess deserialize(DataInput din) throws IOException
    {
        byte[] meta = Serialize.deserializeByteArray(din, MAX_ELEMENT_SIZE);
        byte[] p2m = Serialize.deserializeByteArray(din, MAX_ELEMENT_SIZE);
        int count = din.readInt();
        Map<UserPublicKey, AsymmetricLink> sharingR = new HashMap();
        for (int i=0; i < count; i++) {
            sharingR.put(new UserPublicKey(Serialize.deserializeByteArray(din, MAX_ELEMENT_SIZE)),
                    new AsymmetricLink(Serialize.deserializeByteArray(din, MAX_ELEMENT_SIZE)));
        }
        FileAccess res = new FileAccess(meta, p2m, sharingR);
        return res;
    }

    public SymmetricKey getMetaKey(SymmetricKey parentKey)
    {
        return parent2meta.target(parentKey);
    }

    public SymmetricKey getParentKey(User sharingKey)
    {
        return sharingR2parent.get(sharingKey.getKey()).target(sharingKey);
    }

    public byte[] getMetadata(SymmetricKey metaKey)
    {
        return metaKey.decrypt(metadata, parent2meta.initializationVector());
    }
}
