package peergos.storage.net;

import peergos.crypto.*;
import peergos.storage.dht.*;
import peergos.user.fs.*;
import peergos.util.*;
import com.sun.net.httpserver.*;

import java.io.*;
import java.util.function.*;

public class DHTUserAPIHandler implements HttpHandler
{
    private final DHT dht;

    public DHTUserAPIHandler(DHT dht) throws IOException
    {
        this.dht = dht;
    }

    @Override
    public void handle(HttpExchange httpExchange) throws IOException {
        try {
            InputStream in = httpExchange.getRequestBody();
            DataInputStream din = new DataInputStream(in);
            int type = din.readInt();
            if (type == 0) {
                // PUT
                byte[] key = Serialize.deserializeByteArray(din, 64);
                byte[] value = Serialize.deserializeByteArray(din, Fragment.SIZE);
                byte[] owner = Serialize.deserializeByteArray(din, UserPublicKey.SIZE);
                byte[] sharingKey = Serialize.deserializeByteArray(din, UserPublicKey.SIZE);
                byte[] mapKey = Serialize.deserializeByteArray(din, 32);
                byte[] proof = Serialize.deserializeByteArray(din, 4096);
                dht.put(key, value, owner, sharingKey, mapKey, proof).thenAccept(new PutSuccess(httpExchange)).exceptionally(new Failure(httpExchange));
            } else if (type == 1) {
                // GET
                byte[] key = Serialize.deserializeByteArray(din, 64);
                dht.get(key).thenAccept(new GetSuccess(key, httpExchange)).exceptionally(new Failure(httpExchange));
            } else if (type == 2) {
                // CONTAINS
                byte[] key = Serialize.deserializeByteArray(din, 64);
                dht.contains(key).thenAccept(new ContainsSuccess(httpExchange)).exceptionally(new Failure(httpExchange));
            } else {
                httpExchange.sendResponseHeaders(404, 0);
            }
        } catch (Exception e) {
            e.printStackTrace();
            throw e;
        }
    }

    private static class PutSuccess implements Consumer<Boolean>
    {
        private final HttpExchange exchange;

        private PutSuccess(HttpExchange exchange)
        {
            this.exchange = exchange;
        }

        @Override
        public void accept(Boolean result) {
            try {
                exchange.sendResponseHeaders(200, 0);
                DataOutputStream dout = new DataOutputStream(exchange.getResponseBody());
                dout.writeInt(result ? 1 : 0); // success

                dout.flush();
                dout.close();
            } catch (IOException e)
            {
                e.printStackTrace();
            }
        }
    }

    private static class GetSuccess implements Consumer<byte[]>
    {
        private final HttpExchange exchange;
        private final byte[] key;

        private GetSuccess(byte[] key, HttpExchange exchange)
        {
            this.key = key;
            this.exchange = exchange;
        }

        @Override
        public void accept(byte[] value) {
            try {
                exchange.sendResponseHeaders(200, 0);
                DataOutputStream dout = new DataOutputStream(exchange.getResponseBody());
                dout.writeInt(1); // success
                Serialize.serialize(value, dout);
                dout.flush();
                dout.close();
            } catch (IOException e)
            {
                e.printStackTrace();
            }
        }
    }

    private static class ContainsSuccess implements Consumer<Integer>
    {
        private final HttpExchange exchange;

        private ContainsSuccess(HttpExchange exchange)
        {
            this.exchange = exchange;
        }

        @Override
        public void accept(Integer size) {
            try {
                exchange.sendResponseHeaders(200, 0);
                DataOutputStream dout = new DataOutputStream(exchange.getResponseBody());
                dout.writeInt(1); // success
                dout.writeInt(size);
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

        public Void apply(Throwable throwable) {
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
