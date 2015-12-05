package peergos.storage.dht;

import org.ipfs.api.*;
import peergos.storage.merklebtree.*;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;

public class IpfsDHT implements ContentAddressedStorage {
    private final IPFS ipfs;

    public IpfsDHT(IPFS ipfs) {
        this.ipfs = ipfs;
    }

    public IpfsDHT() {
        this(new IPFS(new MultiAddress("/ip4/127.0.0.1/tcp/5001")));
    }

    public byte[] get(byte[] key) {
        try {
            return ipfs.block.get(new Multihash(key));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public byte[] put(byte[] value) {
        try {
            return ipfs.block.put(Arrays.asList(value)).get(0).hash.toBytes();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void remove(byte[] key) {
        System.out.println("Unimplemented IPFS remove!");
    }
}
