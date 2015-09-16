package peergos.storage.dht;

import java.util.concurrent.*;

public interface DHT
{
    Future<Object> put(byte[] key, byte[] value, byte[] owner, byte[] writingKey, byte[] mapKey, byte[] proof, PutHandlerCallback onComplete, OnFailure onError);

    Future<Object> contains(byte[] key, GetHandlerCallback onComplete, OnFailure onError);

    Future<Object> get(byte[] key, GetHandlerCallback onComplete, OnFailure onError);
}
