package peergos.storage.net;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import peergos.storage.dht.*;
import peergos.user.fs.Fragment;
import peergos.util.ArrayOps;
import peergos.util.Serialize;

import java.io.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.*;

public class DHTAPIHandler implements HttpHandler
{
    private final Router router;

    public DHTAPIHandler(Router r)
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
                router.ask(m)
                        .thenApply(new PeergosDHT.PutHandler(router, ((Message.PUT) m).getKey(), value))
                        .thenAccept(new PutSuccess(httpExchange))
                        .exceptionally(new Failure(httpExchange));
            } else if (m instanceof Message.GET) {
                int type = din.readInt();
                if (type == 1) // GET
                {
                    router.ask(m)
                            .thenApply(new PeergosDHT.GetHandler(router, ((Message.GET) m).getKey()))
                            .thenAccept(new GetSuccess(((Message.GET) m).getKey(), httpExchange))
                            .exceptionally(new Failure(httpExchange));
                } else if (type == 2) // CONTAINS
                {
                    router.ask(m)
                            .thenApply(new PeergosDHT.GetHandler(router, ((Message.GET) m).getKey()))
                            .thenAccept(new ContainsSuccess(httpExchange))
                            .exceptionally(new Failure(httpExchange));
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            throw e;
        }
    }

    private static class PutSuccess implements Consumer<PutOffer>
    {
        private final HttpExchange exchange;

        private PutSuccess(HttpExchange exchange)
        {
            this.exchange = exchange;
        }

        @Override
        public void accept(PutOffer offer) {
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

    private static class GetSuccess implements Consumer<GetOffer>
    {
        private final HttpExchange exchange;
        private final byte[] key;

        private GetSuccess(byte[] key, HttpExchange exchange)
        {
            this.key = key;
            this.exchange = exchange;
        }

        @Override
        public void accept(GetOffer offer) {
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

    private static class ContainsSuccess implements Consumer<GetOffer>
    {
        private final HttpExchange exchange;

        private ContainsSuccess(HttpExchange exchange)
        {
            this.exchange = exchange;
        }

        @Override
        public void accept(GetOffer offer) {
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

    private static class Failure implements Function<Throwable, Void>
    {
        private final HttpExchange exchange;

        private Failure(HttpExchange exchange)
        {
            this.exchange = exchange;
        }

        public Void apply(java.lang.Throwable throwable) {
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
            return null;
        }
    }
}
