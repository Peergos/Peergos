package peergos.user.fs;

import peergos.crypto.*;
import peergos.util.ByteArrayWrapper;
import peergos.util.Serialize;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.security.KeyPair;
import java.util.*;

public class DirAccess extends Metadata
{
    // read permissions
    private SortedMap<UserPublicKey, AsymmetricLink> sharingR2subfoldersR = new TreeMap(); // optional
    private final SymmetricLink subfolders2files;
    private final Map<SymmetricLink, Location> subfolders = new HashMap();
    private final Map<SymmetricLink, Location> files = new HashMap();
    private final SymmetricLink subfolders2parent;
    private final SymmetricLink parent2meta;
    // write permissions => able to create files and subfolders
    private SortedMap<UserPublicKey, AsymmetricLink> sharingW2subfoldersW = new TreeMap(); // optional
    private final SymmetricLinkToPrivate subfoldersW2sign;
    private final UserPublicKey verifyW;

    public static final int MAX_ELEMENT_SIZE = Integer.MAX_VALUE;
    private final byte[] metadata;

    public DirAccess(SymmetricKey meta, SymmetricKey parent, SymmetricKey files, SymmetricKey subfolders, Set<UserPublicKey> sharingR,
                     byte[] rawMetadata, KeyPair signingW, SymmetricKey subfoldersW, Set<UserPublicKey> sharingW)
    {
        super(TYPE.DIR);
        this.subfolders2files = new SymmetricLink(subfolders, files);
        this.subfolders2parent = new SymmetricLink(subfolders, parent);
        this.parent2meta = new SymmetricLink(parent, meta);
        this.metadata = meta.encrypt(rawMetadata, parent2meta.initializationVector());
        for (UserPublicKey key: sharingR)
            sharingR2subfoldersR.put(key, new AsymmetricLink(key, subfolders));
        subfoldersW2sign = new SymmetricLinkToPrivate(subfoldersW, signingW.getPrivate());
        verifyW = new UserPublicKey(signingW.getPublic());
        for (UserPublicKey key: sharingW)
            sharingW2subfoldersW.put(key, new AsymmetricLink(key, subfoldersW));
    }

    public DirAccess(SymmetricKey subfoldersKey, byte[] rawMetadata, SymmetricKey subfoldersKeyW)
    {
        this(SymmetricKey.random(), SymmetricKey.random(), SymmetricKey.random(), subfoldersKey, null, rawMetadata, SSL.generateKeyPair(), subfoldersKeyW, null);
    }

    public DirAccess(Map<UserPublicKey, AsymmetricLink> sharingR, byte[] s2f, byte[] s2p, byte[] p2m, byte[] metadata,
                     byte[] verr, byte[] sW2si, Map<UserPublicKey, AsymmetricLink> sharingW)
    {
        super(TYPE.DIR);
        sharingR2subfoldersR.putAll(sharingR);
        subfolders2files = new SymmetricLink(s2f);
        subfolders2parent = new SymmetricLink(s2p);
        parent2meta = new SymmetricLink(p2m);
        this.metadata = metadata;
        sharingW2subfoldersW.putAll(sharingW);
        this.subfoldersW2sign = new SymmetricLinkToPrivate(sW2si);
        this.verifyW = new UserPublicKey(verr);
    }

    public void serialize(DataOutput dout) throws IOException
    {
        super.serialize(dout);
        Serialize.serialize(metadata, dout);
        // read access
        Serialize.serialize(parent2meta.serialize(), dout);
        Serialize.serialize(subfolders2parent.serialize(), dout);
        Serialize.serialize(subfolders2files.serialize(), dout);
        dout.writeInt(sharingR2subfoldersR.size());
        for (UserPublicKey key: sharingR2subfoldersR.keySet()) {
            Serialize.serialize(key.getPublicKey(), dout);
            Serialize.serialize(sharingR2subfoldersR.get(key).serialize(), dout);
        }
        // write access
        Serialize.serialize(verifyW.getPublicKey(), dout);
        Serialize.serialize(subfoldersW2sign.serialize(), dout);
        dout.writeInt(sharingW2subfoldersW.size());
        for (UserPublicKey key: sharingW2subfoldersW.keySet()) {
            Serialize.serialize(key.getPublicKey(), dout);
            Serialize.serialize(sharingW2subfoldersW.get(key).serialize(), dout);
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
        int readSharingKeys = din.readInt();
        Map<UserPublicKey, AsymmetricLink> sharingR = new HashMap();
        for (int i=0; i < readSharingKeys; i++)
            sharingR.put(new UserPublicKey(Serialize.deserializeByteArray(din, MAX_ELEMENT_SIZE)),
                    new AsymmetricLink(Serialize.deserializeByteArray(din, MAX_ELEMENT_SIZE)));
        byte[] verr = Serialize.deserializeByteArray(din, MAX_ELEMENT_SIZE);
        byte[] sW2si = Serialize.deserializeByteArray(din, MAX_ELEMENT_SIZE);
        byte[] sW2sW = null;
        int writeSharingKeys = din.readInt();
        Map<UserPublicKey, AsymmetricLink> sharingW = new HashMap();
        for (int i=0; i < writeSharingKeys; i++) {
            sharingW.put(new UserPublicKey(Serialize.deserializeByteArray(din, MAX_ELEMENT_SIZE)),
                    new AsymmetricLink(Serialize.deserializeByteArray(din, MAX_ELEMENT_SIZE)));
        }
        DirAccess res =  new DirAccess(sharingR, s2f, s2p, p2m, meta, verr, sW2si, sharingW);
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

    public void addSubFolder(Location location, SymmetricKey ourSubfolders, SymmetricKey targetSubfolders)
    {
        subfolders.put(new SymmetricLink(ourSubfolders, targetSubfolders), location);
    }

    public void addFile(Location location, SymmetricKey ourSubfolders, SymmetricKey targetParent)
    {
        files.put(new SymmetricLink(ourSubfolders, targetParent), location);
    }

    public void addSubFolder(Location location, SymmetricLink toTargetSubfolders)
    {
        subfolders.put(toTargetSubfolders, location);
    }

    public void addFile(Location location, SymmetricLink toTargetParent)
    {
        files.put(toTargetParent, location);
    }

    public void addRSharingKey(UserPublicKey key, SymmetricKey subfoldersKey)
    {
        sharingR2subfoldersR.put(key, new AsymmetricLink(key, subfoldersKey));
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
        return sharingR2subfoldersR.get(sharingKey).target(sharingKey);
    }
}
