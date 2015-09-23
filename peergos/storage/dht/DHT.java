package peergos.storage.dht;

import java.util.concurrent.*;

public interface DHT
{
    CompletableFuture<Boolean> put(byte[] key, byte[] value, byte[] owner, byte[] writingKey, byte[] mapKey, byte[] proof);

    CompletableFuture<Integer> contains(byte[] key);

    CompletableFuture<byte[]> get(byte[] key);
}
