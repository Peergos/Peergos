package peergos.user.fs;

import peergos.crypto.*;
import peergos.util.Serialize;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class DirReadAccess
{
    private AsymmetricLink sharing2subfolders; // optional
    private final SymmetricLink subfolders2files;
    private final Map<SymmetricLink, byte[]> subfolders = new HashMap();
    private final Map<SymmetricLink, byte[]> files = new HashMap();
    private final SymmetricLink subfolders2parent;
    private final SymmetricLink parent2meta;
    private final byte[] metadata;
    public static final int MAX_ELEMENT_SIZE = Integer.MAX_VALUE;

    public DirReadAccess(SymmetricKey metaKey, SymmetricKey parentKey, SymmetricKey filesKey, SymmetricKey subfoldersKey, UserPublicKey sharingKey, byte[] rawMetadata)
    {
        this.subfolders2files = new SymmetricLink(subfoldersKey, filesKey);
        this.subfolders2parent = new SymmetricLink(subfoldersKey, parentKey);
        this.parent2meta = new SymmetricLink(parentKey, metaKey);
        this.metadata = metaKey.encrypt(rawMetadata, parent2meta.initializationVector());
        if (sharingKey != null)
            sharing2subfolders = new AsymmetricLink(sharingKey, subfoldersKey);
    }

    public DirReadAccess(SymmetricKey subfoldersKey, byte[] rawMetadata)
    {
        this(SymmetricKey.random(), SymmetricKey.random(), SymmetricKey.random(), subfoldersKey, null, rawMetadata);
    }

    public DirReadAccess(byte[] s2s, byte[] s2f, byte[] s2p, byte[] p2m, byte[] metadata)
    {
        if (s2s != null)
            sharing2subfolders = new AsymmetricLink(s2s);
        subfolders2files = new SymmetricLink(s2f);
        subfolders2parent = new SymmetricLink(s2p);
        parent2meta = new SymmetricLink(p2m);
        this.metadata = metadata;
    }

    public void serialize(DataOutput dout) throws IOException
    {
        Serialize.serialize(metadata, dout);
        Serialize.serialize(parent2meta.serialize(), dout);
        Serialize.serialize(subfolders2parent.serialize(), dout);
        Serialize.serialize(subfolders2files.serialize(), dout);
        if (sharing2subfolders != null) {
            dout.writeInt(1);
            Serialize.serialize(sharing2subfolders.serialize(), dout);
        } else {
            dout.writeInt(0);
        }
        dout.writeInt(subfolders.size());
        for (SymmetricLink sub: subfolders.keySet())
        {
            Serialize.serialize(sub.serialize(), dout);
            Serialize.serialize(subfolders.get(sub), dout);
        }
        dout.writeInt(files.size());
        for (SymmetricLink file: files.keySet())
        {
            Serialize.serialize(file.serialize(), dout);
            Serialize.serialize(files.get(file), dout);
        }
    }

    public static DirReadAccess deserialize(DataInput din) throws IOException
    {
        byte[] meta = Serialize.deserializeByteArray(din, MAX_ELEMENT_SIZE);
        byte[] p2m = Serialize.deserializeByteArray(din, MAX_ELEMENT_SIZE);
        byte[] s2p = Serialize.deserializeByteArray(din, MAX_ELEMENT_SIZE);
        byte[] s2f = Serialize.deserializeByteArray(din, MAX_ELEMENT_SIZE);
        byte[] s2s = null;
        if (din.readInt() != 0)
            s2s = Serialize.deserializeByteArray(din, MAX_ELEMENT_SIZE);
        DirReadAccess res =  new DirReadAccess(s2s, s2f, s2p, p2m, meta);
        int subs = din.readInt();
        for (int i=0; i < subs; i++)
        {
            SymmetricLink link = new SymmetricLink(Serialize.deserializeByteArray(din, MAX_ELEMENT_SIZE));
            byte[] mapKey = Serialize.deserializeByteArray(din, MAX_ELEMENT_SIZE);
            res.addSubFolder(mapKey, link);
        }
        int files = din.readInt();
        for (int i=0; i < files; i++)
        {
            SymmetricLink link = new SymmetricLink(Serialize.deserializeByteArray(din, MAX_ELEMENT_SIZE));
            byte[] mapKey = Serialize.deserializeByteArray(din, MAX_ELEMENT_SIZE);
            res.addFile(mapKey, link);
        }
        return res;
    }

    public void addSubFolder(byte[] mapKey, SymmetricKey ourSubfolders, SymmetricKey targetSubfolders)
    {
        subfolders.put(new SymmetricLink(ourSubfolders, targetSubfolders), mapKey);
    }

    public void addFile(byte[] mapKey, SymmetricKey ourSubfolders, SymmetricKey targetParent)
    {
        files.put(new SymmetricLink(ourSubfolders, targetParent), mapKey);
    }

    public void addSubFolder(byte[] mapKey, SymmetricLink toTargetSubfolders)
    {
        subfolders.put(toTargetSubfolders, mapKey);
    }

    public void addFile(byte[] mapKey, SymmetricLink toTargetParent)
    {
        files.put(toTargetParent, mapKey);
    }

    public void setSharingKey(UserPublicKey sharingKey, SymmetricKey subfoldersKey)
    {
        if (sharingKey != null)
            sharing2subfolders = new AsymmetricLink(sharingKey, subfoldersKey);
    }

    public SymmetricKey getParentKey(SymmetricKey subfoldersKey)
    {
        return subfolders2parent.target(subfoldersKey);
    }

    public SymmetricKey getFilesKey(SymmetricKey subfoldersKey)
    {
        return subfolders2files.target(subfoldersKey);
    }

    public SymmetricKey getSubfoldersKey(User sharingKey)
    {
        return sharing2subfolders.target(sharingKey);
    }
}
