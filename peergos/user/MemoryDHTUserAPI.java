package peergos.user;


import org.ibex.nestedvm.util.Seekable;
import peergos.util.ByteArrayWrapper;

import java.util.HashMap;
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

    private final Map<ByteArrayWrapper, byte[]> chunks = new HashMap<ByteArrayWrapper, byte[]>();

    public  Future<Boolean> put(byte[] key, byte[] value, final String user, final byte[] sharingKey, final byte[] mapKey, final byte[] proof)
    {
        ByteArrayWrapper wrapper = new ByteArrayWrapper(key);

        chunks.put(new ByteArrayWrapper(mapKey), value);
        return new DummyFuture<Boolean>(true);
    }

    public  Future<Boolean> contains(byte[] key)
    {
        boolean contains = chunks.containsKey(new ByteArrayWrapper(key));
        return new DummyFuture<Boolean>(contains);
    }

    public  Future<byte[]> get(byte[] key){
        ByteArrayWrapper keyWrapper = new ByteArrayWrapper(key);
        return new DummyFuture<byte[]>(chunks.get(keyWrapper));
    }

    public  void shutdown(){}
}