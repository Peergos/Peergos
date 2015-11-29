package peergos.storage.dht;

import org.ipfs.api.*;
import peergos.storage.merklebtree.*;

import java.util.*;
import java.util.concurrent.*;

public class IpfsDHT implements ContentAddressedStorage {
    private final IPFS ipfs;

    public IpfsDHT(IPFS ipfs) {
        this.ipfs = ipfs;
    }

    public byte[] get(byte[] key) {
        throw new IllegalStateException("Unimplemented!");
    }

    @Override
    public byte[] put(byte[] value) {
        throw new IllegalStateException("Unimplemented!");
    }

    @Override
    public void remove(byte[] key) {
        throw new IllegalStateException("Unimplemented!");
    }
}
