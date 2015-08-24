package peergos.storage;

import java.io.*;
import java.util.concurrent.atomic.*;

public class DiskStorage implements Storage
{
    private final File root;
    private final AtomicLong remainingSpace = new AtomicLong(0);

    public DiskStorage(File root, long maxBytes) throws IOException
    {
        this.root = root;
        remainingSpace.addAndGet(maxBytes);
        if (!root.exists())
            root.mkdirs();
        else {
            for (File f: root.listFiles())
            {
                if (f.isDirectory())
                    continue;
                remainingSpace.addAndGet(f.length());
            }
        }
    }

    public long remainingSpace() {
        return remainingSpace.get();
    }

    public File getRoot()
    {
        return root;
    }

    public boolean put(String key, byte[] value) throws IOException
    {
        new Fragment(key).write(value);
        remainingSpace.getAndAdd(-value.length);
        return true;
    }

    public boolean remove(String key) throws IOException {
        File file = new File(root, key);
        remainingSpace.getAndAdd(file.length());
        return file.delete();
    }

    public byte[] get(String key)
    {
        try {
            return new Fragment(key).read();
        } catch (IOException e)
        {
            e.printStackTrace();

            return null;
        }
    }

    public boolean contains(String key)
    {
        return new File(root, key).exists();
    }

    public int sizeOf(String key)
    {
        File f = new File(root, key);
        if (!f.exists())
            return 0;
        return (int) f.length();
    }

    public class Fragment
    {
        String name;

        public Fragment(String name)
        {
            this.name = name;
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
}
