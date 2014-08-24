package peergos.user.fs;

import peergos.crypto.*;
import peergos.util.ByteArrayWrapper;
import peergos.util.Serialize;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.*;

public class FileAccess extends Metadata
{
    // read permissions
    private SortedMap<UserPublicKey, AsymmetricLink> sharingR2parent = new TreeMap();
    private final SymmetricLink parent2meta;

    private final byte[] metadata;
    public static final int MAX_ELEMENT_SIZE = Integer.MAX_VALUE;

    // public data
    private List<ByteArrayWrapper> fragments;

    public FileAccess(Set<UserPublicKey> sharingR, SymmetricKey metaKey, SymmetricKey parentKey, byte[] rawMetadata, List<ByteArrayWrapper> fragments)
    {
        super(TYPE.FILE);
        this.fragments = fragments;
        this.parent2meta = new SymmetricLink(parentKey, metaKey);
        if (sharingR != null) {
            for (UserPublicKey key: sharingR)
                sharingR2parent.put(key, new AsymmetricLink(key, parentKey));
        }
        this.metadata = metaKey.encrypt(rawMetadata, parent2meta.initializationVector());
    }

    public FileAccess(SymmetricKey parentKey, byte[] rawMetadata, List<ByteArrayWrapper> fragments)
    {
        this(null, SymmetricKey.random(), parentKey, rawMetadata, fragments);
    }

    public FileAccess(byte[] m, byte[] p2m, Map<UserPublicKey, AsymmetricLink> sharingR, List<ByteArrayWrapper> fragments)
    {
        super(TYPE.FILE);
        metadata = m;
        parent2meta = new SymmetricLink(p2m);
        sharingR2parent.putAll(sharingR);
        this.fragments = fragments;
    }

    public void serialize(DataOutput dout) throws IOException
    {
        super.serialize(dout);
        Serialize.serialize(metadata, dout);
        Serialize.serialize(parent2meta.serialize(), dout);
        dout.writeInt(sharingR2parent.size());
        for (UserPublicKey key: sharingR2parent.keySet()) {
            Serialize.serialize(key.getPublicKey(), dout);
            Serialize.serialize(sharingR2parent.get(key).serialize(), dout);
        }
    }

    public static FileAccess deserialize(DataInput din, List<ByteArrayWrapper> fragments) throws IOException
    {
        byte[] meta = Serialize.deserializeByteArray(din, MAX_ELEMENT_SIZE);
        byte[] p2m = Serialize.deserializeByteArray(din, MAX_ELEMENT_SIZE);
        int count = din.readInt();
        Map<UserPublicKey, AsymmetricLink> sharingR = new HashMap();
        for (int i=0; i < count; i++) {
            sharingR.put(new UserPublicKey(Serialize.deserializeByteArray(din, MAX_ELEMENT_SIZE)),
                    new AsymmetricLink(Serialize.deserializeByteArray(din, MAX_ELEMENT_SIZE)));
        }
        FileAccess res = new FileAccess(meta, p2m, sharingR, fragments);
        return res;
    }

    public List<ByteArrayWrapper> getFragmentHashes() {
        return Collections.unmodifiableList(fragments);
    }

    public SymmetricKey getMetaKey(SymmetricKey parentKey)
    {
        return parent2meta.target(parentKey);
    }

    public SymmetricKey getParentKey(User sharingKey)
    {
        return sharingR2parent.get(sharingKey.getKey()).target(sharingKey);
    }

    public byte[] getMetadata(SymmetricKey parentKey)
    {
        return parent2meta.target(parentKey).decrypt(metadata, parent2meta.initializationVector());
    }
}
