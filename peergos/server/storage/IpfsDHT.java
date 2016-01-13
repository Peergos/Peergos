package peergos.server.storage;

import org.ipfs.api.*;
import peergos.server.merklebtree.MerkleNode;

import java.io.*;
import java.util.*;

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
    public byte[] get(byte[] key) {
        try {
            return ipfs.object.data(new Multihash(key));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Multihash put(MerkleNode object) {
        try {
            org.ipfs.api.MerkleNode data = ipfs.object.patch(EMPTY, "set-data", Optional.of(object.data), Optional.empty(), Optional.empty());
            Multihash current = data.hash;
            for (Map.Entry<String, Multihash> e : object.links.entrySet())
                current = ipfs.object.patch(current, "add-link", Optional.empty(), Optional.of(e.getKey()), Optional.of(e.getValue())).hash;
            return current;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public byte[] put(byte[] value) {
        return put(new MerkleNode(value)).toBytes();
    }

    @Override
    public void remove(byte[] key) {
        // delete this once IPFS object patch set-data is working
        if (true)
            return;
        try {
            ipfs.pin.rm(new Multihash(key));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static void main(String[] args) throws IOException {
        IpfsDHT dht = new IpfsDHT();
        byte[] val = new byte[57];
        new Random().nextBytes(val);
        byte[] key = dht.put(val);
        byte[] val2 = dht.get(key);
        boolean equals = Arrays.equals(val, val2);
        System.out.println(equals);
    }
}
