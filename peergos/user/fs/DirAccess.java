package peergos.user.fs;

import peergos.crypto.*;
import peergos.util.Serialize;

import java.io.*;
import java.util.*;
import java.util.stream.*;

public class DirAccess extends Metadata
{
    // read permissions
    private SortedMap<UserPublicKey, AsymmetricLink> sharingR2subfoldersR; // optional
    private final SymmetricLink subfolders2files;
    private final Map<Location, SymmetricLocationLink> subfolders = new HashMap(); // encrypted locations
    private final Map<Location, SymmetricLocationLink> files = new HashMap();
    private final SymmetricLink subfolders2parent;
    private final SymmetricLink parent2meta;
    // write permissions => able to create files and subfolders
    private SortedMap<UserPublicKey, AsymmetricLink> sharingW2subfoldersW; // optional
    private final SymmetricLinkToPrivate subfoldersW2sign;
    private final UserPublicKey verifyW;

    public DirAccess(SortedMap<UserPublicKey, AsymmetricLink> sharingR, byte[] s2f, byte[] s2p, byte[] p2m, byte[] metadata,
                     byte[] verr, byte[] sW2si, SortedMap<UserPublicKey, AsymmetricLink> sharingW)
    {
        this(sharingR, new SymmetricLink(s2f), new SymmetricLink(s2p), new SymmetricLink(p2m), metadata, new UserPublicKey(verr), new SymmetricLinkToPrivate(sW2si), sharingW);
    }

    public DirAccess(SortedMap<UserPublicKey, AsymmetricLink> sharingR, SymmetricLink s2f, SymmetricLink s2p, SymmetricLink p2m, byte[] metadata,
                     UserPublicKey verr, SymmetricLinkToPrivate sW2si, SortedMap<UserPublicKey, AsymmetricLink> sharingW)
    {
        super(TYPE.DIR, Arrays.copyOfRange(metadata, SymmetricKey.NONCE_BYTES, metadata.length), Arrays.copyOfRange(metadata, 0, SymmetricKey.NONCE_BYTES));
        sharingR2subfoldersR = sharingR;
        subfolders2files = s2f;
        subfolders2parent = s2p;
        parent2meta = p2m;
        sharingW2subfoldersW = sharingW;
        this.subfoldersW2sign = sW2si;
        this.verifyW = verr;
    }

    public static DirAccess create(User owner, SymmetricKey meta, SymmetricKey parent, SymmetricKey files, SymmetricKey subfolders, Set<UserPublicKey> sharingR,
                                   FileProperties metadata, User signingW, SymmetricKey subfoldersW, Set<UserPublicKey> sharingW, byte[] metaNonce)
    {
        TreeMap<UserPublicKey, AsymmetricLink> collect = sharingR.stream()
                .collect(Collectors.toMap(x -> (UserPublicKey) x, x -> new AsymmetricLink(owner, (UserPublicKey) x, subfolders), (a, b) -> a, () -> new TreeMap<>()));
        TreeMap<UserPublicKey, AsymmetricLink> collect2 = sharingW.stream()
                .collect(Collectors.toMap(x -> (UserPublicKey) x, x -> new AsymmetricLink(owner, (UserPublicKey) x, subfoldersW), (a, b) -> a, () -> new TreeMap<>()));
        return new DirAccess(collect,
                new SymmetricLink(subfolders, files, subfolders.createNonce()),
                new SymmetricLink(subfolders, parent, subfolders.createNonce()),
                new SymmetricLink(parent, meta, parent.createNonce()),
                meta.encrypt(metadata.serialize(), metaNonce),
                signingW.toUserPublicKey(),
                new SymmetricLinkToPrivate(subfoldersW, signingW),
                collect2
        );
    }

    public static DirAccess create(User owner, SymmetricKey subfoldersKey, FileProperties metadata, SymmetricKey subfoldersKeyW)
    {
        SymmetricKey metaKey = SymmetricKey.random();
        return create(owner, metaKey, SymmetricKey.random(), SymmetricKey.random(), subfoldersKey, null, metadata,
                User.random(), subfoldersKeyW, null, metaKey.createNonce());
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
            Serialize.serialize(key.getPublicKeys(), dout);
            Serialize.serialize(sharingR2subfoldersR.get(key).serialize(), dout);
        }
        // write access
        Serialize.serialize(verifyW.getPublicKeys(), dout);
        Serialize.serialize(subfoldersW2sign.serialize(), dout);
        dout.writeInt(sharingW2subfoldersW.size());
        for (UserPublicKey key: sharingW2subfoldersW.keySet()) {
            Serialize.serialize(key.getPublicKeys(), dout);
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
        SortedMap<UserPublicKey, AsymmetricLink> sharingR = new TreeMap<>();
        for (int i=0; i < readSharingKeys; i++)
            sharingR.put(new UserPublicKey(Serialize.deserializeByteArray(din, MAX_ELEMENT_SIZE)),
                    new AsymmetricLink(Serialize.deserializeByteArray(din, MAX_ELEMENT_SIZE)));
        byte[] verr = Serialize.deserializeByteArray(din, MAX_ELEMENT_SIZE);
        byte[] sW2si = Serialize.deserializeByteArray(din, MAX_ELEMENT_SIZE);
        byte[] sW2sW = null;
        int writeSharingKeys = din.readInt();
        SortedMap<UserPublicKey, AsymmetricLink> sharingW = new TreeMap<>();
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
            return new FileProperties(new DataInputStream(new ByteArrayInputStream(baseKey.decrypt(encryptedMetadata, parent2meta.getNonce()))));
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

    public void addRSharingKey(User owner, UserPublicKey key, SymmetricKey subfoldersKey)
    {
        sharingR2subfoldersR.put(key, new AsymmetricLink(owner, key, subfoldersKey));
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

    public SymmetricKey getRSubfoldersKey(UserPublicKey owner, User sharingKey)
    {
        return sharingR2subfoldersR.get(sharingKey).target(sharingKey, owner);
    }
}
