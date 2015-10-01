package peergos.storage;

import peergos.corenode.*;
import peergos.crypto.*;
import peergos.util.*;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class StorageWrapper
{
    private final AtomicLong promisedSize = new AtomicLong(0);
    private final Map<ByteArrayWrapper, Integer> pending = new ConcurrentHashMap();
    private final Map<ByteArrayWrapper, Credentials> credentials = new ConcurrentHashMap();
    private final UserPublicKey donor;
    private final InetSocketAddress us;
    public CoreNode coreAPI = HTTPCoreNode.getInstance();
    private final Storage storage;

    public StorageWrapper(Storage storage, UserPublicKey donor, InetSocketAddress us) throws IOException {
        this.storage = storage;
        this.donor = donor;
        this.us = us;
    }

    public boolean isWaitingFor(byte[] key) {
        return pending.containsKey(new ByteArrayWrapper(key));
    }

    public boolean accept(ByteArrayWrapper fragmentHash, int size, UserPublicKey owner, byte[] sharingKey, byte[] mapKey, byte[] proof) {
        try {
            if (storage.contains(fragmentHash.toString()))
                return false; // don't overwrite old data for now (not sure this would ever be a problem with a cryptographic hash..
        } catch (IOException e) {
            return false;
        }
        boolean res = storage.remainingSpace() - promisedSize.get() > size;
        if (res)
            promisedSize.getAndAdd(size);
        else
            System.out.println("Storage rejecting fragment store: Not within size limits: remaining="+storage.remainingSpace() + ", promised="+promisedSize.get() + ", size="+size);
        pending.put(fragmentHash, size);
        credentials.put(fragmentHash, new Credentials(owner.getPublicKeys(), sharingKey, proof));
        return res;
    }

    public boolean put(ByteArrayWrapper key, byte[] value) {
        if (value.length != pending.get(key))
            return false;
        pending.remove(key);
        // commit data
        try
        {
            storage.put(key.toString(), value);
        } catch (IOException e)
        {
            e.printStackTrace();
            return false;
        }
        promisedSize.getAndAdd(-value.length);
        Credentials cred = credentials.remove(key);
        return true;
    }

    public byte[] get(ByteArrayWrapper key) {
        try {
            return storage.get(key.toString());
        } catch (IOException e) {
            return null;
        }
    }

    public boolean contains(ByteArrayWrapper key) {
        try {
            return storage.contains(key.toString());
        } catch (IOException e) {
            return false;
        }
    }

    public int sizeOf(ByteArrayWrapper key) {
        return storage.sizeOf(key.toString());
    }

    public static class Credentials
    {
        public byte[] owner;
        public byte[] sharingKey;
        public byte[] proof;

        Credentials(byte[] owner, byte[] sharingKey, byte[] proof)
        {
            this.owner = owner;
            this.sharingKey = sharingKey;
            this.proof = proof;
        }
    }
}
