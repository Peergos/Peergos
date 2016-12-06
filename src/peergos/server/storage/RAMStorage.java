package peergos.server.storage;

import peergos.shared.crypto.*;
import peergos.shared.ipfs.api.*;
import peergos.shared.merklebtree.MerkleNode;
import peergos.shared.storage.ContentAddressedStorage;

import java.io.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;

public class RAMStorage implements ContentAddressedStorage {
    private Map<Multihash, byte[]> storage = new HashMap<>();

    private final Set<Multihash> pinnedRoots = new HashSet<>();

    @Override
    public CompletableFuture<Multihash> _new(UserPublicKey writer) {
        return put(writer, new MerkleNode(new byte[0]));
    }

    @Override
    public CompletableFuture<Multihash> setData(UserPublicKey writer, Multihash object, byte[] data) {
        return put(writer, getAndParseObject(object).setData(data));
    }

    @Override
    public CompletableFuture<Multihash> addLink(UserPublicKey writer, Multihash object, String label, Multihash linkTarget) {
        return put(writer, getAndParseObject(object).addLink(label, linkTarget));
    }

    @Override
    public CompletableFuture<Optional<MerkleNode>> getObject(Multihash object) {
        return CompletableFuture.completedFuture(Optional.of(getAndParseObject(object)));
    }

    public MerkleNode getAndParseObject(Multihash hash) {
        if (!storage.containsKey(hash))
            throw new IllegalStateException("Hash not present! "+ hash);
        try {
            return MerkleNode.deserialize(storage.get(hash));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public CompletableFuture<Multihash> put(UserPublicKey writer, MerkleNode object) {
        byte[] value = object.serialize();
        byte[] hash = hash(value);
        Multihash multihash = new Multihash(Multihash.Type.sha2_256, hash);
        storage.put(multihash, value);
        return CompletableFuture.completedFuture(multihash);
    }

    @Override
    public CompletableFuture<Optional<byte[]>> getData(Multihash key) {
        if (!storage.containsKey(key))
            return CompletableFuture.completedFuture(Optional.empty());
        return CompletableFuture.completedFuture(Optional.of(getAndParseObject(key).data));
    }

    public void clear() {
        storage.clear();
    }

    public int size() {
        return storage.size();
    }

    @Override
    public CompletableFuture<List<Multihash>> recursivePin(Multihash h) {
        return CompletableFuture.completedFuture(Arrays.asList(h));
    }

    @Override
    public CompletableFuture<List<Multihash>> recursiveUnpin(Multihash h) {
        return CompletableFuture.completedFuture(Arrays.asList(h));
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
