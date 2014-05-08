package peergos.user.fs;

import peergos.crypto.*;
import peergos.util.Serialize;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.security.KeyPair;
import java.security.PublicKey;
import java.util.HashMap;
import java.util.Map;

public class DirAccess
{
    // read permissions
    private AsymmetricLink sharing2subfolders; // optional
    private final SymmetricLink subfolders2files;
    private final Map<SymmetricLink, byte[]> subfolders = new HashMap();
    private final Map<SymmetricLink, byte[]> files = new HashMap();
    private final SymmetricLink subfolders2parent;
    private final SymmetricLink parent2meta;
    // write permissions
    private AsymmetricLink sharingW2subfoldersW; // optional
    private final SymmetricLinkToPrivate subfoldersW2sign;
    private final UserPublicKey verifyW;

    public static final int MAX_ELEMENT_SIZE = Integer.MAX_VALUE;
    private final byte[] metadata;

    public DirAccess(SymmetricKey meta, SymmetricKey parent, SymmetricKey files, SymmetricKey subfolders, UserPublicKey sharing,
                     byte[] rawMetadata, KeyPair signingW, SymmetricKey subfoldersW, UserPublicKey sharingW)
    {
        this.subfolders2files = new SymmetricLink(subfolders, files);
        this.subfolders2parent = new SymmetricLink(subfolders, parent);
        this.parent2meta = new SymmetricLink(parent, meta);
        this.metadata = meta.encrypt(rawMetadata, parent2meta.initializationVector());
        if (sharing != null)
            sharing2subfolders = new AsymmetricLink(sharing, subfolders);
        subfoldersW2sign = new SymmetricLinkToPrivate(subfoldersW, signingW.getPrivate());
        verifyW = new UserPublicKey(signingW.getPublic());
        if (sharingW != null)
            sharingW2subfoldersW = new AsymmetricLink(sharingW, subfoldersW);
    }

    public DirAccess(SymmetricKey subfoldersKey, byte[] rawMetadata, SymmetricKey subfoldersKeyW)
    {
        this(SymmetricKey.random(), SymmetricKey.random(), SymmetricKey.random(), subfoldersKey, null, rawMetadata, SSL.generateKeyPair(), subfoldersKeyW, null);
    }

    public DirAccess(byte[] s2s, byte[] s2f, byte[] s2p, byte[] p2m, byte[] metadata, byte[] verr, byte[] sW2si, byte[] sW2sW)
    {
        if (s2s != null)
            sharing2subfolders = new AsymmetricLink(s2s);
        subfolders2files = new SymmetricLink(s2f);
        subfolders2parent = new SymmetricLink(s2p);
        parent2meta = new SymmetricLink(p2m);
        this.metadata = metadata;
        if (sW2sW != null)
            this.sharingW2subfoldersW = new AsymmetricLink(sW2sW);
        this.subfoldersW2sign = new SymmetricLinkToPrivate(sW2si);
        this.verifyW = new UserPublicKey(verr);
    }

    public void serialize(DataOutput dout) throws IOException
    {
        Serialize.serialize(metadata, dout);
        // read access
        Serialize.serialize(parent2meta.serialize(), dout);
        Serialize.serialize(subfolders2parent.serialize(), dout);
        Serialize.serialize(subfolders2files.serialize(), dout);
        if (sharing2subfolders != null) {
            dout.writeInt(1);
            Serialize.serialize(sharing2subfolders.serialize(), dout);
        } else {
            dout.writeInt(0);
        }
        // write access
        Serialize.serialize(verifyW.getPublicKey(), dout);
        Serialize.serialize(subfoldersW2sign.serialize(), dout);
        if (sharingW2subfoldersW != null)
        {
            dout.writeInt(1);
            Serialize.serialize(sharingW2subfoldersW.serialize(), dout);
        } else {
            dout.writeInt(0);
        }
        // read subtree
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

    public static DirAccess deserialize(DataInput din) throws IOException
    {
        byte[] meta = Serialize.deserializeByteArray(din, MAX_ELEMENT_SIZE);
        byte[] p2m = Serialize.deserializeByteArray(din, MAX_ELEMENT_SIZE);
        byte[] s2p = Serialize.deserializeByteArray(din, MAX_ELEMENT_SIZE);
        byte[] s2f = Serialize.deserializeByteArray(din, MAX_ELEMENT_SIZE);
        byte[] s2s = null;
        if (din.readInt() != 0)
            s2s = Serialize.deserializeByteArray(din, MAX_ELEMENT_SIZE);
        byte[] verr = Serialize.deserializeByteArray(din, MAX_ELEMENT_SIZE);
        byte[] sW2si = Serialize.deserializeByteArray(din, MAX_ELEMENT_SIZE);
        byte[] sW2sW = null;
        if (din.readInt() != 0)
            sW2sW = Serialize.deserializeByteArray(din, MAX_ELEMENT_SIZE);
        DirAccess res =  new DirAccess(s2s, s2f, s2p, p2m, meta, verr, sW2si, sW2sW);
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

    public void setRSharingKey(UserPublicKey sharingKey, SymmetricKey subfoldersKey)
    {
        if (sharingKey != null)
            sharing2subfolders = new AsymmetricLink(sharingKey, subfoldersKey);
    }

    public SymmetricKey getRParentKey(SymmetricKey subfoldersKey)
    {
        return subfolders2parent.target(subfoldersKey);
    }

    public SymmetricKey getRFilesKey(SymmetricKey subfoldersKey)
    {
        return subfolders2files.target(subfoldersKey);
    }

    public SymmetricKey getRSubfoldersKey(User sharingKey)
    {
        return sharing2subfolders.target(sharingKey);
    }
}
