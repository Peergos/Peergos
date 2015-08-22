package peergos.storage;

import java.io.*;

public interface Storage
{

    long remainingSpace();

    boolean put(String key, byte[] value) throws IOException;

    boolean remove(String key) throws IOException;

    byte[] get(String key) throws IOException;

    boolean contains(String key) throws IOException;

    int sizeOf(String key);
}
