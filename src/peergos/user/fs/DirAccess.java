package peergos.user.fs;

import peergos.crypto.*;
import peergos.crypto.asymmetric.PublicBoxingKey;
import peergos.crypto.asymmetric.SecretBoxingKey;
import peergos.crypto.symmetric.SymmetricKey;
import peergos.util.*;

import java.io.*;
import java.util.*;
import java.util.stream.*;

public class DirAccess extends FileAccess
{
    // read permissions
    private SortedMap<PublicBoxingKey, AsymmetricLink> sharingR2subfoldersR; // optional
    private final SymmetricLink subfolders2files;
    private final SymmetricLink subfolders2parent;
    private final List<SymmetricLocationLink> subfolders = new ArrayList<>(); // encrypted locations
    private final List<SymmetricLocationLink> files = new ArrayList<>();

    public DirAccess(FileAccess base, SortedMap<PublicBoxingKey, AsymmetricLink> sharingR, byte[] s2f, byte[] s2p)
    {
        super(base);
        sharingR2subfoldersR = sharingR;
        subfolders2files = new SymmetricLink(s2f);
        subfolders2parent = new SymmetricLink(s2p);
    }

    public DirAccess(SortedMap<PublicBoxingKey, AsymmetricLink> sharingR, SymmetricLink s2f, SymmetricLink s2p, SymmetricLink p2m, byte[] metadata, SymmetricLocationLink parentLocationLink)
    {
        super(p2m, metadata, Optional.empty(), parentLocationLink);
        sharingR2subfoldersR = sharingR;
        subfolders2files = s2f;
        subfolders2parent = s2p;
    }

    public static DirAccess create(SecretBoxingKey owner, SymmetricKey meta, SymmetricKey parent, SymmetricKey files, SymmetricKey subfolders, Set<PublicBoxingKey> sharingR,
                                   FileProperties metadata, byte[] metaNonce, Location location, SymmetricKey rootParentKey)
    {
    	SymmetricLocationLink parentLocationLink = location != null ?  new SymmetricLocationLink(parent, rootParentKey, location) : null;
    	
        TreeMap<PublicBoxingKey, AsymmetricLink> collect = sharingR.stream()
                .collect(Collectors.toMap(x -> (PublicBoxingKey) x, x -> new AsymmetricLink(owner, (PublicBoxingKey) x, subfolders), (a, b) -> a, () -> new TreeMap<>()));
        return new DirAccess(collect,
                new SymmetricLink(subfolders, files, subfolders.createNonce()),
                new SymmetricLink(subfolders, parent, subfolders.createNonce()),
                new SymmetricLink(parent, meta, parent.createNonce()),
                ArrayOps.concat(metaNonce, meta.encrypt(metadata.serialize(), metaNonce))
                , parentLocationLink);
    }

    public static DirAccess createRoot(SecretBoxingKey owner, SymmetricKey subfoldersKey, FileProperties metadata)
    {
        return create(owner, subfoldersKey, metadata, null, null);
    }
    
    public static DirAccess create(SecretBoxingKey owner, SymmetricKey subfoldersKey, FileProperties metadata, Location location, SymmetricKey rootParentKey)
    {
        SymmetricKey metaKey = SymmetricKey.random();
        return create(owner, metaKey, SymmetricKey.random(), SymmetricKey.random(), subfoldersKey, Collections.EMPTY_SET, metadata, metaKey.createNonce(), location, rootParentKey);
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
        for (PublicBoxingKey key: sharingR2subfoldersR.keySet()) {
            Serialize.serialize(key.serialize(), dout);
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

    public static DirAccess deserialize(FileAccess base, DataInputStream din) throws IOException
    {
        byte[] s2p = Serialize.deserializeByteArray(din, MAX_ELEMENT_SIZE);
        byte[] s2f = Serialize.deserializeByteArray(din, MAX_ELEMENT_SIZE);
        int readSharingKeys = din.readInt();
        SortedMap<PublicBoxingKey, AsymmetricLink> sharingR = new TreeMap<>();
        for (int i=0; i < readSharingKeys; i++)
            sharingR.put(PublicBoxingKey.deserialize(din),
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

    public void addRSharingKey(SecretBoxingKey owner, PublicBoxingKey key, SymmetricKey subfoldersKey)
    {
        sharingR2subfoldersR.put(key, new AsymmetricLink(owner, key, subfoldersKey));
    }

    public Collection<SymmetricLocationLink> getFiles()
    {
        return files;
    }

    public Collection<SymmetricLocationLink> getSubfolders()
    {
        return subfolders;
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
