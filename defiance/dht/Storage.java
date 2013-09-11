package defiance.dht;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class Storage
{
    private final File root;
    private final long maxBytes;
    private final AtomicLong totalSize = new AtomicLong(0);
    private final AtomicLong promisedSize = new AtomicLong(0);
    private final Map<byte[], Integer> pending = new ConcurrentHashMap();
    private static final String DUMMY = "";
    private final Map<byte[], Integer> existing = new ConcurrentHashMap();

    public Storage(File root, long maxBytes) throws IOException
    {
        this.root = root;
        this.maxBytes = maxBytes;
        root.mkdirs();
    }

    public void start(int port) throws IOException
    {
        StorageServer.create(port, root, this);
    }

    public boolean isWaitingFor(byte[] key)
    {
        return pending.containsKey(key);
    }

    public boolean accept(byte[] key, int size)
    {
        if (existing.containsKey(key))
            return false; // don't overwrite old data for now (not sure this would ever be a problem with a cryptographic hash..
        boolean res = totalSize.get() + promisedSize.get() + size < maxBytes;
        if (res)
            promisedSize.getAndAdd(size);
        pending.put(key, size);
        return res;
    }

    public boolean put(byte[] key, byte[] value)
    {
        if (value.length != pending.get(key))
            return false;
        pending.remove(key);
        existing.put(key, value.length);
        // commit data
        try
        {
            new Fragment(key).write(value);
        } catch (IOException e)
        {
            e.printStackTrace();
            existing.remove(key);
            return false;
        }
        return true;
    }

    public boolean contains(byte[] key)
    {
        return existing.containsKey(key);
    }

    public int sizeOf(byte[] key)
    {
        if (!existing.containsKey(key))
            return 0;
        return existing.get(key);
    }

    public class Fragment
    {
        String name;

        public Fragment(byte[] key)
        {
            name = defiance.util.Arrays.bytesToHex(key);
        }

        public void write(byte[] data) throws IOException
        {
            OutputStream out = new FileOutputStream(new File(root, name));
            out.write(data);
            out.flush();
            out.close();
        }
    }
}
