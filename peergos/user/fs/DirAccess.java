package peergos.user.fs;

import peergos.crypto.*;
import peergos.util.*;

import java.io.*;
import java.util.*;
import java.util.stream.*;

public class DirAccess extends FileAccess
{
    // read permissions
    private SortedMap<UserPublicKey, AsymmetricLink> sharingR2subfoldersR; // optional
    private final SymmetricLink subfolders2files;
    private final List<SymmetricLocationLink> subfolders = new ArrayList<>(); // encrypted locations
    private final List<SymmetricLocationLink> files = new ArrayList<>();
    private final SymmetricLink subfolders2parent;

    public DirAccess(FileAccess base, SortedMap<UserPublicKey, AsymmetricLink> sharingR, byte[] s2f, byte[] s2p)
    {
        super(base);
        sharingR2subfoldersR = sharingR;
        subfolders2files = new SymmetricLink(s2f);
        subfolders2parent = new SymmetricLink(s2p);
    }

    public DirAccess(SortedMap<UserPublicKey, AsymmetricLink> sharingR, SymmetricLink s2f, SymmetricLink s2p, SymmetricLink p2m, byte[] metadata)
    {
        super(p2m, Collections.<UserPublicKey, AsymmetricLink>emptySortedMap(), metadata, Optional.empty());
        sharingR2subfoldersR = sharingR;
        subfolders2files = s2f;
        subfolders2parent = s2p;
    }

    public static DirAccess create(User owner, SymmetricKey meta, SymmetricKey parent, SymmetricKey files, SymmetricKey subfolders, Set<UserPublicKey> sharingR,
                                   FileProperties metadata, byte[] metaNonce)
    {
        TreeMap<UserPublicKey, AsymmetricLink> collect = sharingR.stream()
                .collect(Collectors.toMap(x -> (UserPublicKey) x, x -> new AsymmetricLink(owner, (UserPublicKey) x, subfolders), (a, b) -> a, () -> new TreeMap<>()));
        return new DirAccess(collect,
                new SymmetricLink(subfolders, files, subfolders.createNonce()),
                new SymmetricLink(subfolders, parent, subfolders.createNonce()),
                new SymmetricLink(parent, meta, parent.createNonce()),
                ArrayOps.concat(metaNonce, meta.encrypt(metadata.serialize(), metaNonce))
        );
    }

    public static DirAccess create(User owner, SymmetricKey subfoldersKey, FileProperties metadata)
    {
        SymmetricKey metaKey = SymmetricKey.random();
        return create(owner, metaKey, SymmetricKey.random(), SymmetricKey.random(), subfoldersKey, Collections.EMPTY_SET, metadata, metaKey.createNonce());
    }

    public Type getType() {
        return Type.Dir;
    }

    public void serialize(DataOutput dout) throws IOException
    {
        super.serialize(dout);
        // read access
        Serialize.serialize(subfolders2parent.serialize(), dout);
        Serialize.serialize(subfolders2files.serialize(), dout);
        dout.writeInt(sharingR2subfoldersR.size());
        for (UserPublicKey key: sharingR2subfoldersR.keySet()) {
            Serialize.serialize(key.getPublicKeys(), dout);
            Serialize.serialize(sharingR2subfoldersR.get(key).serialize(), dout);
        }
        // read subtree
        dout.writeInt(subfolders.size());
        for (SymmetricLocationLink sub: subfolders)
        {
            Serialize.serialize(sub.serialize(), dout);
        }
        dout.writeInt(files.size());
        for (SymmetricLocationLink file: files)
        {
            Serialize.serialize(file.serialize(), dout);
        }
    }

    public static DirAccess deserialize(FileAccess base, DataInput din) throws IOException
    {
        byte[] s2p = Serialize.deserializeByteArray(din, MAX_ELEMENT_SIZE);
        byte[] s2f = Serialize.deserializeByteArray(din, MAX_ELEMENT_SIZE);
        int readSharingKeys = din.readInt();
        SortedMap<UserPublicKey, AsymmetricLink> sharingR = new TreeMap<>();
        for (int i=0; i < readSharingKeys; i++)
            sharingR.put(new UserPublicKey(Serialize.deserializeByteArray(din, MAX_ELEMENT_SIZE)),
                    new AsymmetricLink(Serialize.deserializeByteArray(din, MAX_ELEMENT_SIZE)));
        DirAccess res =  new DirAccess(base, sharingR, s2f, s2p);
        int subs = din.readInt();
        for (int i=0; i < subs; i++)
        {
            SymmetricLocationLink link = new SymmetricLocationLink(Serialize.deserializeByteArray(din, MAX_ELEMENT_SIZE));
            res.addSubFolder(link);
        }
        int files = din.readInt();
        for (int i=0; i < files; i++)
        {
            SymmetricLocationLink link = new SymmetricLocationLink(Serialize.deserializeByteArray(din, MAX_ELEMENT_SIZE));
            res.addFile(link);
        }
        return res;
    }

    public void addSubFolder(Location location, SymmetricKey ourSubfolders, SymmetricKey targetSubfolders)
    {
        subfolders.add(new SymmetricLocationLink(ourSubfolders, targetSubfolders, location));
    }

    public void addFile(Location location, SymmetricKey ourSubfolders, SymmetricKey targetParent)
    {
        files.add(new SymmetricLocationLink(ourSubfolders, targetParent, location));
    }

    public void addSubFolder(SymmetricLocationLink toTargetSubfolders) throws IOException
    {
        subfolders.add(toTargetSubfolders);
    }

    public void addFile(SymmetricLocationLink toTargetParent) throws IOException
    {
        files.add(toTargetParent);
    }

    public void addRSharingKey(User owner, UserPublicKey key, SymmetricKey subfoldersKey)
    {
        sharingR2subfoldersR.put(key, new AsymmetricLink(owner, key, subfoldersKey));
    }

    public Collection<SymmetricLocationLink> getFiles()
    {
        return files;
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
