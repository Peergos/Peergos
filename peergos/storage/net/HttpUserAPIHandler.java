package peergos.storage.net;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import peergos.storage.dht.*;
import peergos.user.fs.Fragment;
import peergos.util.ArrayOps;
import peergos.util.Serialize;

import java.io.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class HttpUserAPIHandler implements HttpHandler
{
    private final Router router;
    private ExecutorService executor = Executors.newFixedThreadPool(2);

    public HttpUserAPIHandler(Router r)
    {
        this.router = r;
    }

    @Override
    public void handle(HttpExchange httpExchange) throws IOException {
        try {
            InputStream in = httpExchange.getRequestBody();
            DataInputStream din = new DataInputStream(in);
            Message m = Message.read(din);
            if (m instanceof Message.PUT) {
                byte[] value = Serialize.deserializeByteArray(din, Fragment.SIZE);
                Future<Object> fut = router.ask(m);
                OnSuccess success = new DHTAPI.PutHandler(router, ((Message.PUT) m).getKey(), value, new PutSuccess(httpExchange));
                OnFailure failure = new Failure(httpExchange);
                FutureWrapper.followWith(fut, success, failure, executor);
            } else if (m instanceof Message.GET) {
                int type = din.readInt();
                if (type == 1) // GET
                {
                    Future<Object> fut = router.ask(m);
                    OnSuccess success = new DHTAPI.GetHandler(router, ((Message.GET) m).getKey(), new GetSuccess(((Message.GET) m).getKey(), httpExchange));
                    OnFailure failure = new Failure(httpExchange);
                    FutureWrapper.followWith(fut, success, failure, executor);
                } else if (type == 2) // CONTAINS
                {
                    Future<Object> fut = router.ask(m);
                    OnSuccess success = new DHTAPI.GetHandler(router, ((Message.GET) m).getKey(), new ContainsSuccess(httpExchange));
                    OnFailure failure = new Failure(httpExchange);
                    FutureWrapper.followWith(fut, success, failure, executor);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            throw e;
        }
    }

    private static class PutSuccess implements PutHandlerCallback
    {
        private final HttpExchange exchange;

        private PutSuccess(HttpExchange exchange)
        {
            this.exchange = exchange;
        }

        @Override
        public void callback(PutOffer offer) {
            try {
                exchange.sendResponseHeaders(200, 0);
                DataOutputStream dout = new DataOutputStream(exchange.getResponseBody());
                dout.writeInt(1); // success

                dout.flush();
                dout.close();
            } catch (IOException e)
            {
                e.printStackTrace();
            }
        }
    }

    private static class GetSuccess implements GetHandlerCallback
    {
        private final HttpExchange exchange;
        private final byte[] key;

        private GetSuccess(byte[] key, HttpExchange exchange)
        {
            this.key = key;
            this.exchange = exchange;
        }

        @Override
        public void callback(GetOffer offer) {
            try {
                exchange.sendResponseHeaders(200, 0);
                DataOutputStream dout = new DataOutputStream(exchange.getResponseBody());
                dout.writeInt(1); // success
                byte[] frag = HttpMessenger.getFragment(offer.getTarget().external, "/" + ArrayOps.bytesToHex(key));
                Serialize.serialize(frag, dout);
                dout.flush();
                dout.close();
            } catch (IOException e)
            {
                e.printStackTrace();
            }
        }
    }

    private static class ContainsSuccess implements GetHandlerCallback
    {
        private final HttpExchange exchange;

        private ContainsSuccess(HttpExchange exchange)
        {
            this.exchange = exchange;
        }

        @Override
        public void callback(GetOffer offer) {
            try {
                exchange.sendResponseHeaders(200, 0);
                DataOutputStream dout = new DataOutputStream(exchange.getResponseBody());
                dout.writeInt(1); // success
                dout.writeInt(offer.getSize());
                dout.flush();
                dout.close();
            } catch (IOException e)
            {
                e.printStackTrace();
            }
        }
    }

    private static class Failure implements OnFailure
    {
        private final HttpExchange exchange;

        private Failure(HttpExchange exchange)
        {
            this.exchange = exchange;
        }

        public void onFailure(java.lang.Throwable throwable) {
            try {
                exchange.sendResponseHeaders(200, 0);
                DataOutputStream dout = new DataOutputStream(exchange.getResponseBody());
                dout.writeInt(-1);
                Serialize.serialize(throwable.getMessage(), dout);
                dout.flush();
                dout.close();
            } catch (IOException e)
            {
                e.printStackTrace();
            }
        }
    }
}
