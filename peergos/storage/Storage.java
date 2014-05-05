package peergos.storage;

import peergos.corenode.AbstractCoreNode;
import peergos.corenode.HTTPCoreNode;
import peergos.crypto.SSL;
import peergos.util.ArrayOps;
import peergos.util.ByteArrayWrapper;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URL;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class Storage
{
    private final File root;
    private final long maxBytes;
    private final AtomicLong totalSize = new AtomicLong(0);
    private final AtomicLong promisedSize = new AtomicLong(0);
    private final Map<ByteArrayWrapper, Integer> pending = new ConcurrentHashMap();
    private final Map<ByteArrayWrapper, Integer> existing = new ConcurrentHashMap();
    private final Map<ByteArrayWrapper, Credentials> credentials = new ConcurrentHashMap();
    private AbstractCoreNode coreAPI = new HTTPCoreNode(new URL("http://"+ SSL.getCommonName(SSL.getCoreServerCertificates()[0])+":"+AbstractCoreNode.PORT+"/"));
    private final InetSocketAddress us;
    private final String donor;

    public Storage(String donor, File root, long maxBytes, InetSocketAddress us) throws IOException
    {
        this.root = root;
        this.maxBytes = maxBytes;
        this.us = us;
        this.donor = donor;
        if (root.exists())
        {
            for (File f: root.listFiles())
            {
                if (f.isDirectory())
                    continue;
                ByteArrayWrapper name = new ByteArrayWrapper(ArrayOps.hexToBytes(f.getName()));
                Fragment frag = new Fragment(name);
                int size = frag.getSize();
                existing.put(name, size);
            }
        }
        else
            root.mkdirs();
    }

    public File getRoot()
    {
        return root;
    }

    public boolean isWaitingFor(byte[] key)
    {
        return pending.containsKey(new ByteArrayWrapper(key));
    }

    public boolean accept(ByteArrayWrapper key, int size, String owner, byte[] sharingKey, byte[] mapKey, byte[] proof)
    {
        if (existing.containsKey(key))
            return false; // don't overwrite old data for now (not sure this would ever be a problem with a cryptographic hash..
        if (!coreAPI.isFragmentAllowed(owner, sharingKey, mapKey, key.data))
            return false;
        boolean res = totalSize.get() + promisedSize.get() + size < maxBytes;
        if (res)
            promisedSize.getAndAdd(size);
        pending.put(key, size);
        credentials.put(key, new Credentials(owner, sharingKey, proof));
        return res;
    }

    public boolean put(ByteArrayWrapper key, byte[] value)
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
        Credentials cred = credentials.remove(key);
        coreAPI.registerFragmentStorage(donor, us, cred.owner, cred.sharingKey, key.data, cred.proof);
        return true;
    }

    public byte[] get(ByteArrayWrapper key)
    {
        try {
            return new Fragment(key).read();
        } catch (IOException e)
        {
            e.printStackTrace();
            existing.remove(key);
            return null;
        }
    }

    public boolean contains(ByteArrayWrapper key)
    {
        return existing.containsKey(key);
    }

    public int sizeOf(ByteArrayWrapper key)
    {
        if (!existing.containsKey(key))
            return 0;
        return existing.get(key);
    }

    public class Fragment
    {
        String name;

        public Fragment(ByteArrayWrapper key)
        {
            name = ArrayOps.bytesToHex(key.data);
        }

        public int getSize()
        {
            return (int)new File(root, name).length(); // all fragments are WELL under 4GiB!
        }

        public void write(byte[] data) throws IOException
        {
            OutputStream out = new FileOutputStream(new File(root, name));
            out.write(data);
            out.flush();
            out.close();
        }

        public byte[] read() throws IOException{
            ByteArrayOutputStream bout = new ByteArrayOutputStream();
            InputStream in = new FileInputStream(new File(root, name));
            byte[] buf = new byte[4096];
            int read;
            while ((read = in.read(buf, 0, buf.length)) > 0)
                bout.write(buf, 0, read);
            return bout.toByteArray();
        }
    }

    public static class Credentials
    {
        public String owner;
        public byte[] sharingKey;
        public byte[] proof;

        Credentials(String owner, byte[] sharingKey, byte[] proof)
        {
            this.owner = owner;
            this.sharingKey = sharingKey;
            this.proof = proof;
        }
    }
}
