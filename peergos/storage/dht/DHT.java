package peergos.storage.dht;

import java.util.*;
import java.util.concurrent.*;

public interface DHT
{
    CompletableFuture<Optional<byte[]>> put(byte[] value, byte[] owner, byte[] writingKey, byte[] mapKey, byte[] proof);

    CompletableFuture<Integer> contains(byte[] key);

    CompletableFuture<byte[]> get(byte[] key);
}
