package peergos.storage.dht;

import org.ipfs.api.*;
import peergos.storage.merklebtree.*;

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

    public byte[] get(byte[] key) {
        try {
//            return ipfs.object.get(new Multihash(key)).data.get();
            return ipfs.block.get(new Multihash(key));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public byte[] put(byte[] value) {
        try {
//            return ipfs.object.patch(EMPTY, "set-data", Optional.of(value), Optional.empty(), Optional.empty());
            return ipfs.block.put(Arrays.asList(value)).get(0).hash.toBytes();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void remove(byte[] key) {
//        try {
//            ipfs.pin.rm(new Multihash(key));
//        } catch (IOException e) {
//            throw new RuntimeException(e);
//        }
    }
}
