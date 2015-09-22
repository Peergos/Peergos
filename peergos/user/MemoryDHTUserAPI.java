package peergos.user;


import peergos.util.ByteArrayWrapper;

import java.util.Map;
import java.util.concurrent.*;

public class MemoryDHTUserAPI implements DHTUserAPI
{
    private final Map<ByteArrayWrapper, byte[]> chunks = new ConcurrentHashMap<>();

    public CompletableFuture<Boolean> put(byte[] key, byte[] value, final byte[] owner, final byte[] sharingKey, final byte[] mapKey, final byte[] proof)
    {
        chunks.put(new ByteArrayWrapper(key), value);
        CompletableFuture<Boolean> fut = new CompletableFuture<>();
        fut.complete(true);
        return fut;
    }

    public CompletableFuture<Boolean> contains(byte[] key)
    {
        boolean contains = chunks.containsKey(new ByteArrayWrapper(key));
        CompletableFuture<Boolean> fut = new CompletableFuture<>();
        fut.complete(contains);
        return fut;
    }

    public CompletableFuture<ByteArrayWrapper> get(byte[] key){
        CompletableFuture<ByteArrayWrapper> fut = new CompletableFuture<>();
        fut.complete(new ByteArrayWrapper(chunks.get(new ByteArrayWrapper(key))));
        return fut;
    }

    public  void shutdown(){}
}