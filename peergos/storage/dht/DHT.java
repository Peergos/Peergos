package peergos.storage.dht;

import java.util.concurrent.*;

public interface DHT
{
    CompletableFuture<PutOffer> put(byte[] key, byte[] value, byte[] owner, byte[] writingKey, byte[] mapKey, byte[] proof);

    CompletableFuture<GetOffer> contains(byte[] key);

    CompletableFuture<GetOffer> get(byte[] key);
}
