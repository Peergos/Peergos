package peergos.storage.net;

import peergos.crypto.*;
import peergos.storage.dht.*;
import peergos.user.fs.*;
import peergos.util.*;
import com.sun.net.httpserver.*;

import java.io.*;

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
                PutHandlerCallback success = new PutSuccess(httpExchange);
                Failure error = new Failure(httpExchange);
                dht.put(key, value, owner, sharingKey, mapKey, proof, success, error);
            } else if (type == 1) {
                // GET
                byte[] key = Serialize.deserializeByteArray(din, 64);
                GetHandlerCallback success = new GetSuccess(key, httpExchange);
                Failure error = new Failure(httpExchange);
                dht.get(key, success, error);
            } else if (type == 2) {
                // CONTAINS
                byte[] key = Serialize.deserializeByteArray(din, 64);
                GetHandlerCallback success = new ContainsSuccess(httpExchange);
                Failure error = new Failure(httpExchange);
                dht.contains(key, success, error);
            } else {
                httpExchange.sendResponseHeaders(404, 0);
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

        public void onFailure(Throwable throwable) {
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
