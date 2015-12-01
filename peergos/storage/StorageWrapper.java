package peergos.storage;

import peergos.util.*;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class StorageWrapper
{
    private final AtomicLong promisedSize = new AtomicLong(0);
    private final Map<ByteArrayWrapper, Integer> pending = new ConcurrentHashMap();
    private final Storage storage;

    public StorageWrapper(Storage storage) throws IOException {
        this.storage = storage;
    }

    public boolean isWaitingFor(byte[] key) {
        return pending.containsKey(new ByteArrayWrapper(key));
    }

    public boolean accept(ByteArrayWrapper fragmentHash, int size) {
        try {
            if (storage.contains(fragmentHash.toString())) {
                pending.put(fragmentHash, size);
                return true;
            }
        } catch (IOException e) {
            return false;
        }
        boolean res = storage.remainingSpace() - promisedSize.get() > size;
        if (res)
            promisedSize.getAndAdd(size);
        else
            System.out.println("Storage rejecting fragment store: Not within size limits: remaining="+storage.remainingSpace() + ", promised="+promisedSize.get() + ", size="+size);
        pending.put(fragmentHash, size);
        return res;
    }

    public boolean put(ByteArrayWrapper key, byte[] value) {
        if (value.length != pending.get(key))
            return false;
        pending.remove(key);
        // commit data
        try
        {
            if (storage.contains(key.toString()))
                return true;
            storage.put(key.toString(), value);
        } catch (IOException e)
        {
            e.printStackTrace();
            return false;
        }
        promisedSize.getAndAdd(-value.length);
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
}
