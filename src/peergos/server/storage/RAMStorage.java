package peergos.server.storage;

import org.ipfs.api.Multihash;
import peergos.server.merklebtree.MerkleNode;
import peergos.util.*;

import java.security.*;
import java.util.*;

public class RAMStorage implements ContentAddressedStorage {
    private Map<Multihash, byte[]> storage = new HashMap<>();

    @Override
    public Multihash put(MerkleNode object) {
        byte[] value = object.data;
        byte[] hash = hash(value);
        Multihash multihash = new Multihash(Multihash.Type.sha2_256, hash);
        storage.put(multihash, value);
        return multihash;
    }

    @Override
    public byte[] get(Multihash key) {
        return storage.get(key);
    }

    @Override
    public void remove(Multihash key) {
        storage.remove(key);
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
