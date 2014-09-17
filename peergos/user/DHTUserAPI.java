package peergos.user;

import java.util.concurrent.Future;

public abstract class DHTUserAPI
{
    public abstract Future put(byte[] key, byte[] value, final String user, final byte[] sharingKey, final byte[] mapKey, final byte[] proof);

    public abstract Future<Boolean> contains(byte[] key);

    public abstract Future<byte[]> get(byte[] key);

    public abstract void shutdown();
}
