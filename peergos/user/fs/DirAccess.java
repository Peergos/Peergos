package peergos.user.fs;

import peergos.crypto.*;
import peergos.util.ByteArrayWrapper;
import peergos.util.Serialize;

import java.io.*;
import java.security.KeyPair;
import java.util.*;

public class DirAccess extends Metadata
{
    // read permissions
    private SortedMap<UserPublicKey, AsymmetricLink> sharingR2subfoldersR = new TreeMap(); // optional
    private final SymmetricLink subfolders2files;
    private final Map<Location, SymmetricLocationLink> subfolders = new HashMap(); // encrypted locations
    private final Map<Location, SymmetricLocationLink> files = new HashMap();
    private final SymmetricLink subfolders2parent;
    private final SymmetricLink parent2meta;
    // write permissions => able to create files and subfolders
    private SortedMap<UserPublicKey, AsymmetricLink> sharingW2subfoldersW = new TreeMap(); // optional
    private final SymmetricLinkToPrivate subfoldersW2sign;
    private final UserPublicKey verifyW;

    public DirAccess(SymmetricKey meta, SymmetricKey parent, SymmetricKey files, SymmetricKey subfolders, Set<UserPublicKey> sharingR,
                     FileProperties metadata, KeyPair signingW, SymmetricKey subfoldersW, Set<UserPublicKey> sharingW, byte[] iv)
    {
        super(TYPE.DIR, meta.encrypt(metadata.serialize(), iv));
        this.subfolders2files = new SymmetricLink(subfolders, files, iv);
        this.subfolders2parent = new SymmetricLink(subfolders, parent, iv);
        this.parent2meta = new SymmetricLink(parent, meta, iv);
        if (sharingR != null)
            for (UserPublicKey key: sharingR)
                sharingR2subfoldersR.put(key, new AsymmetricLink(key, subfolders));
        subfoldersW2sign = new SymmetricLinkToPrivate(subfoldersW, signingW.getPrivate());
        verifyW = new UserPublicKey(signingW.getPublic());
        if (sharingW != null)
            for (UserPublicKey key: sharingW)
                sharingW2subfoldersW.put(key, new AsymmetricLink(key, subfoldersW));
    }

    public DirAccess(SymmetricKey subfoldersKey, FileProperties metadata, SymmetricKey subfoldersKeyW)
    {
        this(SymmetricKey.random(), SymmetricKey.random(), SymmetricKey.random(), subfoldersKey, null, metadata, SSL.generateKeyPair(), subfoldersKeyW, null, metadata.getIV());
    }

    public DirAccess(Map<UserPublicKey, AsymmetricLink> sharingR, byte[] s2f, byte[] s2p, byte[] p2m, byte[] metadata,
                     byte[] verr, byte[] sW2si, Map<UserPublicKey, AsymmetricLink> sharingW)
    {
        super(TYPE.DIR, metadata);
        sharingR2subfoldersR.putAll(sharingR);
        subfolders2files = new SymmetricLink(s2f);
        subfolders2parent = new SymmetricLink(s2p);
        parent2meta = new SymmetricLink(p2m);
        sharingW2subfoldersW.putAll(sharingW);
        this.subfoldersW2sign = new SymmetricLinkToPrivate(sW2si);
        this.verifyW = new UserPublicKey(verr);
    }

    public void serialize(DataOutput dout) throws IOException
    {
        super.serialize(dout);
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
        for (SymmetricLocationLink sub: subfolders.values())
        {
            Serialize.serialize(sub.serialize(), dout);
        }
        dout.writeInt(files.size());
        for (SymmetricLocationLink file: files.values())
        {
            Serialize.serialize(file.serialize(), dout);
        }
    }

    public static DirAccess deserialize(DataInput din, SymmetricKey ourSubfolders, byte[] metadata) throws IOException
    {
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
        DirAccess res =  new DirAccess(sharingR, s2f, s2p, p2m, metadata, verr, sW2si, sharingW);
        int subs = din.readInt();
        for (int i=0; i < subs; i++)
        {
            SymmetricLocationLink link = new SymmetricLocationLink(Serialize.deserializeByteArray(din, MAX_ELEMENT_SIZE));
            res.addSubFolder(ourSubfolders, link);
        }
        int files = din.readInt();
        for (int i=0; i < files; i++)
        {
            SymmetricLocationLink link = new SymmetricLocationLink(Serialize.deserializeByteArray(din, MAX_ELEMENT_SIZE));
            res.addFile(ourSubfolders, link);
        }
        return res;
    }

    public FileProperties getProps(SymmetricKey ourSubfolders) {
        SymmetricKey baseKey = parent2meta.target(subfolders2parent.target(ourSubfolders));
        try {
            return new FileProperties(new DataInputStream(new ByteArrayInputStream(baseKey.decrypt(encryptedMetadata, parent2meta.initializationVector()))));
        } catch (IOException e) {e.printStackTrace();return null;}
    }

    public void addSubFolder(Location location, SymmetricKey ourSubfolders, SymmetricKey targetSubfolders)
    {
        subfolders.put(location, new SymmetricLocationLink(ourSubfolders, targetSubfolders, location));
    }

    public void addFile(Location location, SymmetricKey ourSubfolders, SymmetricKey targetParent)
    {
        files.put(location, new SymmetricLocationLink(ourSubfolders, targetParent, location));
    }

    public void addSubFolder(SymmetricKey ourSubfolders, SymmetricLocationLink toTargetSubfolders) throws IOException
    {
        subfolders.put(toTargetSubfolders.targetLocation(ourSubfolders), toTargetSubfolders);
    }

    public void addFile(SymmetricKey ourSubfolders, SymmetricLocationLink toTargetParent) throws IOException
    {
        files.put(toTargetParent.targetLocation(ourSubfolders), toTargetParent);
    }

    public void addRSharingKey(UserPublicKey key, SymmetricKey subfoldersKey)
    {
        sharingR2subfoldersR.put(key, new AsymmetricLink(key, subfoldersKey));
    }

    public Collection<SymmetricLocationLink> getFiles()
    {
        return files.values();
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
