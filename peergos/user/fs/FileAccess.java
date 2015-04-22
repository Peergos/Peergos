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

    public FileAccess(UserPublicKey owner, Set<User> sharingR, SymmetricKey metaKey, SymmetricKey parentKey, FileProperties metadata, List<ByteArrayWrapper> fragments, byte[] metaNonce)
    {
        super(TYPE.FILE, metaKey.encrypt(metadata.serialize(), metaNonce), metaNonce);
        setFragments(fragments);
        this.parent2meta = new SymmetricLink(parentKey, metaKey, parentKey.createNonce());
        if (sharingR != null) {
            for (User key: sharingR)
                sharingR2parent.put(new UserPublicKey(key.getPublicKeys()), new AsymmetricLink(key, owner,  parentKey));
        }
    }

    public static FileAccess create(UserPublicKey owner, SymmetricKey parentKey, FileProperties metadata, List<ByteArrayWrapper> fragments)
    {
        SymmetricKey metaKey = SymmetricKey.random();
        return new FileAccess(owner, null, metaKey, parentKey, metadata, fragments, metaKey.createNonce());
    }

    public FileAccess(byte[] m, byte[] p2m, Map<UserPublicKey, AsymmetricLink> sharingR)
    {
        super(TYPE.FILE, Arrays.copyOfRange(m, TweetNaCl.SECRETBOX_NONCE_BYTES, m.length), Arrays.copyOfRange(m, 0, TweetNaCl.SECRETBOX_NONCE_BYTES));
        parent2meta = new SymmetricLink(p2m);
        sharingR2parent.putAll(sharingR);
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
        Map<UserPublicKey, AsymmetricLink> sharingR = new HashMap();
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

    public ChunkProperties getProps(SymmetricKey baseKey, byte[] iv) {
        return FileProperties.deserialize(baseKey.decrypt(encryptedMetadata, iv));
    }

    public FileProperties getProps(SymmetricKey parentKey)
    {
        return (FileProperties) getProps(parent2meta.target(parentKey), parent2meta.getNonce());
    }
}
