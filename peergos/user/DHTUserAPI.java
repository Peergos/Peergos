package peergos.user;

import scala.concurrent.Future;

public abstract class DHTUserAPI
{
    public abstract Future put(byte[] key, byte[] value, final String user, final byte[] sharingKey, final byte[] mapKey);

    public abstract Future<Boolean> contains(byte[] key);

    public abstract Future<byte[]> get(byte[] key);
}
