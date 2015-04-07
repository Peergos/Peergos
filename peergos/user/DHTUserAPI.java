package peergos.user;

import peergos.util.*;

import java.util.concurrent.Future;

public abstract class DHTUserAPI
{
    public abstract Future<Boolean> put(byte[] key, byte[] value, final String user, final byte[] sharingKey, final byte[] mapKey, final byte[] proof);

    public abstract Future<Boolean> contains(byte[] key);

    public abstract Future<ByteArrayWrapper> get(byte[] key);

    public abstract void shutdown();
}
