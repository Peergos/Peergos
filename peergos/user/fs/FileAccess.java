package peergos.user.fs;

import peergos.crypto.*;
import peergos.util.ByteArrayWrapper;
import peergos.util.Serialize;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.*;
import java.util.stream.*;

public class FileAccess extends Metadata
{
    // read permissions
    private SortedMap<UserPublicKey, AsymmetricLink> sharingR2parent;
    private final SymmetricLink parent2meta;

    public static FileAccess create(UserPublicKey owner, Set<User> sharingR, SymmetricKey metaKey, SymmetricKey parentKey, FileProperties metadata,
                      List<ByteArrayWrapper> fragments, byte[] metaNonce)
    {
        SortedMap<UserPublicKey, AsymmetricLink> collect = sharingR.stream()
                .collect(Collectors.toMap(x -> new UserPublicKey(x.getPublicKeys()), x -> new AsymmetricLink(x,
                        owner, parentKey), (a, b) -> a, () -> new TreeMap<>()));
        return new FileAccess(metaKey.encrypt(metadata.serialize(), metaNonce), metaNonce, new SymmetricLink(parentKey, metaKey, parentKey.createNonce()),
                collect);
    }

    public FileAccess(byte[] m, byte[] p2m, SortedMap<UserPublicKey, AsymmetricLink> sharingR)
    {
        this(Arrays.copyOfRange(m, SymmetricKey.NONCE_BYTES, m.length), Arrays.copyOfRange(m, 0, SymmetricKey.NONCE_BYTES),
                new SymmetricLink(p2m), sharingR);
    }

    public FileAccess(byte[] meta, byte[] nonce, SymmetricLink p2m, SortedMap<UserPublicKey, AsymmetricLink> sharingR2parent) {
        super(TYPE.FILE, meta, nonce);
        this.parent2meta = p2m;
        this.sharingR2parent = sharingR2parent;
    }

    public static FileAccess create(UserPublicKey owner, SymmetricKey parentKey, FileProperties metadata, List<ByteArrayWrapper> fragments)
    {
        SymmetricKey metaKey = SymmetricKey.random();
        FileAccess fa = create(owner, null, metaKey, parentKey, metadata, fragments, metaKey.createNonce());
        fa.setFragments(fragments);
        return fa;
    }

    public void serialize(DataOutput dout) throws IOException
    {
        super.serialize(dout);
        Serialize.serialize(parent2meta.serialize(), dout);
        dout.writeInt(sharingR2parent.size());
        for (UserPublicKey key: sharingR2parent.keySet()) {
            Serialize.serialize(key.getPublicKeys(), dout);
            Serialize.serialize(sharingR2parent.get(key).serialize(), dout);
        }
    }

    public static FileAccess deserialize(DataInput din, byte[] metadata) throws IOException
    {
        byte[] p2m = Serialize.deserializeByteArray(din, MAX_ELEMENT_SIZE);
        int count = din.readInt();
        SortedMap<UserPublicKey, AsymmetricLink> sharingR = new TreeMap();
        for (int i=0; i < count; i++) {
            sharingR.put(new UserPublicKey(Serialize.deserializeByteArray(din, MAX_ELEMENT_SIZE)),
                    new AsymmetricLink(Serialize.deserializeByteArray(din, MAX_ELEMENT_SIZE)));
        }
        FileAccess res = new FileAccess(metadata, p2m, sharingR);
        return res;
    }

    public SymmetricKey getMetaKey(SymmetricKey parentKey)
    {
        return parent2meta.target(parentKey);
    }

    public SymmetricKey getParentKey(User sharingKey, UserPublicKey owner)
    {
        return sharingR2parent.get(sharingKey.toUserPublicKey()).target(sharingKey, owner);
    }

    public ChunkProperties getProps(SymmetricKey baseKey, byte[] metaNonce) {
        return FileProperties.deserialize(baseKey.decrypt(encryptedMetadata, metaNonce));
    }

    public FileProperties getProps(SymmetricKey parentKey)
    {
        return (FileProperties) getProps(parent2meta.target(parentKey), getMetaNonce());
    }
}
