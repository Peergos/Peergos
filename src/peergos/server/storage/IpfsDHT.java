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
import java.util.stream.*;

public class IpfsDHT implements ContentAddressedStorage {
    private final IPFS ipfs;

    public IpfsDHT(IPFS ipfs) {
        this.ipfs = ipfs;
        try {
            // test connectivity
            ipfs.object._new(Optional.empty());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public IpfsDHT() {
        this(new IPFS(new MultiAddress("/ip4/127.0.0.1/tcp/5001")));
    }

    @Override
    public CompletableFuture<List<Multihash>> put(UserPublicKey writer, List<byte[]> blocks) {
        try {
            return CompletableFuture.completedFuture(ipfs.block.put(blocks))
                    .thenApply(nodes -> nodes.stream().map(n -> n.hash).collect(Collectors.toList()));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public CompletableFuture<Optional<MerkleNode>> get(Multihash hash) {
        try {
            byte[] raw = ipfs.block.get(hash);
            return CompletableFuture.completedFuture(Optional.of(MerkleNode.deserialize(raw)));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public CompletableFuture<Optional<byte[]>> getData(Multihash key) {
        try {
            return CompletableFuture.completedFuture(Optional.of(ipfs.object.data(key)));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public CompletableFuture<Multihash> put(UserPublicKey writer, MerkleNode object) {
        return put(writer, Arrays.asList(object.serialize())).thenApply(list -> list.get(0));
    }

    @Override
    public CompletableFuture<List<Multihash>> recursivePin(Multihash h) {
        try {
            List<Multihash> added = ipfs.pin.add(h);
            return CompletableFuture.completedFuture(added);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public CompletableFuture<List<Multihash>> recursiveUnpin(Multihash h) {
        try {
            List<Multihash> removed = ipfs.pin.rm(h, true);
            return CompletableFuture.completedFuture(removed);
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
        byte[] val2 = dht.getData(put).get().get();
        boolean equals = Arrays.equals(val1, val2);
        System.out.println(equals);
    }
}
