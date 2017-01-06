package peergos.server.storage;

import peergos.shared.cbor.*;
import peergos.shared.crypto.*;
import peergos.shared.ipfs.api.*;
import peergos.shared.merklebtree.MerkleNode;
import peergos.shared.storage.ContentAddressedStorage;

import java.io.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

public class RAMStorage implements ContentAddressedStorage {
    private Map<Multihash, byte[]> storage = new HashMap<>();

    private final Set<Multihash> pinnedRoots = new HashSet<>();

    @Override
    public CompletableFuture<List<Multihash>> put(UserPublicKey writer, List<byte[]> blocks) {
        return CompletableFuture.completedFuture(blocks.stream()
                .map(b -> {
                    byte[] hash = hash(b);
                    Multihash multihash = new Multihash(Multihash.Type.sha2_256, hash);
                    storage.put(multihash, b);
                    return multihash;
                }).collect(Collectors.toList()));
    }

    @Override
    public CompletableFuture<Optional<CborObject>> get(Multihash object) {
        return CompletableFuture.completedFuture(Optional.of(getAndParseObject(object)));
    }

    public CborObject getAndParseObject(Multihash hash) {
        if (!storage.containsKey(hash))
            throw new IllegalStateException("Hash not present! "+ hash);
        return CborObject.fromByteArray(storage.get(hash));
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
