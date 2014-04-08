package peergos.dht;

import static akka.pattern.Patterns.ask;
import akka.actor.ActorRef;

import akka.actor.ActorSystem;
import akka.dispatch.OnFailure;
import akka.dispatch.OnSuccess;
import peergos.net.HTTPSMessenger;
import peergos.util.Arrays;
import scala.concurrent.Future;

import java.io.IOException;

public class API
{
    private ActorRef router;
    private ActorSystem system;

    public API(ActorSystem system, ActorRef router)
    {
        this.router = router;
        this.system = system;
    }

    // core node API
    public void createUser(byte[] username, byte[] publicKey, PublicKeyPutHandlerCallback onComplete, ErrorHandler onError)
    {
//        PublicKeyPutHandler handler = new DefaultPublicKeyPutHandler(username, publicKey, onComplete, onError);
//        routing.sendPublicKeyPUT(username, publicKey, 0, handler);
        // TODO use reliable core database
    }

    public void getPublicKey(byte[] username, PublicKeyGetHandlerCallback onComplete, ErrorHandler onError)
    {
//        PublicKeyGetHandler handler = new DefaultPublicKeyGetHandler(username, onComplete, onError);
//        routing.sendPublicKeyGET(username, handler);
        // TODO use reliable core database
    }

    public void createUser(byte[] username, byte[] publicKey, PublicKeyPutHandlerCallback onComplete)
    {
        createUser(username, publicKey, onComplete, new ErrorHandler());
    }

    public void getPublicKey(byte[] username, PublicKeyGetHandlerCallback onComplete)
    {
        getPublicKey(username, onComplete, new ErrorHandler());
    }

    // DHT API

    static class ErrorHandler extends OnFailure {    @Override public final void onFailure(Throwable e) {      e.printStackTrace();    } }
    static class PrintResult<T> extends OnSuccess<T> {    @Override public final void onSuccess(T t) {      System.out.println(t);    } }

    static class PutHandler<T> extends OnSuccess<T> {
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
                HTTPSMessenger.putFragment(offer.getTarget().addr, offer.getTarget().port, "/" + Arrays.bytesToHex(key), value);
                callback.callback(offer);
            } catch (IOException e)
            {
                // what do here?
                e.printStackTrace();
            }
        }
    }

    static class GetHandler<T> extends OnSuccess<T> {
        private final byte[] key;
        private final GetHandlerCallback callback;

        public GetHandler(byte[] key, GetHandlerCallback callback) {
            this.key = key;
            this.callback = callback;
        }

        @Override public final void onSuccess(T obj)
        {
            GetOffer offer = (GetOffer) obj;
            try {
                byte[] frag = HTTPSMessenger.getFragment(offer.getTarget().addr, offer.getTarget().port, "/" + Arrays.bytesToHex(key));
                callback.callback(offer);
            } catch (IOException e)
            {
                // what do here?
                e.printStackTrace();
            }
        }
    }

    // Fragment API
    // 256 bit key / 32 byte
    public void put(byte[] key, byte[] value, PutHandlerCallback onComplete, OnFailure onError)
    {
        assert(key.length == 32);
        Future<Object> fut = ask(router, new MessageMailbox(new Message.PUT(key, value.length)), 5000);
        fut.onSuccess(new PutHandler(key, value, onComplete), system.dispatcher());
        fut.onFailure(onError, system.dispatcher());
    }

    public void contains(byte[] key, GetHandlerCallback onComplete, OnFailure onError)
    {
        Future<Object> fut = ask(router, new MessageMailbox(new Message.GET(key)), 5000);
        fut.onSuccess(new GetHandler(key, onComplete), system.dispatcher());
        fut.onFailure(onError, system.dispatcher());
    }

    public void get(byte[] key, GetHandlerCallback onComplete, OnFailure onError)
    {
        Future<Object> fut = ask(router, new MessageMailbox(new Message.GET(key)), 5000);
        fut.onSuccess(new GetHandler(key, onComplete), system.dispatcher());
        fut.onFailure(onError, system.dispatcher());
    }

    public void put(byte[] key, byte[] value, PutHandlerCallback onComplete)
    {
        put(key, value, onComplete, new ErrorHandler());
    }

    public void contains(byte[] key, GetHandlerCallback onComplete)
    {
        contains(key, onComplete, new ErrorHandler());
    }

    public void get(byte[] key, GetHandlerCallback onComplete)
    {
        get(key, onComplete, new ErrorHandler());
    }
}
