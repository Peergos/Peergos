package peergos.user;


import peergos.util.ByteArrayWrapper;

import java.util.Map;
import java.util.concurrent.*;

public class MemoryDHTUserAPI extends DHTUserAPI
{
    class DummyFuture<V> implements Future<V> {
        private final V value;
        public DummyFuture(V value){this.value = value;}
        public boolean cancel(boolean b){return false;}
        public boolean isCancelled(){return false;}
        public boolean isDone(){return true;}
        public V get(){return value;}
        public V get(long l, java.util.concurrent.TimeUnit timeUnit){return value;}
    }

    private final Map<ByteArrayWrapper, byte[]> chunks = new ConcurrentHashMap<>();

    public  Future<Boolean> put(byte[] key, byte[] value, final byte[] owner, final byte[] sharingKey, final byte[] mapKey, final byte[] proof)
    {
        chunks.put(new ByteArrayWrapper(key), value);
            return new DummyFuture<>(true);

    }

    public  Future<Boolean> contains(byte[] key)
    {
        boolean contains = chunks.containsKey(new ByteArrayWrapper(key));
        return new DummyFuture<>(contains);
    }

    public  Future<ByteArrayWrapper> get(byte[] key){
        return new DummyFuture<>(new ByteArrayWrapper(chunks.get(new ByteArrayWrapper(key))));
    }

    public  void shutdown(){}
}