package peergos.server.storage;

import peergos.shared.crypto.*;
import peergos.shared.ipfs.api.IPFS;
import peergos.shared.ipfs.api.MultiAddress;
import peergos.shared.ipfs.api.Multihash;
import peergos.shared.merklebtree.MerkleNode;
import peergos.shared.storage.ContentAddressedStorage;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;

public class IpfsDHT implements ContentAddressedStorage {
    private final IPFS ipfs;
    private final Multihash EMPTY;

    public IpfsDHT(IPFS ipfs) {
        this.ipfs = ipfs;
        try {
            EMPTY = ipfs.object._new(Optional.empty()).hash;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public IpfsDHT() {
        this(new IPFS(new MultiAddress("/ip4/127.0.0.1/tcp/5001")));
    }

    @Override
    public CompletableFuture<Optional<byte[]>> get(Multihash key) {
        try {
            return CompletableFuture.completedFuture(Optional.of(ipfs.object.data(key)));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public CompletableFuture<Multihash> put(UserPublicKey writer, MerkleNode object) {
        try {
            peergos.shared.ipfs.api.MerkleNode data = ipfs.object.patch(EMPTY, "set-data", Optional.of(object.data), Optional.empty(), Optional.empty());
            Multihash current = data.hash;
            for (Map.Entry<String, Multihash> e : object.links.entrySet())
                current = ipfs.object.patch(current, "add-link", Optional.empty(), Optional.of(e.getKey()), Optional.of(e.getValue())).hash;
            return CompletableFuture.completedFuture(current);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public CompletableFuture<Boolean> recursivePin(Multihash h) {
        try {
            List<Multihash> added = ipfs.pin.add(h);
            return CompletableFuture.completedFuture(added.contains(h));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public CompletableFuture<Boolean> recursiveUnpin(Multihash h) {
        try {
            List<Multihash> added = ipfs.pin.rm(h, true);
            return CompletableFuture.completedFuture(added.contains(h));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static void main(String[] args) throws Exception {
        IpfsDHT dht = new IpfsDHT();
        byte[] val1 = new byte[57];
        MerkleNode val = new MerkleNode(val1);
        new Random().nextBytes(val1);
        Multihash put = dht.put(UserPublicKey.createNull(), val).get();
        byte[] val2 = dht.get(put).get().get();
        boolean equals = Arrays.equals(val1, val2);
        System.out.println(equals);
    }
}
