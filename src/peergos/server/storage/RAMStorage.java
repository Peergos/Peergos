package peergos.server.storage;

import org.ipfs.api.Multihash;
import peergos.server.merklebtree.MerkleNode;
import peergos.util.*;

import java.security.*;
import java.util.*;

public class RAMStorage implements ContentAddressedStorage {
    private Map<ByteArrayWrapper, byte[]> storage = new HashMap<>();

    @Override
    public Multihash put(MerkleNode object) {
        return new Multihash(put(object.data));
    }

        @Override
    public byte[] put(byte[] value) {
        byte[] hash = hash(value);
        storage.put(new ByteArrayWrapper(hash), value);
        return hash;
    }

    @Override
    public byte[] get(byte[] key) {
        return storage.get(new ByteArrayWrapper(key));
    }

    @Override
    public void remove(byte[] key) {
        storage.remove(new ByteArrayWrapper(key));
    }

    public void clear() {
        storage.clear();
    }

    public int size() {
        return storage.size();
    }

    @Override
    public boolean recursivePin(Multihash h) {
        return true;
    }

    @Override
    public boolean recursiveUnpin(Multihash h) {
        return true;
    }

    public static byte[] hash(byte[] input)
    {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(input);
            return md.digest();
        } catch (NoSuchAlgorithmException e)
        {
            throw new IllegalStateException("couldn't find hash algorithm");
        }
    }
}
