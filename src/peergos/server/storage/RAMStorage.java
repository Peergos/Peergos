package peergos.server.storage;

import peergos.shared.crypto.*;
import peergos.shared.ipfs.api.*;
import peergos.shared.merklebtree.MerkleNode;
import peergos.shared.storage.ContentAddressedStorage;

import java.security.*;
import java.util.*;
import java.util.concurrent.*;

public class RAMStorage implements ContentAddressedStorage {
    private Map<Multihash, byte[]> storage = new HashMap<>();

    @Override
    public CompletableFuture<Multihash> put(UserPublicKey writer, MerkleNode object) {
        byte[] value = object.data;
        byte[] hash = hash(value);
        Multihash multihash = new Multihash(Multihash.Type.sha2_256, hash);
        storage.put(multihash, value);
        return CompletableFuture.completedFuture(multihash);
    }

    @Override
    public CompletableFuture<Optional<byte[]>> get(Multihash key) {
        if (!storage.containsKey(key))
            return CompletableFuture.completedFuture(Optional.empty());
        return CompletableFuture.completedFuture(Optional.of(storage.get(key)));
    }

    public void clear() {
        storage.clear();
    }

    public int size() {
        return storage.size();
    }

    @Override
    public CompletableFuture<Boolean> recursivePin(Multihash h) {
        return CompletableFuture.completedFuture(true);
    }

    @Override
    public CompletableFuture<Boolean> recursiveUnpin(Multihash h) {
        return CompletableFuture.completedFuture(true);
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
