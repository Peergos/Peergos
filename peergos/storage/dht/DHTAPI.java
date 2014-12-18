package peergos.storage.dht;

import peergos.storage.net.HttpMessenger;
import peergos.util.ArrayOps;
import peergos.util.ByteArrayWrapper;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class DHTAPI
{
    private Router router;
    private ExecutorService executor = Executors.newFixedThreadPool(16);

    public DHTAPI(Router router)
    {
        this.router = router;
    }

    static class ErrorHandler implements OnFailure {    @Override public final void onFailure(Throwable e) {      e.printStackTrace();    } }
    static class PrintResult<T> implements OnSuccess<T> {    @Override public final void onSuccess(T t) {      System.out.println(t);    } }

    public static class PutHandler<T> implements OnSuccess<T> {
        private final byte[] key, value;
        private final PutHandlerCallback callback;
        private final Router router;

        public PutHandler(Router router, byte[] key, byte[] value, PutHandlerCallback callback) {
            this.key = key;
            this.value = value;
            this.callback = callback;
            this.router = router;
        }

        @Override public final void onSuccess(T obj)
        {
            PutOffer offer = (PutOffer) obj;
            try {
                if (offer.getTarget().external.equals(router.address().external)) {
                    if (router.storage.isWaitingFor(key))
                        router.storage.put(new ByteArrayWrapper(key), value);
                } else
                    HttpMessenger.putFragment(offer.getTarget().external, "/" + ArrayOps.bytesToHex(key), value);
                callback.callback(offer);
            } catch (IOException e)
            {
                // what do here?
                e.printStackTrace();
            }
        }
    }

    public static class GetHandler<T> implements OnSuccess<T> {
        private final byte[] key;
        private final GetHandlerCallback callback;
        private final Router router;

        public GetHandler(Router router, byte[] key, GetHandlerCallback callback) {
            this.key = key;
            this.callback = callback;
            this.router = router;
        }

        @Override public final void onSuccess(T obj)
        {
            GetOffer offer = (GetOffer) obj;
            if (offer.getTarget().external.equals(router.address().external))
                try {
                    offer.target = new NodeID(offer.target.id, new InetSocketAddress(InetAddress.getLocalHost(), offer.target.external.getPort()));
                } catch (UnknownHostException e) {}

            callback.callback(offer);
        }
    }

    // 256 bit key / 32 byte
    public Future<Object> put(byte[] key, byte[] value, String user, byte[] sharingKey, byte[] mapKey, byte[] proof, PutHandlerCallback onComplete, OnFailure onError)
    {
        assert(key.length == 32);
        Future<Object> fut = router.ask(new Message.PUT(key, value.length, user, sharingKey, mapKey, proof));
        OnSuccess success = new PutHandler(router, key, value, onComplete);
        FutureWrapper.followWith(fut, success, onError, executor);
        return fut;
    }

    public Future<Object> contains(byte[] key, GetHandlerCallback onComplete, OnFailure onError)
    {
        assert(key.length == 32);
        Future<Object> fut = router.ask(new Message.GET(key));
        OnSuccess success = new GetHandler(router, key, onComplete);
        FutureWrapper.followWith(fut, success, onError, executor);
        return fut;
    }

    public Future<Object> get(byte[] key, GetHandlerCallback onComplete, OnFailure onError)
    {
        assert(key.length == 32);
        Future<Object> fut = router.ask(new Message.GET(key));
        OnSuccess success = new GetHandler(router, key, onComplete);
        FutureWrapper.followWith(fut, success, onError, executor);
        return fut;
    }

    public Future<Object> put(byte[] key, byte[] value, String user, byte[] sharingKey, byte[] mapKey, byte[] proof, PutHandlerCallback onComplete)
    {
        return put(key, value, user, sharingKey, mapKey, proof, onComplete, new ErrorHandler());
    }

    public Future<Object> contains(byte[] key, GetHandlerCallback onComplete)
    {
        return contains(key, onComplete, new ErrorHandler());
    }

    public Future<Object> get(byte[] key, GetHandlerCallback onComplete)
    {
        return get(key, onComplete, new ErrorHandler());
    }
}
