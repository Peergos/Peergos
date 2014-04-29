package peergos.storage.dht;

import static akka.pattern.Patterns.ask;
import akka.actor.ActorRef;

import akka.actor.ActorSystem;
import akka.dispatch.OnFailure;
import akka.dispatch.OnSuccess;
import peergos.storage.net.HttpsMessenger;
import peergos.util.Arrays;
import scala.concurrent.Future;

import java.io.IOException;

public class DHTAPI
{
    private ActorRef router;
    private ActorSystem system;

    public DHTAPI(ActorSystem system, ActorRef router)
    {
        this.router = router;
        this.system = system;
    }

    static class ErrorHandler extends OnFailure {    @Override public final void onFailure(Throwable e) {      e.printStackTrace();    } }
    static class PrintResult<T> extends OnSuccess<T> {    @Override public final void onSuccess(T t) {      System.out.println(t);    } }

    public static class PutHandler<T> extends OnSuccess<T> {
        private final byte[] key, value;
        private final PutHandlerCallback callback;

        public PutHandler(byte[] key, byte[] value, PutHandlerCallback callback) {
            this.key = key;
            this.value = value;
            this.callback = callback;
        }

        @Override public final void onSuccess(T obj)
        {
            PutOffer offer = (PutOffer) obj;
            try {
                HttpsMessenger.putFragment(offer.getTarget().addr, offer.getTarget().port, "/" + Arrays.bytesToHex(key), value);
                callback.callback(offer);
            } catch (IOException e)
            {
                // what do here?
                e.printStackTrace();
            }
        }
    }

    public static class GetHandler<T> extends OnSuccess<T> {
        private final byte[] key;
        private final GetHandlerCallback callback;

        public GetHandler(byte[] key, GetHandlerCallback callback) {
            this.key = key;
            this.callback = callback;
        }

        @Override public final void onSuccess(T obj)
        {
            GetOffer offer = (GetOffer) obj;
            callback.callback(offer);
        }
    }

    // 256 bit key / 32 byte
    public Future<Object> put(byte[] key, byte[] value, String user, byte[] sharingKey, byte[] signedHashOfKey, PutHandlerCallback onComplete, OnFailure onError)
    {
        assert(key.length == 32);
        Future<Object> fut = ask(router, new MessageMailbox(new Message.PUT(key, value.length, user, sharingKey, signedHashOfKey)), 20000);
        fut.onSuccess(new PutHandler(key, value, onComplete), system.dispatcher());
        fut.onFailure(onError, system.dispatcher());
        return fut;
    }

    public Future<Object> contains(byte[] key, GetHandlerCallback onComplete, OnFailure onError)
    {
        assert(key.length == 32);
        Future<Object> fut = ask(router, new MessageMailbox(new Message.GET(key)), 20000);
        fut.onSuccess(new GetHandler(key, onComplete), system.dispatcher());
        fut.onFailure(onError, system.dispatcher());
        return fut;
    }

    public Future<Object> get(byte[] key, GetHandlerCallback onComplete, OnFailure onError)
    {
        assert(key.length == 32);
        Future<Object> fut = ask(router, new MessageMailbox(new Message.GET(key)), 20000);
        fut.onSuccess(new GetHandler(key, onComplete), system.dispatcher());
        fut.onFailure(onError, system.dispatcher());
        return fut;
    }

    public Future<Object> put(byte[] key, byte[] value, String user, byte[] sharingKey, byte[] signedHashOfKey, PutHandlerCallback onComplete)
    {
        return put(key, value, user, sharingKey, signedHashOfKey, onComplete, new ErrorHandler());
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
