package peergos.storage.dht;

import org.ipfs.api.*;
import peergos.storage.merklebtree.*;
import peergos.storage.merklebtree.MerkleNode;

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
            // delete this once IPFS object patch set-data is working
            if (true)
                return ipfs.block.get(new Multihash(key));
            return ipfs.object.get(new Multihash(key)).data.get();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Multihash put(MerkleNode object) {
        // delete this once IPFS object patch set-data is working
        if (true)
            try {
                return ipfs.block.put(Arrays.asList(object.data)).get(0).hash;
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        try {
            org.ipfs.api.MerkleNode data = ipfs.object.patch(EMPTY, "set-data", Optional.of(object.data), Optional.empty(), Optional.empty());
            Multihash current = data.hash;
            for (Map.Entry<String, Multihash> e : object.links.entrySet())
                current = ipfs.object.patch(current, "add-link", Optional.empty(), Optional.of(""), Optional.of(e.getValue())).hash;
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
}
